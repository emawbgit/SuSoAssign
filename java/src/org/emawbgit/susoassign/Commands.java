package org.emawbgit.susoassign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The work behind each susoassign subcommand. Independent of Stata (talks through Api.Ui),
 * so it runs the same in Stata, in the tests and in CI.
 */
final class Commands {

    private static Api api;   // set by setup(); lives as long as Stata's JVM

    private Commands() { }

    static Api api() {
        if (api == null) throw new IllegalStateException("Not connected: run -susoassign setup- first.");
        return api;
    }

    static void use(Api a) { api = a; }

    static Api current() { return api; }

    // ------------------------------------------------------------------ setup

    static int setup(Api.Ui ui, String server, String workspace, String user, String password, int timeout) {
        if (password == null || password.isEmpty()) password = System.getenv("SUSO_PASSWORD");
        if (password == null || password.isEmpty()) {
            ui.error("No password: give password() or set the environment variable SUSO_PASSWORD.");
            return 198;
        }
        Api a;
        try {
            a = new Api(server, workspace, user, password, timeout);
        } catch (IllegalArgumentException e) {
            ui.error(e.getMessage());
            return 198;
        }
        Api.Resp r = a.get("/api/v1/questionnaires?limit=1&offset=1");
        if (r.status != 200) {
            ui.error("Connection test failed: " + (r.status == 0 ? r.error : Api.message(r)));
            if (r.status == 404) ui.error("404: check the workspace name (use the workspace NAME, not its display name).");
            return 677;
        }
        api = a;
        ui.local("base", a.base);
        return 0;
    }

    // ------------------------------------------------------------------ questionnaires

