package org.emawbgit.susoassign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Questionnaire model built from a Survey Solutions questionnaire document
 * (the JSON returned by GET /api/v1/questionnaires/{id}/{version}/document,
 * or a Designer/HQ-export document.json).
 *
 * Only what preloading needs is kept: question variable, GUID, type, roster level.
 */
final class Qx {

    /** A roster level. Rosters that share a trigger and parent share one level (and one preload file). */
    static final class Level {
        final String key;
        final Level parent;
        final int depth;
        final List<String> names = new ArrayList<>();   // roster variable names on this level (first = file name)
        String source = "";                             // fixed | numeric | list | multi | yesno | main
        String triggerGuid = "";
        Question trigger;
        final List<Long> fixedCodes = new ArrayList<>();

        Level(String key, Level parent) {
            this.key = key;
            this.parent = parent;
            this.depth = parent == null ? 0 : parent.depth + 1;
        }

        String name() { return names.isEmpty() ? key : names.get(0); }

        /** Levels from the top roster down to this one (main level excluded). */
        List<Level> chain() {
            List<Level> out = new ArrayList<>();
            for (Level l = this; l != null && l.depth > 0; l = l.parent) out.add(0, l);
            return out;
        }
    }

    static final class Question {
        String var;
        String guid;          // 32 lowercase hex chars, no dashes
        String kind;          // single multi yesno numeric text date gps list barcode picture audio area
        boolean integer, ordered, featured, linked, timestamp;
        int scope;            // 0 interviewer, 1 supervisor, 2 headquarters (hidden), 3 hidden
        Integer maxAnswers;
        Set<Long> codes;      // inline categories; null when reusable/unknown
        Level level;

        boolean preloadable() {
            if (linked) return false;
            switch (kind) {
                case "picture": case "audio": case "area": return false;
                default: return true;
            }
        }

        boolean protectable() {
            return kind.equals("numeric") || kind.equals("list") || kind.equals("multi") || kind.equals("yesno");
        }
    }

    String id = "";
    String title = "";
    String var = "";
    final Level main = new Level("main", null);
    final Map<String, Question> byVar = new LinkedHashMap<>();      // lower-case var -> question
    final Map<String, Level> levelByRosterVar = new HashMap<>();    // lower-case roster var -> level
    final Set<String> variables = new LinkedHashSet<>();            // calculated variables (lower-case)
    final List<String> warnings = new ArrayList<>();

    private final Map<String, Level> levels = new LinkedHashMap<>();
    private final Map<String, Question> byGuid = new HashMap<>();

    Question question(String v) { return v == null ? null : byVar.get(v.toLowerCase(Locale.ROOT)); }

    Level rosterLevel(String rosterVar) {
        return rosterVar == null ? null : levelByRosterVar.get(rosterVar.toLowerCase(Locale.ROOT));
    }

    List<Level> rosterLevels() { return new ArrayList<>(levels.values()); }

    // ------------------------------------------------------------------ parsing

