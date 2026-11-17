package org.emawbgit.susoassign;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Plain-Java test suite: java -ea -cp ... org.emawbgit.susoassign.TestAll <fixtures dir>. */
public final class TestAll {

    private static int passed = 0;
    private static Path fixtures;

    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args.length > 0 ? args[0] : "tests/fixtures");
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new AssertionError("run with java -ea");
        run("json", TestAll::json);
        run("tab", TestAll::tab);
        run("qx", TestAll::qx);
        run("qx fixtures from SuSoSimulate", TestAll::qxFixtures);
        run("encode all types", TestAll::encode);
        run("rosters and list remap", TestAll::rosters);
        run("validation", TestAll::validation);
        run("zip round trip", TestAll::zipRoundTrip);
        run("error messages", TestAll::messages);
        run("mock HQ upload", TestAll::mock);
        System.out.println("ALL " + passed + " TEST GROUPS PASSED");
    }

    private interface T { void run() throws Exception; }

    private static void run(String name, T t) throws Exception {
        t.run();
        passed++;
        System.out.println("ok  " + name);
    }

    private static void eq(Object want, Object got) {
        if (want == null ? got != null : !want.equals(got))
            throw new AssertionError("\nwant: " + want + "\ngot:  " + got);
    }

    // ---------------------------------------------------------------- helpers

    private static Qx testQx() throws IOException {
        return Qx.parse(new String(Files.readAllBytes(fixtures.resolve("test_qx.json")), StandardCharsets.UTF_8));
    }

    private static Tab tab(String name, String... lines) {
        return Tab.parse(name, String.join("\n", lines) + "\n", false);
    }

    private static Preload load(boolean strict, List<String> protect, Tab... tabs) throws IOException {
        Preload p = new Preload(testQx(), strict);
        p.load(Arrays.asList(tabs), protect);
        return p;
    }

    private static String errors(Preload p) {
        StringBuilder sb = new StringBuilder();
        for (Preload.Issue i : p.issues) sb.append(i.severity).append(": ").append(i.file).append('/').append(i.column).append(": ").append(i.message).append('\n');
        return sb.toString();
    }

    private static boolean has(Preload p, String severity, String fragment) {
        for (Preload.Issue i : p.issues) if (i.severity.equals(severity) && i.message.contains(fragment)) return true;
        return false;
    }

    private static String body(Preload p, int i) { return p.requests("11112222333344445555555555555555$3").get(i).json; }

    // ---------------------------------------------------------------- tests

    @SuppressWarnings("unchecked")
    private static void json() {
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"a\":[1,2.5,\"x\\u00e9\\\"\"],\"b\":null,\"c\":true}");
        eq(Arrays.asList(1L, 2.5, "xé\""), m.get("a"));
        eq("{\"s\":\"t\\\"ab\\tc\"}", Json.write(Collections.singletonMap("s", "t\"ab\tc")));
    }

    private static void tab() {
        Tab rfc = Tab.parse("t", "a\tb\r\n\"x \"\"y\"\"\"\t\"1\t2\"\r\n", true);
        eq("x \"y\"", rfc.rows.get(0)[0]);
        eq("1\t2", rfc.rows.get(0)[1]);
        Tab raw = Tab.parse("t", "﻿a\tb\n\"x\"\t\n", false);   // HQ: quotes are literal, BOM stripped
        eq("a", raw.header.get(0));
        eq("\"x\"", raw.rows.get(0)[0]);
        eq("", raw.rows.get(0)[1]);
        Tab ragged = Tab.parse("t", "a\tb\tc\n1\n", false);
        eq(3, ragged.rows.get(0).length);
    }

    private static void qx() throws IOException {
        Qx q = testQx();
        eq("hh", q.var);
        eq("11111111222233334444555555555555", q.id);
        eq("single", q.question("REGION").kind);
        eq("yesno", q.question("services").kind);
        eq(true, q.question("ranking").ordered);
        eq(null, q.question("crops").codes);               // reusable categories: not validated locally
        eq(false, q.question("photo").preloadable());
        eq(false, q.question("head").preloadable());        // linked
        eq(true, q.variables.contains("calc_total"));
        Qx.Level member = q.rosterLevel("member");
        eq(member, q.rosterLevel("member2"));               // same trigger -> same level
        eq("list", member.source);
        eq(Arrays.asList("member", "member2"), member.names);
        Qx.Level season = q.rosterLevel("season");
        eq("fixed", season.source);
        eq(Arrays.asList(1L, 2L), season.fixedCodes);
        eq(2, season.depth);
        eq("numeric", q.rosterLevel("plot").source);
        eq("multi", q.rosterLevel("asset").source);
        eq("e0000000000000000000000000000004", q.question("job_hours").guid);
        eq(2, q.question("job_hours").level.depth);
        eq("NumericQuestion", Qx.typeName("Main.Core.Entities.SubEntities.Question.NumericQuestion, Main.Core"));
    }

    private static void qxFixtures() throws IOException {
        for (String f : new String[]{"demo_household.json", "edge_cases.json"}) {
            Qx q = Qx.parse(new String(Files.readAllBytes(fixtures.resolve(f)), StandardCharsets.UTF_8));
            assert !q.byVar.isEmpty() : f;
        }
        Qx e = Qx.parse(new String(Files.readAllBytes(fixtures.resolve("edge_cases.json")), StandardCharsets.UTF_8));
        int notPreloadable = 0;
        for (Qx.Question q : e.byVar.values()) if (!q.preloadable()) notPreloadable++;
        assert notPreloadable >= 3 : "picture, audio and area must not be preloadable";
    }

    private static void encode() throws IOException {
        Preload p = load(true, Arrays.asList("members", "assets"),
                tab("hh", "interview__id\t_responsible\t_quantity\thhid\tregion\thhsize\tincome\tvisit_date\tloc__Latitude\tloc__Longitude\tassets__1\tassets__2\tassets__3\tassets__n1\tranking__1\tranking__2\tranking__3\tservices__1\tservices__2\tservices__3\tmembers__0\tmembers__1",
                        "A1\tint1\t1\tHH \"001\"\t2\t4\t.5\t2024-05-01\t-1.25\t36.8\t1\t0\t1\t-999999999\t2\t\t1\t1\t0\t\tAnn\tBob",
                        "A2\tsup1\t-1\t002\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t"));
        eq("", errors(p));
        eq("{\"Responsible\":\"int1\",\"Quantity\":1,\"QuestionnaireId\":\"11112222333344445555555555555555$3\",\"IdentifyingData\":["
                + "{\"Variable\":\"hhid\",\"Answer\":\"HH \\\"001\\\"\"},"
                + "{\"Variable\":\"region\",\"Answer\":\"2\"},"
                + "{\"Variable\":\"hhsize\",\"Answer\":\"4\"},"
                + "{\"Variable\":\"income\",\"Answer\":\"0.5\"},"
                + "{\"Variable\":\"visit_date\",\"Answer\":\"2024-05-01\"},"
                + "{\"Variable\":\"assets\",\"Answer\":\"[\\\"1\\\",\\\"3\\\"]\"},"
                + "{\"Variable\":\"ranking\",\"Answer\":\"[\\\"3\\\",\\\"1\\\"]\"},"
                + "{\"Variable\":\"services\",\"Answer\":\"[\\\"1 -> Yes\\\",\\\"2 -> No\\\"]\"},"
                + "{\"Variable\":\"members\",\"Answer\":\"[\\\"Ann\\\",\\\"Bob\\\"]\"},"
                + "{\"Variable\":\"loc\",\"Answer\":\"-1.25$36.8\"}],"
                + "\"ProtectedVariables\":[\"members\",\"assets\"]}", body(p, 0));
        eq("{\"Responsible\":\"sup1\",\"Quantity\":-1,\"QuestionnaireId\":\"11112222333344445555555555555555$3\",\"IdentifyingData\":[{\"Variable\":\"hhid\",\"Answer\":\"002\"}],"
                + "\"ProtectedVariables\":[\"members\",\"assets\"]}", body(p, 1));

        Preload web = load(true, null, tab("hh", "interview__id\t_responsible\t_webmode\t_email\t_password\t_record_audio\t_comment\t_target_area\t_aascope",
                "W1\tint1\t1\ta@b.org\t?\t0\tCall first\tarea 1\tcover, member"));
        eq("", errors(web));
        eq("{\"Responsible\":\"int1\",\"QuestionnaireId\":\"11112222333344445555555555555555$3\",\"IdentifyingData\":[],\"Email\":\"a@b.org\",\"Password\":\"?\","
                + "\"WebMode\":true,\"IsAudioRecordingEnabled\":false,\"Comments\":\"Call first\",\"TargetArea\":\"area 1\",\"AudioAuditScope\":[\"cover\",\"member\"]}", body(web, 0));
    }

    private static void rosters() throws IOException {
        Preload p = load(true, null,
                tab("hh", "interview__id\t_responsible\tmembers__0\tmembers__1\tn_plots\tassets__1\tassets__2",
                        "A1\tint1\tAnn\tBob\t2\t1\t0"),
                tab("member", "interview__id\tmember__id\tage\tsex\tphone\tjobs__0",
                        "A1\t1\t40\t1\t555\tfarmer",
                        "A1\t2\t12\t2\t\t"),
                tab("job", "interview__id\tmember__id\tjob__id\tjob_hours", "A1\t1\t1\t30"),
                tab("plot", "interview__id\tplot__id\tplot_area", "A1\t0\t1.5", "A1\t1\t2"),
                tab("season", "interview__id\tplot__id\tseason__id\tirrigated", "A1\t1\t2\t1"),
                tab("asset", "interview__id\tasset__id\tasset_n", "A1\t1\t2"));
        eq("", errors(p));
        String b = body(p, 0);
        // list-roster ids are positions 1..n in the data; HQ numbers list items from 0
        assert b.contains("{\"Identity\":\"e0000000000000000000000000000001_0\",\"Variable\":\"age\",\"Answer\":\"40\"}") : b;
        assert b.contains("{\"Identity\":\"e0000000000000000000000000000001_1\",\"Variable\":\"age\",\"Answer\":\"12\"}") : b;
        assert b.contains("{\"Identity\":\"e0000000000000000000000000000005_0\",\"Variable\":\"phone\",\"Answer\":\"555\"}") : b;   // alias roster member2
        assert b.contains("{\"Identity\":\"e0000000000000000000000000000003_0\",\"Variable\":\"jobs\",\"Answer\":\"[\\\"farmer\\\"]\"}") : b;
        assert b.contains("{\"Identity\":\"e0000000000000000000000000000004_0-0\",\"Variable\":\"job_hours\",\"Answer\":\"30\"}") : b;
        assert b.contains("{\"Identity\":\"f0000000000000000000000000000001_0\",\"Variable\":\"plot_area\",\"Answer\":\"1.5\"}") : b;
        assert b.contains("{\"Identity\":\"f0000000000000000000000000000002_1-2\",\"Variable\":\"irrigated\",\"Answer\":\"1\"}") : b;
        assert b.contains("{\"Identity\":\"f0000000000000000000000000000003_1\",\"Variable\":\"asset_n\",\"Answer\":\"2\"}") : b;
        eq(7, p.rosterRows);

        // list built from the roster file's text column (HQ preload style, any codes): ordered by id
        Preload r = load(true, null,
                tab("hh", "interview__id\t_responsible", "A1\tint1"),
                tab("member", "interview__id\tmember__id\tmembers\tage", "A1\t20\tBob\t12", "A1\t10\tAnn\t40"));
        eq("", errors(r));
        String rb = body(r, 0);
        assert rb.contains("{\"Variable\":\"members\",\"Answer\":\"[\\\"Ann\\\",\\\"Bob\\\"]\"}") : rb;
        assert rb.contains("\"e0000000000000000000000000000001_0\",\"Variable\":\"age\",\"Answer\":\"40\"") : rb;
        assert rb.contains("\"e0000000000000000000000000000001_1\",\"Variable\":\"age\",\"Answer\":\"12\"") : rb;

        // partial rows of a longer list: position 3 is the third item
        Preload part = load(true, null,
                tab("hh", "interview__id\t_responsible\tmembers__0\tmembers__1\tmembers__2", "A1\tint1\ta\tb\tc"),
                tab("member", "interview__id\tmember__id\tage", "A1\t3\t9"));
        eq("", errors(part));
        assert body(part, 0).contains("\"e0000000000000000000000000000001_2\"") : body(part, 0);

        // a Survey Solutions export: item codes in members__kc (1, 4 after a deletion) place the rows
        Preload exp = load(true, null,
                tab("hh", "interview__id\t_responsible\tmembers__0\tmembers__0c\tmembers__1\tmembers__1c", "A1\tint1\tAnn\t1\tCy\t4"),
                tab("member", "interview__id\tmember__id\tage", "A1\t4\t30", "A1\t1\t50"));
        eq("", errors(exp));
        String eb = body(exp, 0);
        assert eb.contains("\"e0000000000000000000000000000001_1\",\"Variable\":\"age\",\"Answer\":\"30\"") : eb;
        assert eb.contains("\"e0000000000000000000000000000001_0\",\"Variable\":\"age\",\"Answer\":\"50\"") : eb;
        assert !eb.contains("members__") && !eb.contains("\"4\"") : eb;
    }

    private static void validation() throws IOException {
        Preload p = load(false, Arrays.asList("region", "members__1", "nosuch"),
                tab("hh", "interview__id\t_responsible\t_quantity\t_email\t_password\tregion\thhsize\tvisit_date\tloc__Latitude\tassets\tphoto\tcalc_total\tweight\tage\tassets__4\t_foo",
                        "A1\t\t-2\tx@y.z\tABC\t7\t2.5\t01/05/2024\t10\t1\t\t\t1\t\t1\t",
                        "A1\tint1\t\t\t\t\t\t\t\t\t\t\t\t\t\t"),
                tab("plot", "interview__id\tplot__id\tplot_area", "ZZ\t0\t1", "A1\tx\t1"),
                tab("weird", "interview__id\ta", "A1\t1"));
        String e = errors(p);
        for (String frag : new String[]{"only numeric, list and multi-select", "Use the root variable name", "'nosuch' is not a question",
                "does not match", "is a multi question: expected column(s) assets__<code>", "picture", "calculated variable",
                "'age' is in roster 'member'", "Unknown service column"})
            assert e.contains(frag) : "missing '" + frag + "' in\n" + e;
        assert has(p, "warning", "'weight' is not a question") : e;    // non-strict: dropped with a warning
        Preload p2 = load(true, null,
                tab("hh", "interview__id\t_responsible\t_quantity\t_email\t_password\tregion\thhsize\tvisit_date\tloc__Latitude\tn_plots",
                        "A1\t\t-2\tnot-an-email\tABC\t7\t2.5\t01/05/2024\t10\t1",
                        "A1\tint1\t\t\t\t\t\t\t\t",
                        "A2\tint1\t5\tq@r.st\t\t\t\t\t\t"),
                tab("plot", "interview__id\tplot__id\tplot_area", "ZZ\t0\t1", "A2\tx\t1"));
        String e2 = errors(p2);
        for (String frag : new String[]{"Responsible (_responsible) is required", "Quantity must be an integer >= -1",
                "only allowed for web-mode", "Invalid email", "Password must be", "Code 7 is not a category", "integer question",
                "ISO date", "needs both __Latitude and __Longitude", "Duplicate assignment key", "no assignment 'ZZ'",
                "roster id must be an integer", "must have quantity 1"})
            assert e2.contains(frag) : "missing '" + frag + "' in\n" + e2;

        // numeric roster ids outside 0..n-1, fixed codes, multi-trigger codes, list size
        Preload p3 = load(true, null,
                tab("hh", "interview__id\t_responsible\tn_plots\tassets__1\tranking__4\tmembers__0", "A1\tint1\t2\t1\t1\tAnn"),
                tab("plot", "interview__id\tplot__id\tplot_area", "A1\t2\t1"),
                tab("season", "interview__id\tplot__id\tseason__id\tirrigated", "A1\t0\t3\t1"),
                tab("asset", "interview__id\tasset__id\tasset_n", "A1\t2\t1"),
                tab("member", "interview__id\tmember__id\tage", "A1\t1\t3", "A1\t2\t4"));
        String e3 = errors(p3);
        // a Survey Solutions export fed in as is: system columns and list codes dropped quietly,
        // extra GPS properties reported once per column
        Preload ex = load(true, null, tab("hh",
                "interview__key\tinterview__id\tsssys_irnd\t_responsible\tmembers__0\tmembers__0c\tloc__Latitude\tloc__Longitude\tloc__Accuracy\tinterview__status\tassignment__id",
                "11-22\tA1\t0.5\tint1\tAnn\t1\t1\t2\t5\t100\t7",
                "11-23\tA2\t0.6\tint1\tBo\t1\t3\t4\t5\t100\t8"));
        eq(0, ex.errorCount());
        eq(1, ex.issues.size());
        assert body(ex, 0).contains("{\"Variable\":\"members\",\"Answer\":\"[\\\"Ann\\\"]\"},{\"Variable\":\"loc\",\"Answer\":\"1$2\"}") : body(ex, 0);
        for (String frag : new String[]{"outside 0..1", "not an item of fixed roster", "not selected in 'assets'", "Code 4 is not a category of 'ranking'", "List-roster id 2 does not match an item of 'members'"})
            assert e3.contains(frag) : "missing '" + frag + "' in\n" + e3;
    }

    private static void zipRoundTrip() throws IOException {
        Path dir = Files.createTempDirectory("susoassign");
        // Stata export delimited style: RFC quotes, ".5", ".a"
        Files.write(dir.resolve("hh.tab"), ("interview__id\t_responsible\tregion\tincome\tmembers__0\tmembers__1\tnote_var\r\n"
                + "A1\tint1\t.a\t-.5\t\"Ann \"\"A\"\"\"\tBob\tx\r\n").getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("member.tab"), "interview__id\tmember__id\tage\r\nA1\t1\t40\r\n".getBytes(StandardCharsets.UTF_8));
        Preload p = new Preload(testQx(), false);
        p.load(Tab.readAll(dir, true), Collections.singletonList("members"));
        eq(0, p.errorCount());
        Path zip = dir.resolve("out.zip");
        p.writeZip(zip);
        List<Tab> back = Tab.readAll(zip, false);
        eq(Arrays.asList("hh", "member", "protected__variables"), Arrays.asList(back.get(0).name, back.get(1).name, back.get(2).name));
        eq(Arrays.asList("interview__id", "_responsible", "region", "income", "members__0", "members__1"), back.get(0).header);   // note_var dropped
        eq("", back.get(0).rows.get(0)[2]);              // .a -> empty
        eq("-0.5", back.get(0).rows.get(0)[3]);
        eq("Ann \"A\"", back.get(0).rows.get(0)[4]);      // literal quotes, as HQ reads them
        eq(Arrays.asList("interview__id", "member__id", "age", "members"), back.get(1).header);   // item text added (PL0052)
        eq("Ann \"A\"", back.get(1).rows.get(0)[3]);
        // the zip is read back (as upload does) into exactly the same requests
        Preload again = new Preload(testQx(), true);
        again.load(back, null);
        eq("", errors(again));
        eq(body(p, 0), body(again, 0));
    }

    private static void messages() {
        Api.Resp r = new Api.Resp();
        r.status = 400;
        r.body = "{\"Assignment\":null,\"VerificationStatus\":{\"Errors\":[{\"Code\":\"PL0026\",\"Message\":\"User not found\",\"References\":[{\"Column\":\"_responsible\",\"Content\":\"bob\"}]}]}}";
        eq("PL0026: User not found [_responsible=bob]", Api.message(r));
        r.body = "\"Question(s) not found: foo\"";
        eq("Question(s) not found: foo", Api.message(r));
        r.body = "Invalid data.";
        eq("Invalid data.", Api.message(r));
        r.status = 401;
        assert Api.message(r).startsWith("401");
        Api.Result res = new Api.Result();
        r.status = 201;
        r.body = "{\"Assignment\":{\"Id\":42,\"ResponsibleName\":\"x\"},\"WebInterviewLink\":\"https://s/w/1\"}";
        Api.fill(res, r);
        eq("created", res.status);
        eq("42", res.id);
        eq("https://s/w/1", res.link);
        Api.Resp t = new Api.Resp();
        t.sent = true;
        t.error = "No response";
        Api.fill(res = new Api.Result(), t);
        eq("unknown", res.status);
        try {
            new Api("http://example.org", "", "u", "p", 30);
            throw new AssertionError("http must be refused");
        } catch (IllegalArgumentException expected) { /* ok */ }
        new Api("http://localhost:1", "", "u", "p", 30);
    }

    // ---------------------------------------------------------------- mock HQ

    private static void mock() throws Exception {
        MockHQ m = new MockHQ(0, new String(Files.readAllBytes(fixtures.resolve("test_qx.json")), StandardCharsets.UTF_8));
        try {
            String server = m.url();
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            Map<String, String> locals = new java.util.HashMap<>();
            Api.Ui ui = new Api.Ui() {
                public void info(String s) { log.add(s); }
                public void error(String s) { log.add("ERR " + s); }
                public boolean breakRequested() { return false; }
                public void local(String n, String v) { locals.put(n, v); }
            };
            eq(677, Commands.setup(ui, server, "ws", "u", "wrong", 6));
            eq(0, Commands.setup(ui, server, "ws", "u", "p", 6));
            Commands.api().backoffMillis = 50;
            Path dir = Files.createTempDirectory("susoassign-mock");
            Commands.questionnaires(ui, dir.resolve("q.tab").toString());
            assert new String(Files.readAllBytes(dir.resolve("q.tab")), StandardCharsets.UTF_8).contains("11112222333344445555555555555555$3");

            Path src = dir.resolve("in");
            Files.createDirectories(src);
            StringBuilder main = new StringBuilder("interview__id\t_responsible\thhid\n");
            String[] resp = {"int1", "bad", "plain", "flaky", "down", "slow", "int1", "int1"};
            for (int i = 0; i < resp.length; i++) main.append("K").append(i).append('\t').append(resp[i]).append("\tH").append(i).append('\n');
            Files.write(src.resolve("hh.tab"), main.toString().getBytes(StandardCharsets.UTF_8));
            Path results = dir.resolve("res.tab"), skip = dir.resolve("skip.txt");
            Files.write(skip, "K7\n".getBytes(StandardCharsets.UTF_8));
            Commands.upload(ui, "", "11112222-3333-4444-5555-555555555555", "3", src.toString(), "", dir.resolve("iss.tab").toString(),
                    dir.resolve("req.tab").toString(), results.toString(), skip.toString(), 4, false);
            List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
            String all = String.join("\n", lines);
            assert lines.get(1).startsWith("K0\tcreated\t201\t") : all;
            assert lines.get(2).startsWith("K1\trejected\t400\t\t\tPL0026: User not found") : all;
            assert lines.get(3).startsWith("K2\trejected\t400\t\t\tQuestion(s) not found: x") : all;
            assert lines.get(4).startsWith("K3\tcreated\t201\t7") : all;      // 429 retried
            assert lines.get(5).startsWith("K4\tfailed\t503") : all;          // retried, then gave up
            assert lines.get(6).startsWith("K5\tunknown\t") : all;            // timeout: never resent
            assert lines.get(8).startsWith("K7\tskipped") : all;              // resume
            eq(1, m.slow.get());
            eq(3, m.down.get());
            eq("3", locals.get("created"));                                    // K0, K3 (after 429), K6
            eq("1", locals.get("unknown"));
            eq(10, m.posts.get());                                              // 7 sent + 1 retry (429) + 2 retries (503)
            assert m.bodies.get(0).contains("\"QuestionnaireId\":\"11112222333344445555555555555555$3\"");

            Commands.archive(ui, "5, 6 x", dir.resolve("arch.tab").toString());
            eq("2", locals.get("archived"));
        } finally {
            m.stop();
        }
    }

}
