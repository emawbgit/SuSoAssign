package org.emawbgit.susoassign;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Survey Solutions HQ (workspace "ws", user "u", password "p").
 * The responsible name scripts the answer: bad, plain (400), flaky (429 then 201),
 * down (503), slow (7 s), anything else (201).
 * Standalone: java -cp classes;test org.emawbgit.susoassign.MockHQ <port> <questionnaire.json>
 */
final class MockHQ {
    final AtomicInteger posts = new AtomicInteger(), flaky = new AtomicInteger(), slow = new AtomicInteger(), down = new AtomicInteger();
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    final HttpServer srv;

    MockHQ(int port, String qxJson) throws IOException {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        srv.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String path = ex.getRequestURI().getPath();
            String body = new String(Tab.readAllBytes(ex.getRequestBody()), StandardCharsets.UTF_8);
            int code;
            String out;
            if (!"Basic dTpw".equals(auth)) { code = 401; out = ""; }
            else if (!path.startsWith("/ws/")) { code = 404; out = ""; }
            else if (path.equals("/ws/api/v1/questionnaires")) {
                code = 200;
                out = "{\"Questionnaires\":[{\"QuestionnaireId\":\"11112222-3333-4444-5555-555555555555\",\"Version\":3,\"Title\":\"T\",\"Variable\":\"hh\"}],\"TotalCount\":1}";
            } else if (path.equals("/ws/api/v1/questionnaires/11112222333344445555555555555555/3/document")) { code = 200; out = qxJson; }
            else if (path.equals("/ws/api/v1/assignments") && ex.getRequestMethod().equals("POST")) {
                posts.incrementAndGet();
                bodies.add(body);
                String resp = body.replaceAll(".*\"Responsible\":\"([^\"]*)\".*", "$1");
                switch (resp) {
                    case "bad": code = 400; out = "{\"VerificationStatus\":{\"Errors\":[{\"Code\":\"PL0026\",\"Message\":\"User not found\"}]}}"; break;
                    case "plain": code = 400; out = "\"Question(s) not found: x\""; break;
                    case "flaky": code = flaky.getAndIncrement() == 0 ? 429 : 201; out = code == 201 ? "{\"Assignment\":{\"Id\":7}}" : ""; break;
                    case "down": down.incrementAndGet(); code = 503; out = ""; break;
                    case "slow":
                        slow.incrementAndGet();
                        try { Thread.sleep(7000); } catch (InterruptedException ignore) { }
                        code = 201; out = "{\"Assignment\":{\"Id\":8}}"; break;
                    default: code = 201; out = "{\"Assignment\":{\"Id\":" + (100 + posts.get()) + "}}";
                }
            } else if (path.matches("/ws/api/v1/assignments/\\d+/archive")) { code = 200; out = ""; }
            else { code = 404; out = ""; }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        srv.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);   // never keep the JVM alive after a failed assertion
            return t;
        }));
        srv.start();
    }

    String url() { return "http://127.0.0.1:" + srv.getAddress().getPort(); }

    void stop() { srv.stop(0); }

    public static void main(String[] args) throws Exception {
        MockHQ m = new MockHQ(Integer.parseInt(args[0]), new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        System.out.println("MockHQ on " + m.url());
        Thread.currentThread().join();
    }
}
