package org.emawbgit.susoassign;

import com.stata.sfi.Macro;
import com.stata.sfi.SFIToolkit;

/**
 * javacall entry points used by susoassign.ado. The only class that touches Stata's SFI API;
 * every method returns a Stata return code and never throws.
 */
public final class Bridge {

    private Bridge() { }

    private static final Api.Ui UI = new Api.Ui() {
        @Override public void info(String s) { SFIToolkit.displayln(s); }
        @Override public void error(String s) { SFIToolkit.errorln(s); }
        @Override public boolean breakRequested() { return SFIToolkit.pollnow() != 0; }
        @Override public void local(String name, String value) { Macro.setLocal(name, value == null ? "" : value); }
    };

    private interface Body { int run() throws Exception; }

    private static int guard(Body b) {
        try {
            return b.run();
        } catch (IllegalStateException | IllegalArgumentException e) {
            SFIToolkit.errorln(e.getMessage());
            return 198;
        } catch (Exception e) {
            SFIToolkit.errorln("susoassign: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 693;
        }
    }

    private static String arg(String[] a, int i) { return a != null && i < a.length && a[i] != null ? a[i] : ""; }

    private static int num(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    /** args: server workspace user password timeout */
    public static int setup(String[] a) {
        return guard(() -> Commands.setup(UI, arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3), num(arg(a, 4), 120)));
    }

    /** Session state for -susoassign setup- without arguments. */
    public static int status(String[] a) {
        return guard(() -> {
            Api c = Commands.current();
            UI.local("connected", c == null ? "0" : "1");
            UI.local("base", c == null ? "" : c.base);
            UI.local("conn_user", c == null ? "" : c.user);
            return 0;
        });
    }

    /** args: outfile */
    public static int questionnaires(String[] a) {
        return guard(() -> Commands.questionnaires(UI, arg(a, 0)));
    }

    /** args: qid version outfile */
    public static int document(String[] a) {
        return guard(() -> {
            java.nio.file.Files.write(java.nio.file.Paths.get(arg(a, 2)),
                    Commands.document(arg(a, 0), arg(a, 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return 0;
        });
    }

    /** args: qxfile qid version indir outzip protect issuesfile */
    public static int create(String[] a) {
        return guard(() -> Commands.create(UI, arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3), arg(a, 4), arg(a, 5), arg(a, 6)));
    }

    /** args: qxfile qid version src protect issues requests results skipfile parallel dryrun */
    public static int upload(String[] a) {
        return guard(() -> Commands.upload(UI, arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3), arg(a, 4), arg(a, 5),
                arg(a, 6), arg(a, 7), arg(a, 8), num(arg(a, 9), 4), "1".equals(arg(a, 10))));
    }

    /** args: ids results */
    public static int archive(String[] a) {
        return guard(() -> Commands.archive(UI, arg(a, 0), arg(a, 1)));
    }
}
