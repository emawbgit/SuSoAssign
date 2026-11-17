package org.emawbgit.susoassign;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Survey Solutions HQ REST client: Basic auth over HTTPS, workspace-aware. */
final class Api {

    static final class Resp {
        int status;          // 0 when no HTTP response was received
        String body = "";
        boolean sent;        // false only when the request certainly never reached the server
        String error = "";
    }

    static final class Result {
        String key, status = "", id = "", link = "", message = "";
        int http;
    }

    final String base;       // https://server/workspace
    final String user;
    private final String auth;
    final Duration timeout;
    private final HttpClient client;
    int maxAttempts = 3;
    long backoffMillis = 1000;

    Api(String server, String workspace, String user, String password, int timeoutSeconds) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        URI u;
        try {
            u = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid server address '" + server + "'.");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && (host.equals("localhost") || host.equals("127.0.0.1"))))
            throw new IllegalArgumentException("The server address must start with https:// (got '" + server
                    + "'). Plain http would send the password unencrypted.");
        String ws = workspace == null || workspace.trim().isEmpty() ? "primary" : workspace.trim();
        this.base = s + "/" + ws;
        this.user = user;
        this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)   // a redirect would usually mean a wrong workspace/login page
                .proxy(ProxySelector.getDefault())
                .build();
    }

    Resp get(String path) { return call("GET", path, null); }

    Resp post(String path, String json) { return call("POST", path, json); }

    Resp patch(String path, String json) { return call("PATCH", path, json); }

    /** One request with safe retries: only when the server cannot have processed it. */
    Resp call(String method, String path, String json) {
        Resp r = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            r = once(method, path, json);
            boolean retry = !r.sent || r.status == 429 || r.status == 502 || r.status == 503 || r.status == 504;
            if (!retry || attempt == maxAttempts) break;
            long wait = backoffMillis << (attempt - 1);
            if (r.status == 429) wait = Math.max(wait, retryAfter(r));
            sleep(wait);
        }
        return r;
    }

    private Resp once(String method, String path, String json) {
        Resp r = new Resp();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(timeout)
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .header("User-Agent", "SuSoAssign-Stata");
            HttpRequest.BodyPublisher body = json == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
            if (json != null) b.header("Content-Type", "application/json; charset=utf-8");
            b.method(method, body);
            HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            r.sent = true;
            r.status = resp.statusCode();
            r.body = new String(resp.body(), StandardCharsets.UTF_8);
            if (r.status == 429) r.error = resp.headers().firstValue("Retry-After").orElse("");
        } catch (HttpConnectTimeoutException | ConnectException e) {
            r.sent = false;
            r.error = "Cannot connect to " + base + " (" + e.getClass().getSimpleName() + ").";
        } catch (HttpTimeoutException e) {
            r.sent = true;
            r.error = "No response within " + timeout.getSeconds() + " s.";
        } catch (IOException e) {
            r.sent = true;
            r.error = "Connection error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.sent = true;
            r.error = "Interrupted.";
        }
        return r;
    }

    private static long retryAfter(Resp r) {
        try {
            return Math.min(60_000, Long.parseLong(r.error.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================ assignments

    /**
     * POST every request with {@code parallel} workers. The calling thread only polls: it reports
     * progress and asks {@code ui} whether the user pressed Break (then no new request is started).
     */
    List<Result> upload(List<Preload.Request> reqs, int parallel, Set<String> skip, Ui ui) {
        List<Result> results = new ArrayList<>();
        ConcurrentLinkedQueue<Integer> todo = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < reqs.size(); i++) {
            Result res = new Result();
            res.key = reqs.get(i).key;
            results.add(res);
            if (skip != null && skip.contains(res.key)) { res.status = "skipped"; res.message = "Already created (resume)."; }
            else todo.add(i);
        }
        int total = todo.size();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger done = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < Math.max(1, Math.min(parallel, 32)); w++) {
            Thread t = new Thread(() -> {
                Integer i;
                while (!stop.get() && (i = todo.poll()) != null) {
                    fill(results.get(i), post("/api/v1/assignments", reqs.get(i).json));
                    done.incrementAndGet();
                }
            }, "susoassign-upload-" + w);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        long lastReport = System.currentTimeMillis();
        int reported = 0;
        while (alive(workers)) {
            sleep(200);
            if (ui.breakRequested() && !stop.get()) {
                stop.set(true);
                ui.info("Break: finishing requests already sent, starting no new ones...");
            }
            int d = done.get();
            if (d != reported && (System.currentTimeMillis() - lastReport > 3000)) {
                ui.info(String.format(Locale.ROOT, "  %d of %d sent", d, total));
                reported = d;
                lastReport = System.currentTimeMillis();
            }
        }
        for (Result r : results) if (r.status.isEmpty()) { r.status = "notsent"; r.message = "Not sent (stopped by Break)."; }
        return results;
    }

    private static boolean alive(List<Thread> ts) {
        for (Thread t : ts) if (t.isAlive()) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    static void fill(Result res, Resp r) {
        res.http = r.status;
        if (r.status == 200 || r.status == 201) {
            try {
                Object o = Json.parse(r.body);
                Map<String, Object> m = o instanceof Map ? (Map<String, Object>) o : null;
                Object a = m == null ? null : m.get("Assignment");
                if (a instanceof Map) res.id = Qx.str(((Map<String, Object>) a).get("Id"), "");
                if (m != null && m.get("WebInterviewLink") != null) res.link = String.valueOf(m.get("WebInterviewLink"));
            } catch (RuntimeException e) {
                res.message = "Created, but the response could not be read.";
            }
            res.status = "created";
            return;
        }
        if (r.status == 0) {
            res.status = r.sent ? "unknown" : "failed";
            res.message = r.error + (r.sent ? " The assignment may or may not exist: check HQ before re-sending." : "");
            return;
        }
        res.status = r.status >= 500 || r.status == 429 ? "failed" : "rejected";
        res.message = message(r);
    }

    /** Human-readable error from any HQ error body. */
    @SuppressWarnings("unchecked")
    static String message(Resp r) {
        if (r.status == 401) return "401 Unauthorized: wrong user/password, or the account is not an API user.";
        if (r.status == 403) return "403 Forbidden: the account has no access to this workspace or action.";
        if (r.status >= 300 && r.status < 400) return r.status + " redirect: check the server address and workspace name.";
        String body = r.body == null ? "" : r.body.trim();
        try {
            Object o = Json.parse(body);
            if (o instanceof String) return (String) o;
            if (o instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) o;
                Object vs = m.get("VerificationStatus");
                if (vs instanceof Map && ((Map<String, Object>) vs).get("Errors") instanceof List) {
                    List<String> parts = new ArrayList<>();
                    for (Object e : (List<Object>) ((Map<String, Object>) vs).get("Errors")) {
                        if (!(e instanceof Map)) continue;
                        Map<String, Object> em = (Map<String, Object>) e;
                        String code = Qx.str(em.get("Code"), Qx.str(em.get("ErrorCode"), ""));
                        String msg = Qx.str(em.get("Message"), Qx.str(em.get("ErrorMessage"), ""));
                        String refs = "";
                        if (em.get("References") instanceof List) {
                            List<String> rs = new ArrayList<>();
                            for (Object x : (List<Object>) em.get("References"))
                                if (x instanceof Map) {
                                    Map<String, Object> xm = (Map<String, Object>) x;
                                    String c = Qx.str(xm.get("Column"), ""), v = Qx.str(xm.get("Content"), "");
                                    if (!c.isEmpty() || !v.isEmpty()) rs.add(c + (v.isEmpty() ? "" : "=" + v));
                                }
                            if (!rs.isEmpty()) refs = " [" + String.join("; ", rs) + "]";
                        }
                        parts.add((code.isEmpty() ? "" : code + ": ") + msg + refs);
                    }
                    if (!parts.isEmpty()) return String.join(" | ", parts);
                }
                for (String k : new String[]{"Message", "message", "title", "detail"})
                    if (m.get(k) != null) return String.valueOf(m.get(k)) + (m.get("errors") != null ? " " + Json.write(m.get("errors")) : "");
            }
        } catch (RuntimeException ignore) {
            // not JSON: fall through to raw text
        }
        if (body.isEmpty()) return "HTTP " + r.status + ".";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    /** What the Stata side needs from the host: messages, a Break check. */
    interface Ui {
        void info(String s);

        void error(String s);

        boolean breakRequested();

        void local(String name, String value);
    }
}