    @SuppressWarnings("unchecked")
    static Qx parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Questionnaire document is not a JSON object.");
        Map<String, Object> doc = (Map<String, Object>) root;
        // HQ may wrap the document: {"Questionnaire": {...}} or {"Document": {...}}
        for (String w : new String[]{"Questionnaire", "Document", "QuestionnaireDocument"}) {
            if (!doc.containsKey("Children") && doc.get(w) instanceof Map) doc = (Map<String, Object>) doc.get(w);
        }
        if (!(doc.get("Children") instanceof List))
            throw new IllegalArgumentException("Questionnaire document has no Children: is this a Survey Solutions questionnaire?");
        Qx q = new Qx();
        q.id = guid(str(doc.get("PublicKey"), str(doc.get("Id"), "")));
        q.title = str(doc.get("Title"), "");
        q.var = str(doc.get("VariableName"), "");
        q.main.source = "main";
        if (!q.var.isEmpty()) q.main.names.add(q.var);
        q.walk((List<Object>) doc.get("Children"), q.main);
        q.resolveTriggers();
        return q;
    }

    @SuppressWarnings("unchecked")
    private void walk(List<Object> items, Level level) {
        for (Object o : items) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) o;
            String cls = typeName(it.get("$type"));
            String gid = guid(str(it.get("PublicKey"), str(it.get("Id"), "")));
            List<Object> kids = it.get("Children") instanceof List ? (List<Object>) it.get("Children") : null;

            if (cls.equals("Group") || (cls.isEmpty() && kids != null)) {
                Level next = level;
                if (bool(it.get("IsRoster"))) {
                    Object src = it.get("RosterSizeSource");
                    boolean fixed = "1".equals(numStr(src)) || "fixedtitles".equalsIgnoreCase(str(src, ""));
                    String trig = fixed ? "" : guid(str(it.get("RosterSizeQuestionId"), ""));
                    String key = level.key + ">" + (fixed ? "fixed:" + gid : "q:" + trig);
                    next = levels.get(key);
                    if (next == null) {
                        next = new Level(key, level);
                        next.source = fixed ? "fixed" : "question";
                        next.triggerGuid = trig;
                        if (fixed && it.get("FixedRosterTitles") instanceof List) {
                            long k = 0;
                            for (Object t : (List<Object>) it.get("FixedRosterTitles")) {
                                Long code = t instanceof Map ? toLong(((Map<String, Object>) t).get("Value")) : null;
                                next.fixedCodes.add(code == null ? k : code);
                                k++;
                            }
                        }
                        levels.put(key, next);
                    }
                    String rv = str(it.get("VariableName"), "");
                    if (!rv.isEmpty()) {
                        next.names.add(rv);
                        levelByRosterVar.put(rv.toLowerCase(Locale.ROOT), next);
                    }
                }
                if (kids != null) walk(kids, next);
                continue;
            }
            if (cls.equals("Variable")) {
                String n = str(it.get("Name"), str(it.get("VariableName"), ""));
                if (!n.isEmpty()) variables.add(n.toLowerCase(Locale.ROOT));
                continue;
            }
            if (cls.equals("StaticText")) continue;

            String kind = kindOf(cls, it.get("QuestionType"));
            if (kind == null) {
                warnings.add("Skipped unknown item type '" + cls + "'.");
                continue;
            }
            Question q = new Question();
            q.var = str(it.get("VariableName"), str(it.get("StataExportCaption"), ""));
            q.guid = gid;
            q.integer = bool(it.get("IsInteger"));
            q.ordered = bool(it.get("AreAnswersOrdered"));
            q.featured = bool(it.get("Featured")) || bool(it.get("Identifying"));
            q.timestamp = bool(it.get("IsTimestamp"));
            q.linked = !str(it.get("LinkedToQuestionId"), "").isEmpty() || !str(it.get("LinkedToRosterId"), "").isEmpty();
            q.scope = scope(it.get("QuestionScope"));
            Long max = toLong(it.get("MaxAllowedAnswers"));
            if (max == null) max = toLong(it.get("MaxAnswerCount"));
            q.maxAnswers = max == null ? null : max.intValue();
            if (kind.equals("multi") && bool(it.get("YesNoView"))) kind = "yesno";
            q.kind = kind;
            boolean reusable = !str(it.get("CategoriesId"), "").isEmpty()
                    || (it.get("Properties") instanceof Map && !str(((Map<String, Object>) it.get("Properties")).get("CategoriesId"), "").isEmpty());
            if (!reusable && it.get("Answers") instanceof List && !((List<Object>) it.get("Answers")).isEmpty()) {
                q.codes = new LinkedHashSet<>();
                for (Object a : (List<Object>) it.get("Answers")) {
                    if (!(a instanceof Map)) continue;
                    Long c = toLong(((Map<String, Object>) a).get("AnswerValue"));
                    if (c == null) c = toLong(((Map<String, Object>) a).get("AnswerCode"));
                    if (c != null) q.codes.add(c);
                }
                if (q.codes.isEmpty()) q.codes = null;
            }
            q.level = level;
            if (q.var.isEmpty()) {
                warnings.add("Question " + gid + " has no variable name; skipped.");
                continue;
            }
            byVar.put(q.var.toLowerCase(Locale.ROOT), q);
            byGuid.put(q.guid, q);
        }
    }

    private void resolveTriggers() {
        for (Level l : levels.values()) {
            if (!l.source.equals("question")) continue;
            Question t = byGuid.get(l.triggerGuid);
            if (t == null) {
                warnings.add("Roster " + l.name() + ": trigger question " + l.triggerGuid + " not found.");
                l.source = "unknown";
                continue;
            }
            l.trigger = t;
            switch (t.kind) {
                case "numeric": l.source = "numeric"; break;
                case "list": l.source = "list"; break;
                case "multi": l.source = "multi"; break;
                case "yesno": l.source = "yesno"; break;
                default: l.source = "unknown";
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final Map<String, String> KIND_BY_CLASS = new HashMap<>();
    private static final Map<String, String> KIND_BY_INT = new HashMap<>();
    static {
        String[][] c = {{"SingleQuestion", "single"}, {"MultyOptionsQuestion", "multi"}, {"MultiOptionsQuestion", "multi"},
                {"NumericQuestion", "numeric"}, {"TextQuestion", "text"}, {"DateTimeQuestion", "date"},
                {"GpsCoordinateQuestion", "gps"}, {"TextListQuestion", "list"}, {"QRBarcodeQuestion", "barcode"},
                {"MultimediaQuestion", "picture"}, {"AudioQuestion", "audio"}, {"AreaQuestion", "area"}};
        for (String[] p : c) KIND_BY_CLASS.put(p[0], p[1]);
        String[][] n = {{"0", "single"}, {"3", "multi"}, {"4", "numeric"}, {"5", "date"}, {"6", "gps"}, {"7", "text"},
                {"9", "list"}, {"10", "barcode"}, {"11", "picture"}, {"12", "area"}, {"13", "audio"}};
        for (String[] p : n) KIND_BY_INT.put(p[0], p[1]);
    }

    private static String kindOf(String cls, Object qtype) {
        String k = KIND_BY_CLASS.get(cls);
        if (k == null && qtype != null) k = KIND_BY_INT.get(numStr(qtype));
        return k;
    }

    /** "Main.Core.Entities.SubEntities.Question.NumericQuestion, Main.Core" -> "NumericQuestion". */
    static String typeName(Object t) {
        String s = str(t, "");
        int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(0, comma);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1).trim() : s.trim();
    }

    static String guid(String s) { return s == null ? "" : s.replace("-", "").replace("{", "").replace("}", "").trim().toLowerCase(Locale.ROOT); }

    private static int scope(Object o) {
        if (o == null) return 0;
        String s = numStr(o);
        if (s.matches("-?\\d+")) return Integer.parseInt(s);
        switch (s.toLowerCase(Locale.ROOT)) {
            case "supervisor": return 1;
            case "headquarter": case "headquarters": return 2;
            case "hidden": return 3;
            default: return 0;
        }
    }

    static String str(Object o, String dflt) { return o == null ? dflt : String.valueOf(o); }

    static boolean bool(Object o) { return o instanceof Boolean ? (Boolean) o : "true".equalsIgnoreCase(String.valueOf(o)); }

    private static String numStr(Object o) {
        if (o instanceof Double && ((Double) o) == Math.floor((Double) o)) return Long.toString(((Double) o).longValue());
        return o == null ? "" : String.valueOf(o).trim();
    }

    static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) { double d = (Double) o; return d == Math.floor(d) ? (long) d : null; }
        try {
            double d = Double.parseDouble(String.valueOf(o).trim());
            return d == Math.floor(d) && !Double.isInfinite(d) ? (long) d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