    @SuppressWarnings("unchecked")
    static int questionnaires(Api.Ui ui, String outFile) throws IOException {
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 1000; page++) {
            Api.Resp r = api().get("/api/v1/questionnaires?limit=40&offset=" + page);
            if (r.status != 200) {
                ui.error("Could not list questionnaires: " + (r.status == 0 ? r.error : Api.message(r)));
                return 677;
            }
            Map<String, Object> m = (Map<String, Object>) Json.parse(r.body);
            List<Object> qs = m.get("Questionnaires") instanceof List ? (List<Object>) m.get("Questionnaires") : new ArrayList<>();
            int added = 0;
            for (Object o : qs) {
                Map<String, Object> q = (Map<String, Object>) o;
                String id = Qx.guid(Qx.str(q.get("QuestionnaireId"), ""));
                String ver = Qx.str(Qx.toLong(q.get("Version")), "");
                if (!seen.add(id + "$" + ver)) continue;
                rows.add(new String[]{Qx.str(q.get("Title"), ""), Qx.str(q.get("Variable"), ""), id, ver, id + "$" + ver});
                added++;
            }
            Long total = Qx.toLong(m.get("TotalCount"));
            if (added == 0 || (total != null && rows.size() >= total)) break;
        }
        writeTab(outFile, Arrays.asList("title", "variable", "qid", "version", "identity"), rows);
        ui.local("n", Integer.toString(rows.size()));
        return 0;
    }

    // ------------------------------------------------------------------ questionnaire document

    static String document(String qid, String version) {
        Api.Resp r = api().get("/api/v1/questionnaires/" + Qx.guid(qid) + "/" + version.trim() + "/document");
        if (r.status != 200)
            throw new IllegalStateException("Could not download questionnaire " + qid + " version " + version + ": "
                    + (r.status == 0 ? r.error : r.status == 404 ? "not found on this server/workspace." : Api.message(r)));
        return r.body;
    }

    static Qx loadQx(String qxFile, String qid, String version) throws IOException {
        String json = qxFile != null && !qxFile.isEmpty()
                ? new String(Files.readAllBytes(Paths.get(qxFile)), StandardCharsets.UTF_8)
                : document(qid, version);
        return Qx.parse(json);
    }

    // ------------------------------------------------------------------ create

    /** Validate Stata's exported tab files and write an HQ-ready zip (only when there are no errors). */
    static int create(Api.Ui ui, String qxFile, String qid, String version, String inDir, String outZip,
                      String protect, String issuesFile) throws IOException {
        Qx qx = loadQx(qxFile, qid, version);
        for (String w : qx.warnings) ui.info("{txt}note: " + w);
        Preload p = new Preload(qx, false);
        List<Tab> tabs = new ArrayList<>();
        for (Tab t : Tab.readAll(Paths.get(inDir), true))   // Stata writes the main file as main__assignments.tab
            tabs.add(t.name.equals("main__assignments") && !qx.var.isEmpty() ? new Tab(qx.var, t.header, t.rows) : t);
        p.load(tabs, splitList(protect));
        writeIssues(issuesFile, p);
        report(ui, p);
        if (p.errorCount() == 0) p.writeZip(Paths.get(outZip));
        return 0;
    }

    // ------------------------------------------------------------------ upload

    static int upload(Api.Ui ui, String qxFile, String qid, String version, String src, String protect,
                      String issuesFile, String requestsFile, String resultsFile, String skipFile,
                      int parallel, boolean dryrun) throws IOException {
        Qx qx = loadQx(qxFile, qid, version);
        for (String w : qx.warnings) ui.info("{txt}note: " + w);
        Preload p = new Preload(qx, true);
        p.load(Tab.readAll(Paths.get(src), false), splitList(protect));
        writeIssues(issuesFile, p);
        report(ui, p);
        if (p.errorCount() > 0) return 0;
        String qidver = Qx.guid(qid) + "$" + version.trim();
        List<Preload.Request> reqs = p.requests(qidver);
        if (requestsFile != null && !requestsFile.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            for (Preload.Request r : reqs) rows.add(new String[]{r.key, r.json});
            writeTab(requestsFile, Arrays.asList("key", "json"), rows);
        }
        if (dryrun) return 0;
        Set<String> skip = new HashSet<>();
        if (skipFile != null && !skipFile.isEmpty() && Files.exists(Paths.get(skipFile)))
            for (String line : Files.readAllLines(Paths.get(skipFile), StandardCharsets.UTF_8))
                if (!line.trim().isEmpty()) skip.add(line.trim());
        List<Api.Result> res = api().upload(reqs, parallel, skip, ui);
        writeResults(resultsFile, res);
        int created = 0, rejected = 0, failed = 0, unknown = 0, skipped = 0, notsent = 0;
        for (Api.Result r : res) {
            switch (r.status) {
                case "created": created++; break;
                case "rejected": rejected++; break;
                case "unknown": unknown++; break;
                case "skipped": skipped++; break;
                case "notsent": notsent++; break;
                default: failed++;
            }
        }
        ui.local("created", Integer.toString(created));
        ui.local("rejected", Integer.toString(rejected));
        ui.local("failed", Integer.toString(failed));
        ui.local("unknown", Integer.toString(unknown));
        ui.local("skipped", Integer.toString(skipped));
        ui.local("notsent", Integer.toString(notsent));
        return 0;
    }

    // ------------------------------------------------------------------ archive

    static int archive(Api.Ui ui, String idsCsv, String resultsFile) throws IOException {
        List<Api.Result> res = new ArrayList<>();
        int ok = 0;
        for (String id : splitList(idsCsv)) {
            Api.Result r = new Api.Result();
            r.key = id;
            r.id = id;
            if (!id.matches("\\d+")) { r.status = "rejected"; r.message = "Not an assignment id."; res.add(r); continue; }
            Api.Resp resp = api().patch("/api/v1/assignments/" + id + "/archive", null);
            r.http = resp.status;
            if (resp.status == 200 || resp.status == 204) { r.status = "archived"; ok++; }
            else { r.status = resp.status == 0 ? "failed" : "rejected"; r.message = resp.status == 0 ? resp.error : Api.message(resp); }
            res.add(r);
        }
        writeResults(resultsFile, res);
        ui.local("archived", Integer.toString(ok));
        return 0;
    }

    // ------------------------------------------------------------------ io helpers

    private static void report(Api.Ui ui, Preload p) {
        int w = p.issues.size() - p.errorCount();
        ui.local("n_assignments", Integer.toString(p.assignmentCount()));
        ui.local("n_rosterrows", Integer.toString(p.rosterRows));
        ui.local("n_errors", Integer.toString(p.errorCount()));
        ui.local("n_warnings", Integer.toString(w));
        ui.local("protected", String.join(" ", p.protectedVars));
    }

    static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.split("[,\\s]+")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out;
    }

    private static void writeIssues(String file, Preload p) throws IOException {
        if (file == null || file.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        for (Preload.Issue i : p.issues) rows.add(new String[]{i.severity, i.key, i.file, i.column, i.message});
        writeTab(file, Arrays.asList("severity", "key", "file", "column", "message"), rows);
    }

    private static void writeResults(String file, List<Api.Result> res) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (Api.Result r : res)
            rows.add(new String[]{r.key, r.status, r.http == 0 ? "" : Integer.toString(r.http), r.id, r.link, r.message});
        writeTab(file, Arrays.asList("key", "status", "http", "assignment_id", "web_link", "message"), rows);
    }

    /** Tab file for Stata's import delimited: fields cleaned of tabs/newlines, no quotes. */
    static void writeTab(String file, List<String> header, List<String[]> rows) throws IOException {
        Path p = Paths.get(file);
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", header)).append('\n');
        for (String[] r : rows) {
            for (int k = 0; k < r.length; k++) {
                if (k > 0) sb.append('\t');
                // read back with import delimited, bindquote(nobind): quotes stay literal
                sb.append(r[k] == null ? "" : r[k].replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
            }
            sb.append('\n');
        }
        Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
