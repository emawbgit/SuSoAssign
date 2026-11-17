package org.emawbgit.susoassign;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Reads assignments in the Survey Solutions preload layout (one main file, one file per roster
 * level, optional protected__variables.tab), validates them against the questionnaire, and turns
 * each assignment into the JSON body of POST /api/v1/assignments.
 */
final class Preload {

    /** First code of a text-list item when the list is sent through the API as an array. Verified live (see NEWS.md). */
    static final long LIST_CODE_BASE = 1;

    static final String[] SERVICE = {"_responsible", "_quantity", "_webmode", "_email", "_password",
            "_record_audio", "_comment", "_target_area", "_aascope"};
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD = Pattern.compile("^(\\?|[A-Z0-9]{6,})$");
    private static final Pattern DATE = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,7})?)?(Z|[+-]\\d{2}:?\\d{2})?)?$");
    private static final Pattern MISSING = Pattern.compile("^\\.[a-z]?$");
    /** System columns of a Survey Solutions export, so last round's export can be fed in as is. */
    private static final Set<String> EXPORT_SYSTEM = new HashSet<>(java.util.Arrays.asList(
            "interview__key", "sssys_irnd", "has__errors", "interview__status"));

    static final class Issue {
        final String severity, key, file, column, message;
        Issue(String severity, String key, String file, String column, String message) {
            this.severity = severity; this.key = key; this.file = file; this.column = column; this.message = message;
        }
    }

    static final class Request {
        final String key, json;
        Request(String key, String json) { this.key = key; this.json = json; }
    }

    /** A column of an input file, classified. */
    private static final class Col {
        int index;
        String name;
        String role;           // key | rid | service | simple | multi | list | gps | listtext | drop
        Qx.Question q;
        Qx.Level ridLevel;     // for rid
        String part;           // multi code / list index / gps property
    }

    /** One file bound to a questionnaire level. */
    private static final class Bound {
        Tab tab;
        Qx.Level level;
        List<Col> cols = new ArrayList<>();
        int keyCol = -1;
    }

    /** Parsed answer, kept for roster-trigger checks. */
    private static final class Ans {
        Double number;
        List<Long> codes;      // multi: selected (in order); yesno: yes codes
        List<String> items;    // list
    }

    final Qx qx;
    final boolean strictUnknown;
    final List<Issue> issues = new ArrayList<>();
    final Set<String> protectedVars = new LinkedHashSet<>();
    private final List<Bound> files = new ArrayList<>();
    private Bound main;
    private final List<String> keys = new ArrayList<>();
    // key -> identifying data items, in order
    private final Map<String, List<Map<String, Object>>> items = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> service = new LinkedHashMap<>();
    // question var -> (key|vector) -> parsed answer
    private final Map<String, Map<String, Ans>> answers = new HashMap<>();
    // list level remaps: level key + key + parent vector -> (original code -> api code)
    private final Map<String, Map<Long, Long>> listRemap = new HashMap<>();
    int rosterRows = 0;

    Preload(Qx qx, boolean strictUnknown) {
        this.qx = qx;
        this.strictUnknown = strictUnknown;
    }

    int errorCount() { int n = 0; for (Issue i : issues) if (i.severity.equals("error")) n++; return n; }

    int assignmentCount() { return keys.size(); }

    private void error(String key, String file, String col, String msg) { issues.add(new Issue("error", key, file, col, msg)); }

    private void warn(String key, String file, String col, String msg) { issues.add(new Issue("warning", key, file, col, msg)); }

    // ================================================================ load + validate

    void load(List<Tab> tabs, Collection<String> extraProtect) {
        List<Tab> data = new ArrayList<>();
        for (Tab t : tabs) {
            if (t.name.equalsIgnoreCase("protected__variables")) readProtected(t);
            else data.add(t);
        }
        if (extraProtect != null) for (String p : extraProtect) if (!p.trim().isEmpty()) protectedVars.add(p.trim());
        checkProtected();

        // bind files to levels
        Tab mainTab = null;
        for (Tab t : data) if (t.name.equalsIgnoreCase(qx.var)) mainTab = t;
        if (mainTab == null) {
            List<Tab> cands = new ArrayList<>();
            for (Tab t : data) if (qx.rosterLevel(t.name) == null && deepestRid(t) == null) cands.add(t);
            if (cands.size() == 1) mainTab = cands.get(0);
        }
        if (mainTab == null) {
            error("", "", "", "No main file found. Name it after the questionnaire variable: " + qx.var + ".tab");
            return;
        }
        for (Tab t : data) {
            Bound b = new Bound();
            b.tab = t;
            if (t == mainTab) { b.level = qx.main; main = b; }
            else {
                b.level = qx.rosterLevel(t.name);
                if (b.level == null) b.level = deepestRid(t);
                if (b.level == null) {
                    error("", t.name, "", "File '" + t.name + "' does not match the questionnaire or any of its rosters.");
                    continue;
                }
            }
            files.add(b);
        }
        // main first, then rosters top-down, so trigger answers exist before their rosters are checked
        files.sort((a, b) -> Integer.compare(a.level.depth, b.level.depth));
        for (Bound b : files) classify(b);
        if (errorCount() > 0) return;
        readMain();
        for (Bound b : files) if (b != main) readRoster(b);
        checkRosterTriggers();
    }

    private Qx.Level deepestRid(Tab t) {
        Qx.Level best = null;
        for (String h : t.header) {
            String low = h.toLowerCase(Locale.ROOT);
            if (!low.endsWith("__id")) continue;
            Qx.Level l = qx.rosterLevel(low.substring(0, low.length() - 4));
            if (l != null && (best == null || l.depth > best.depth)) best = l;
        }
        return best;
    }

    private void readProtected(Tab t) {
        int c = t.col("variable__name");
        if (c < 0) {
            error("", t.name, "", "protected__variables.tab must have one column named variable__name.");
            return;
        }
        for (String[] r : t.rows) if (!r[c].trim().isEmpty()) protectedVars.add(r[c].trim());
    }

    private void checkProtected() {
        for (String v : protectedVars) {
            if (v.contains("__")) {
                error("", "protected__variables", v, "Use the root variable name (e.g. 'hhmembers', not 'hhmembers__2').");
                continue;
            }
            Qx.Question q = qx.question(v);
            if (q == null) error("", "protected__variables", v, "Variable '" + v + "' is not a question of this questionnaire.");
            else if (!q.protectable())
                error("", "protected__variables", v, "'" + v + "' is a " + q.kind
                        + " question; only numeric, list and multi-select (incl. yes/no) answers can be protected.");
        }
    }

    private void classify(Bound b) {
        Set<String> chainNames = new HashSet<>();
        for (Qx.Level l : b.level.chain()) for (String n : l.names) chainNames.add(n.toLowerCase(Locale.ROOT));
        Set<Qx.Level> ridSeen = new HashSet<>();
        for (int i = 0; i < b.tab.header.size(); i++) {
            Col c = new Col();
            c.index = i;
            c.name = b.tab.header.get(i);
            String low = c.name.toLowerCase(Locale.ROOT);
            b.cols.add(c);
            if (low.equals("interview__id") || low.equals("assignment__id")) {
                if (b.keyCol < 0) { b.keyCol = i; c.role = "key"; }
                else c.role = "drop";
                continue;
            }
            if (EXPORT_SYSTEM.contains(low)) { c.role = "drop"; continue; }   // Survey Solutions export columns
            if (low.endsWith("__id") && chainNames.contains(low.substring(0, low.length() - 4))) {
                c.role = "rid";
                c.ridLevel = qx.rosterLevel(low.substring(0, low.length() - 4));
                ridSeen.add(c.ridLevel);
                continue;
            }
            if (low.startsWith("_")) {
                String s = low.equals("_comments") ? "_comment" : low;
                boolean known = false;
                for (String k : SERVICE) known |= k.equals(s);
                if (!known) { unknown(b, c, "Unknown service column '" + c.name + "'."); continue; }
                if (b != main) { error("", b.tab.name, c.name, "Service column '" + c.name + "' belongs in the main file."); c.role = "drop"; continue; }
                c.role = "service";
                c.part = s;
                continue;
            }
            int sep = low.indexOf("__");
            String var = sep < 0 ? low : low.substring(0, sep);
            String suffix = sep < 0 ? null : low.substring(sep + 2);
            Qx.Question q = qx.question(var);
            if (q == null) {
                if (qx.variables.contains(var)) error("", b.tab.name, c.name, "'" + var + "' is a calculated variable and cannot be preloaded.");
                else if (qx.rosterLevel(var) != null && "id".equals(suffix))
                    error("", b.tab.name, c.name, "Roster id column '" + c.name + "' does not belong to file '" + b.tab.name + "'.");
                else unknown(b, c, "'" + c.name + "' is not a question of questionnaire '" + qx.var + "'.");
                if (c.role == null) c.role = "drop";
                continue;
            }
            c.q = q;
            // a list trigger's text column inside its own list-roster file
            if (suffix == null && q.kind.equals("list") && b.level.source.equals("list") && b.level.trigger == q) {
                c.role = "listtext";
                continue;
            }
            if (q.level != b.level) {
                error("", b.tab.name, c.name, "Question '" + q.var + "' is in "
                        + (q.level.depth == 0 ? "the main level" : "roster '" + q.level.name() + "'")
                        + "; put it in file '" + (q.level.depth == 0 ? qx.var : q.level.name()) + ".tab'.");
                c.role = "drop";
                continue;
            }
            if (!q.preloadable()) {
                error("", b.tab.name, c.name, "'" + q.var + "' (" + (q.linked ? "linked " : "") + q.kind + ") cannot be preloaded.");
                c.role = "drop";
                continue;
            }
            switch (q.kind) {
                case "multi": case "yesno":
                    if (suffix == null || !suffix.matches("n?\\d+")) { shape(b, c, q, q.var + "__<code> (e.g. " + q.var + "__1)"); continue; }
                    c.role = "multi";
                    c.part = suffix.startsWith("n") ? "-" + suffix.substring(1) : suffix;
                    break;
                case "list":
                    if (suffix != null && suffix.matches("\\d+c")) { c.role = "drop"; continue; }   // export item codes: the API numbers items itself
                    if (suffix == null || !suffix.matches("\\d+")) { shape(b, c, q, q.var + "__0, " + q.var + "__1, ..."); continue; }
                    c.role = "list";
                    c.part = suffix;
                    break;
                case "gps":
                    if (suffix == null || !suffix.matches("latitude|longitude|altitude|accuracy|timestamp")) {
                        shape(b, c, q, q.var + "__Latitude and " + q.var + "__Longitude");
                        continue;
                    }
                    if (!suffix.equals("latitude") && !suffix.equals("longitude")) {
                        warn("", b.tab.name, c.name, "The API preloads GPS latitude and longitude only; column dropped.");
                        c.role = "drop";
                        continue;
                    }
                    c.role = "gps";
                    c.part = suffix;
                    break;
                default:
                    if (suffix != null) { shape(b, c, q, q.var); continue; }
                    c.role = "simple";
            }
        }
        if (b.keyCol < 0 && b != main)
            error("", b.tab.name, "", "Roster file '" + b.tab.name + "' needs an interview__id column linking rows to the main file.");
        for (Qx.Level l : b.level.chain())
            if (!ridSeen.contains(l))
                error("", b.tab.name, "", "Roster file '" + b.tab.name + "' needs the column " + l.name() + "__id.");
    }

    private void unknown(Bound b, Col c, String msg) {
        c.role = "drop";
        if (strictUnknown) error("", b.tab.name, c.name, msg);
        else warn("", b.tab.name, c.name, msg + " Column dropped.");
    }

    private void shape(Bound b, Col c, Qx.Question q, String expected) {
        error("", b.tab.name, c.name, "'" + q.var + "' is a " + q.kind + " question: expected column(s) " + expected + ".");
        c.role = "drop";
    }

    // ---------------------------------------------------------------- main file

    private void readMain() {
        Set<String> seen = new HashSet<>();
        Map<String, String> passwords = new HashMap<>();
        int rowNo = 0;
        for (String[] r : main.tab.rows) {
            rowNo++;
            String key = main.keyCol >= 0 ? r[main.keyCol].trim() : Integer.toString(rowNo);
            if (key.isEmpty()) { error("row " + rowNo, main.tab.name, "interview__id", "Empty assignment key."); continue; }
            if (!seen.add(key)) { error(key, main.tab.name, "interview__id", "Duplicate assignment key '" + key + "'."); continue; }
            keys.add(key);
            items.put(key, new ArrayList<>());
            Map<String, String> svc = new LinkedHashMap<>();
            service.put(key, svc);
            for (Col c : main.cols) if ("service".equals(c.role)) svc.put(c.part, r[c.index].trim());
            checkService(key, svc, passwords);
            readValues(main, r, key, new ArrayList<>());
        }
    }

    private void checkService(String key, Map<String, String> s, Map<String, String> passwords) {
        String file = main.tab.name;
        String resp = s.getOrDefault("_responsible", "");
        if (resp.isEmpty()) error(key, file, "_responsible", "Responsible (_responsible) is required.");
        String qty = norm(s.getOrDefault("_quantity", ""));
        Integer quantity = null;
        if (!qty.isEmpty()) {
            Long q = Qx.toLong(qty);
            if (q == null || q < -1 || q > Integer.MAX_VALUE) error(key, file, "_quantity", "Quantity must be an integer >= -1 (-1 = unlimited); got '" + qty + "'.");
            else quantity = q.intValue();
        }
        String wm = norm(s.getOrDefault("_webmode", ""));
        if (!wm.isEmpty() && !wm.equals("0") && !wm.equals("1")) error(key, file, "_webmode", "_webmode must be 1 (web) or 0 (CAPI).");
        boolean web = wm.equals("1");
        String email = s.getOrDefault("_email", "");
        if (!email.isEmpty()) {
            if (!web) error(key, file, "_email", "An email is only allowed for web-mode assignments (_webmode = 1).");
            if (!EMAIL.matcher(email).matches()) error(key, file, "_email", "Invalid email '" + email + "'.");
            if (quantity != null && quantity != 1) error(key, file, "_email", "Assignments with an email must have quantity 1.");
        }
        String pw = s.getOrDefault("_password", "");
        if (!pw.isEmpty()) {
            if (!web) error(key, file, "_password", "A password is only allowed for web-mode assignments (_webmode = 1).");
            if (!PASSWORD.matcher(pw).matches())
                error(key, file, "_password", "Password must be '?' (generate) or at least 6 upper-case letters/digits.");
            if (!pw.equals("?") && (quantity == null || quantity == 1)) {
                String other = passwords.put(pw, key);
                if (other != null) error(key, file, "_password", "Password already used by assignment '" + other + "'; passwords must be unique.");
            }
        }
        if (web && quantity == null && email.isEmpty() && pw.isEmpty())
            warn(key, file, "_quantity", "Web assignment with quantity 1 and no email/password is not accepted by Survey Solutions.");
        String ra = norm(s.getOrDefault("_record_audio", ""));
        if (!ra.isEmpty() && !ra.equals("0") && !ra.equals("1")) error(key, file, "_record_audio", "_record_audio must be 1 or 0.");
    }

    // ---------------------------------------------------------------- roster files

    private void readRoster(Bound b) {
        List<Qx.Level> chain = b.level.chain();
        Map<Qx.Level, Integer> ridCol = new HashMap<>();
        for (Col c : b.cols) if ("rid".equals(c.role)) ridCol.putIfAbsent(c.ridLevel, c.index);
        Set<String> seen = new HashSet<>();
        int rowNo = 0;
        for (String[] r : b.tab.rows) {
            rowNo++;
            String key = r[b.keyCol].trim();
            if (!items.containsKey(key)) {
                error(key, b.tab.name, "interview__id", "Row " + rowNo + ": no assignment '" + key + "' in the main file.");
                continue;
            }
            List<Long> vec = new ArrayList<>();
            boolean ok = true;
            for (Qx.Level l : chain) {
                String v = norm(r[ridCol.get(l)]);
                Long code = Qx.toLong(v);
                if (code == null) {
                    error(key, b.tab.name, l.name() + "__id", "Row " + rowNo + ": roster id must be an integer; got '" + v + "'.");
                    ok = false;
                    break;
                }
                vec.add(code);
            }
            if (!ok) continue;
            if (!seen.add(key + "|" + vec)) {
                error(key, b.tab.name, b.level.name() + "__id", "Duplicate roster row " + vec + ".");
                continue;
            }
            rosterRows++;
            Map<String, Ans> rowMarker = answers.computeIfAbsent("\u0000rows:" + b.level.key, k -> new HashMap<>());
            rowMarker.put(key + "|" + vec, new Ans());
            for (Col c : b.cols) {
                if (!"listtext".equals(c.role)) continue;
                String t = r[c.index];
                if (!norm(t).isEmpty()) {
                    Map<String, Ans> m = answers.computeIfAbsent("\u0000listtext:" + b.level.key, k -> new HashMap<>());
                    Ans a = new Ans();
                    a.items = new ArrayList<>();
                    a.items.add(t.trim());
                    m.put(key + "|" + vec, a);
                }
            }
            readValues(b, r, key, vec);
        }
    }

    // ---------------------------------------------------------------- values

    /** Collect the answers of one row into identifying-data items. */
    private void readValues(Bound b, String[] r, String key, List<Long> vec) {
        String file = b.tab.name;
        Map<Qx.Question, TreeMap<Integer, String>> lists = new LinkedHashMap<>();
        Map<Qx.Question, Map<Long, Double>> multis = new LinkedHashMap<>();
        Map<Qx.Question, Map<String, String>> gps = new LinkedHashMap<>();
        for (Col c : b.cols) {
            if (c.role == null) continue;
            String raw = r[c.index];
            switch (c.role) {
                case "simple": {
                    String v = c.q.kind.equals("text") || c.q.kind.equals("barcode") ? text(raw) : norm(raw);
                    if (v.isEmpty()) break;
                    String enc = simple(c.q, v, key, file);
                    if (enc != null) add(key, c.q, vec, enc);
                    if (enc != null && c.q.kind.equals("numeric")) {
                        Ans a = new Ans();
                        a.number = Double.parseDouble(enc);
                        put(c.q, key, vec, a);
                    }
                    break;
                }
                case "multi": {
                    String v = norm(raw);
                    if (v.isEmpty()) break;
                    Double d = num(v);
                    if (d == null) { error(key, file, c.name, "Expected a number (order/1 = selected, 0 = not selected); got '" + v + "'."); break; }
                    multis.computeIfAbsent(c.q, k -> new LinkedHashMap<>()).put(Long.parseLong(c.part), d);
                    break;
                }
                case "list": {
                    String v = text(raw);
                    if (!v.isEmpty()) lists.computeIfAbsent(c.q, k -> new TreeMap<>()).put(Integer.parseInt(c.part), v);
                    break;
                }
                case "gps": {
                    String v = norm(raw);
                    if (!v.isEmpty()) gps.computeIfAbsent(c.q, k -> new HashMap<>()).put(c.part, v);
                    break;
                }
                default:
            }
        }
        for (Map.Entry<Qx.Question, Map<Long, Double>> e : multis.entrySet()) encodeMulti(e.getKey(), e.getValue(), key, file, vec);
        for (Map.Entry<Qx.Question, TreeMap<Integer, String>> e : lists.entrySet()) {
            Qx.Question q = e.getKey();
            List<String> its = new ArrayList<>(e.getValue().values());
            if (q.maxAnswers != null && its.size() > q.maxAnswers)
                error(key, file, q.var, "List '" + q.var + "' has " + its.size() + " items; the maximum is " + q.maxAnswers + ".");
            Ans a = new Ans();
            a.items = its;
            put(q, key, vec, a);
            add(key, q, vec, Json.write(its));
        }
        for (Map.Entry<Qx.Question, Map<String, String>> e : gps.entrySet()) {
            Qx.Question q = e.getKey();
            Map<String, String> g = e.getValue();
            String lat = g.get("latitude"), lon = g.get("longitude");
            if (lat == null || lon == null) { error(key, file, q.var, "GPS '" + q.var + "' needs both __Latitude and __Longitude."); continue; }
            Double la = num(lat), lo = num(lon);
            if (la == null || lo == null || Math.abs(la) > 90 || Math.abs(lo) > 180) {
                error(key, file, q.var, "GPS '" + q.var + "' needs decimal degrees: latitude in [-90, 90], longitude in [-180, 180].");
                continue;
            }
            add(key, q, vec, lat + "$" + lon);
        }
    }

    private String simple(Qx.Question q, String v, String key, String file) {
        switch (q.kind) {
            case "single": {
                Long c = Qx.toLong(v);
                if (c == null) { error(key, file, q.var, "'" + q.var + "' needs an integer category code; got '" + v + "'."); return null; }
                if (q.codes != null && !q.codes.contains(c)) { error(key, file, q.var, "Code " + c + " is not a category of '" + q.var + "'."); return null; }
                return Long.toString(c);
            }
            case "numeric": {
                Double d = num(v);
                if (d == null) { error(key, file, q.var, "'" + q.var + "' needs a number (use '.' as decimal separator); got '" + v + "'."); return null; }
                if (q.integer) {
                    if (d != Math.floor(d) || Math.abs(d) > Integer.MAX_VALUE) {
                        error(key, file, q.var, "'" + q.var + "' is an integer question; got '" + v + "'.");
                        return null;
                    }
                    return Long.toString(d.longValue());
                }
                return v;
            }
            case "date": {
                if (!DATE.matcher(v).matches()) {
                    error(key, file, q.var, "'" + q.var + "' needs an ISO date (YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS); got '" + v + "'.");
                    return null;
                }
                return v.replace(' ', 'T');
            }
            default:
                return v;
        }
    }

    private void encodeMulti(Qx.Question q, Map<Long, Double> vals, String key, String file, List<Long> vec) {
        List<Map.Entry<Long, Double>> yes = new ArrayList<>(), no = new ArrayList<>();
        for (Map.Entry<Long, Double> e : vals.entrySet()) {
            if (e.getValue() > 0) yes.add(e);
            else if (e.getValue() == 0) no.add(e);
            else { error(key, file, q.var + "__" + e.getKey(), "Negative value in '" + q.var + "'."); return; }
            if (q.codes != null && !q.codes.contains(e.getKey())) {
                error(key, file, q.var + "__" + e.getKey(), "Code " + e.getKey() + " is not a category of '" + q.var + "'.");
                return;
            }
        }
        yes.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));   // stable: equal values keep column order
        if (q.maxAnswers != null && yes.size() > q.maxAnswers)
            error(key, file, q.var, "'" + q.var + "' has " + yes.size() + " selections; the maximum is " + q.maxAnswers + ".");
        List<String> arr = new ArrayList<>();
        Ans a = new Ans();
        a.codes = new ArrayList<>();
        for (Map.Entry<Long, Double> e : yes) {
            arr.add(q.kind.equals("yesno") ? e.getKey() + " -> Yes" : Long.toString(e.getKey()));
            a.codes.add(e.getKey());
        }
        if (q.kind.equals("yesno")) for (Map.Entry<Long, Double> e : no) arr.add(e.getKey() + " -> No");
        if (arr.isEmpty()) return;
        put(q, key, vec, a);
        add(key, q, vec, Json.write(arr));
    }

    private void add(String key, Qx.Question q, List<Long> vec, String answer) {
        Map<String, Object> it = new LinkedHashMap<>();
        if (!vec.isEmpty()) it.put("Identity", q.guid + "_" + vecString(vec));  // fixed up for list remaps in requests()
        it.put("Variable", q.var);
        it.put("Answer", answer);
        if (!vec.isEmpty()) it.put("\u0000vec", new ArrayList<>(vec));
        it.put("\u0000q", q);
        items.get(key).add(it);
    }

    private void put(Qx.Question q, String key, List<Long> vec, Ans a) {
        answers.computeIfAbsent(q.var.toLowerCase(Locale.ROOT), k -> new HashMap<>()).put(key + "|" + vec, a);
    }

    private Ans get(Qx.Question q, String key, List<Long> vec) {
        Map<String, Ans> m = answers.get(q.var.toLowerCase(Locale.ROOT));
        return m == null ? null : m.get(key + "|" + vec);
    }

    // ---------------------------------------------------------------- roster triggers

    private void checkRosterTriggers() {
        for (Qx.Level l : qx.rosterLevels()) {
            Map<String, Ans> rows = answers.get("\u0000rows:" + l.key);
            if (rows == null) continue;
            // group the level's rows by key + parent vector
            Map<String, List<Long>> groups = new LinkedHashMap<>();
            for (String kv : rows.keySet()) {
                int bar = kv.indexOf('|');
                String key = kv.substring(0, bar);
                List<Long> vec = parseVec(kv.substring(bar + 1));
                List<Long> parent = vec.subList(0, vec.size() - 1);
                groups.computeIfAbsent(key + "|" + parent, k -> new ArrayList<>()).add(vec.get(vec.size() - 1));
            }
            for (Map.Entry<String, List<Long>> g : groups.entrySet()) {
                int bar = g.getKey().indexOf('|');
                String key = g.getKey().substring(0, bar);
                List<Long> parent = parseVec(g.getKey().substring(bar + 1));
                List<Long> codes = g.getValue();
                codes.sort(Long::compare);
                String file = l.name();
                Ans t = l.trigger == null ? null : get(l.trigger, key, parent);
                switch (l.source) {
                    case "fixed":
                        for (Long c : codes)
                            if (!l.fixedCodes.contains(c)) error(key, file, file + "__id", "Roster id " + c + " is not an item of fixed roster '" + file + "' " + l.fixedCodes + ".");
                        break;
                    case "numeric":
                        if (t == null || t.number == null) {
                            warn(key, file, l.trigger.var, "Roster '" + file + "' has rows but its size question '" + l.trigger.var + "' is not preloaded.");
                            break;
                        }
                        for (Long c : codes)
                            if (c < 0 || c >= t.number)
                                error(key, file, file + "__id", "Roster id " + c + " is outside 0.." + (t.number.longValue() - 1)
                                        + " (" + l.trigger.var + " = " + t.number.longValue() + "). Numeric-roster rows are numbered from 0 when preloading.");
                        break;
                    case "multi": case "yesno":
                        if (t == null || t.codes == null) {
                            warn(key, file, l.trigger.var, "Roster '" + file + "' has rows but its trigger '" + l.trigger.var + "' is not preloaded.");
                            break;
                        }
                        for (Long c : codes)
                            if (!t.codes.contains(c)) error(key, file, file + "__id", "Roster id " + c + " is not selected in '" + l.trigger.var + "'.");
                        break;
                    case "list":
                        checkListRoster(l, key, parent, codes, t);
                        break;
                    default:
                }
            }
        }
    }

    /**
     * The API takes a text list as an array, so the server numbers its items LIST_CODE_BASE, +1, ...
     * Roster rows of a list roster must therefore use those codes; other codes are remapped by order.
     */
    private void checkListRoster(Qx.Level l, String key, List<Long> parent, List<Long> codes, Ans t) {
        String file = l.name();
        Qx.Question q = l.trigger;
        List<String> list = t == null ? null : t.items;
        if (list == null) {
            // build the list from the roster file's text column
            Map<String, Ans> texts = answers.get("\u0000listtext:" + l.key);
            List<String> built = new ArrayList<>();
            for (Long c : codes) {
                List<Long> v = new ArrayList<>(parent);
                v.add(c);
                Ans a = texts == null ? null : texts.get(key + "|" + v);
                if (a == null) {
                    error(key, file, q.var, "List roster '" + file + "' row " + c + " has no item text: preload '" + q.var
                            + "' as " + q.var + "__0, " + q.var + "__1, ... in the parent file or as column '" + q.var + "' in " + file + ".tab.");
                    return;
                }
                built.add(a.items.get(0));
            }
            Ans a = new Ans();
            a.items = built;
            put(q, key, parent, a);
            add(key, q, parent, Json.write(built));
            list = built;
        } else if (list.size() < codes.size()) {
            error(key, file, q.var, "List '" + q.var + "' has " + list.size() + " items but roster '" + file + "' has " + codes.size() + " rows.");
            return;
        }
        boolean inRange = true;
        for (Long c : codes) inRange &= c >= LIST_CODE_BASE && c < LIST_CODE_BASE + list.size();
        if (inRange) return;   // ids already are the codes the server will give the list items
        if (codes.size() != list.size()) {
            error(key, file, file + "__id", "List-roster ids " + codes + " must be " + LIST_CODE_BASE + ".." + (LIST_CODE_BASE + list.size() - 1)
                    + " (the position of each item in '" + q.var + "'): the API numbers text-list items itself.");
            return;
        }
        Map<Long, Long> remap = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) remap.put(codes.get(i), LIST_CODE_BASE + i);
        {
            warn(key, file, file + "__id", "List-roster ids " + codes + " renumbered " + LIST_CODE_BASE + ".." + (LIST_CODE_BASE + codes.size() - 1)
                    + " in order: the API numbers text-list items itself.");
            listRemap.put(l.key + "|" + key + "|" + parent, remap);
        }
    }

    // ================================================================ outputs

    /** One POST /api/v1/assignments body per assignment (call only when errorCount() == 0). */
    List<Request> requests(String questionnaireId) {
        List<Request> out = new ArrayList<>();
        for (String key : keys) {
            Map<String, String> s = service.get(key);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Responsible", s.getOrDefault("_responsible", ""));
            String qty = norm(s.getOrDefault("_quantity", ""));
            if (!qty.isEmpty()) body.put("Quantity", Qx.toLong(qty));
            body.put("QuestionnaireId", questionnaireId);
            List<Map<String, Object>> data = new ArrayList<>();
            for (Map<String, Object> it : items.get(key)) {
                Map<String, Object> o = new LinkedHashMap<>();
                if (it.containsKey("Identity")) o.put("Identity", identity(key, it));
                o.put("Variable", it.get("Variable"));
                o.put("Answer", it.get("Answer"));
                data.add(o);
            }
            body.put("IdentifyingData", data);
            putIf(body, "Email", s.get("_email"));
            putIf(body, "Password", s.get("_password"));
            if ("1".equals(norm(s.getOrDefault("_webmode", "")))) body.put("WebMode", Boolean.TRUE);
            String ra = norm(s.getOrDefault("_record_audio", ""));
            if (!ra.isEmpty()) body.put("IsAudioRecordingEnabled", ra.equals("1"));
            putIf(body, "Comments", s.get("_comment"));
            putIf(body, "TargetArea", s.get("_target_area"));
            String sc = s.getOrDefault("_aascope", "");
            if (!sc.trim().isEmpty()) {
                List<String> l = new ArrayList<>();
                for (String p : sc.split(",")) if (!p.trim().isEmpty()) l.add(p.trim());
                body.put("AudioAuditScope", l);
            }
            if (!protectedVars.isEmpty()) body.put("ProtectedVariables", new ArrayList<>(protectedVars));
            out.add(new Request(key, Json.write(body)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String identity(String key, Map<String, Object> it) {
        Qx.Question q = (Qx.Question) it.get("\u0000q");
        List<Long> orig = (List<Long>) it.get("\u0000vec");
        List<Long> vec = new ArrayList<>(orig);
        List<Qx.Level> chain = q.level.chain();
        // remaps are keyed by the original (input) parent vector
        for (int d = 0; d < vec.size() && d < chain.size(); d++) {
            Map<Long, Long> m = listRemap.get(chain.get(d).key + "|" + key + "|" + orig.subList(0, d));
            if (m != null && m.containsKey(orig.get(d))) vec.set(d, m.get(orig.get(d)));
        }
        return q.guid + "_" + vecString(vec);
    }

    /** Write the validated assignments as an HQ-ready preload zip (unknown columns dropped). */
    void writeZip(Path out) throws IOException {
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Bound b : files) {
                List<Integer> keep = new ArrayList<>();
                List<String> header = new ArrayList<>();
                for (Col c : b.cols) {
                    if (c.role == null || c.role.equals("drop")) continue;
                    keep.add(c.index);
                    header.add(c.role.equals("key") ? "interview__id" : c.role.equals("service") ? c.part : c.name);
                }
                List<String[]> rows = new ArrayList<>();
                for (String[] r : b.tab.rows) {
                    String[] o = new String[keep.size()];
                    for (int k = 0; k < o.length; k++) o[k] = cleanCell(r[keep.get(k)]);
                    rows.add(o);
                }
                if (b == main && b.keyCol < 0) {
                    header.add(0, "interview__id");
                    for (int k = 0; k < rows.size(); k++) {
                        String[] o = new String[rows.get(k).length + 1];
                        o[0] = Integer.toString(k + 1);
                        System.arraycopy(rows.get(k), 0, o, 1, rows.get(k).length);
                        rows.set(k, o);
                    }
                }
                String name = b == main ? qx.var : b.level.name();
                z.putNextEntry(new ZipEntry(name + ".tab"));
                new Tab(name, header, rows).write(z);
                z.closeEntry();
            }
            if (!protectedVars.isEmpty()) {
                List<String> h = new ArrayList<>();
                h.add("variable__name");
                List<String[]> rows = new ArrayList<>();
                for (String v : protectedVars) rows.add(new String[]{v});
                z.putNextEntry(new ZipEntry("protected__variables.tab"));
                new Tab("protected__variables", h, rows).write(z);
                z.closeEntry();
            }
        }
    }

    // ================================================================ helpers

    /** Normalise a cell: Stata/SuSo missing markers -> "", ".5" -> "0.5". */
    static String norm(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.isEmpty() || MISSING.matcher(s).matches() || s.equals("-999999999") || s.equals("##N/A##")) return "";
        if (s.startsWith(".") && s.length() > 1 && Character.isDigit(s.charAt(1))) return "0" + s;
        if (s.startsWith("-.") && s.length() > 2 && Character.isDigit(s.charAt(2))) return "-0" + s.substring(1);
        return s;
    }

    /** Text cells keep their content (a lone "." is a valid text); only SuSo's missing marker is dropped. */
    static String text(String v) {
        if (v == null) return "";
        String s = v.trim();
        return s.equals("##N/A##") ? "" : s;
    }

    private static String cleanCell(String v) {
        String s = v == null ? "" : v.trim();
        if (MISSING.matcher(s).matches() && !s.equals(".")) return "";   // Stata .a-.z; "." kept for text answers
        if (s.startsWith(".") && s.length() > 1 && Character.isDigit(s.charAt(1))) return "0" + s;
        if (s.startsWith("-.") && s.length() > 2 && Character.isDigit(s.charAt(2))) return "-0" + s.substring(1);
        return s;
    }

    static Double num(String v) {
        try {
            double d = Double.parseDouble(v);
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putIf(Map<String, Object> m, String k, String v) { if (v != null && !v.trim().isEmpty()) m.put(k, v.trim()); }

    static String vecString(List<Long> v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) { if (i > 0) sb.append('-'); sb.append(v.get(i)); }
        return sb.toString();
    }

    private static List<Long> parseVec(String s) {
        List<Long> out = new ArrayList<>();
        String t = s.replace("[", "").replace("]", "").trim();
        if (t.isEmpty()) return out;
        for (String p : t.split(",")) out.add(Long.parseLong(p.trim()));
        return out;
    }
}
