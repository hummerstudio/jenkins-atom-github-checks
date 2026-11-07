package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * github-checks against a stub that can say no: the preflight, the "is there a check with this name already" lookup,
 * the write, the append of annotations past fifty, and the read-back — each with the API answering an error, a body
 * that is not a check run, or a stored run that differs from the one sent. Also the local checks that must happen
 * before a single request.
 */
class GithubChecksExtraIT {

    private static final String CANONICAL = "abcdef1234567890abcdef1234567890abcdef12";

    private static Atom fresh() throws Exception {
        return Atom.of();
    }

    private static AtomResult run(Stub stub, Map<String, String> overrides) throws Exception {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("apiUrl", stub == null ? "http://127.0.0.1:1" : stub.api());
        inputs.put("account", "acme");
        inputs.put("repo", "parcel");
        inputs.put("sha", "abcdef1");
        inputs.put("name", "parcel/ci");
        inputs.put("token", "t0ken");
        inputs.putAll(overrides);
        return fresh().input(Json.write(inputs)).run();
    }

    private static Map<String, String> of(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("the inputs are checked before the first request, each with its own reason")
    void refusesBadInputLocally() throws Exception {
        record Case(Map<String, String> inputs, String code, String message) {}
        List<Case> cases = List.of(
                new Case(of("apiUrl", " "), "40001", "apiUrl is required"),
                new Case(of("repo", "other/parcel"), "40001", "is not under account 'acme'"),
                new Case(of("account", "", "repo", ""), "40001", "account and repo are required"),
                new Case(of("sha", ""), "40001", "sha (or GIT_COMMIT) is required"),
                new Case(of("status", "running"), "40001", "status must be queued, in_progress or completed"),
                new Case(of("conclusion", "maybe"), "40001", "unknown conclusion 'maybe'"),
                new Case(of("status", "queued", "conclusion", "success"), "40001", "only accepts a conclusion"),
                new Case(of("maxMessageSize", "50"), "40001", "maxMessageSize must be at least 100"),
                new Case(of("token", ""), "40002", "bind the 'token' credential"));

        for (Case one : cases) {
            Map<String, String> overrides = new LinkedHashMap<>(one.inputs());
            overrides.putIfAbsent("token", "t0ken");
            AtomResult result = run(null, overrides);

            assertNotEquals(0, result.exit(), one.message() + " must be refused: " + result);
            assertEquals(one.code(), result.field("errorCode"), one.message() + ": " + result.file("output.json"));
            assertTrue(result.field("message").contains(one.message()),
                    one.message() + ": message was " + result.field("message"));
        }
    }

    @Test
    @DisplayName("a name and a title default the way the plugin's do, and 'canceled' is sent as 'cancelled'")
    void defaultsNameTitleAndConclusion() throws Exception {
        try (Stub stub = Stub.start()) {
            AtomResult unnamed = fresh()
                    .input(Json.write(of("apiUrl", stub.api(), "account", "acme", "repo", "parcel",
                            "sha", "abcdef1", "token", "t0ken", "BUILD_STATUS", "WEIRD")))
                    .run();
            unnamed.assertOk();
            assertEquals("jenkins/atom", stub.stored.get("name"), "the default check name");
            assertEquals("Completed", output(stub.stored).get("title"), "an unknown build status is 'Completed'");
            assertEquals("neutral", stub.stored.get("conclusion"));

            stub.reset();
            AtomResult running = run(stub, of("status", "in_progress"));
            running.assertOk();
            assertEquals("In progress", output(stub.stored).get("title"), "an unfinished check says so");
            assertEquals("in_progress", stub.stored.get("status"));

            stub.reset();
            AtomResult cancelled = run(stub, of("conclusion", "canceled"));
            cancelled.assertOk();
            assertEquals("cancelled", stub.stored.get("conclusion"), "the API spelling is 'cancelled'");
            assertEquals("cancelled", cancelled.field("conclusion"));
        }
    }

    @Test
    @DisplayName("each preflight answer that is not a 2xx stops the run with the right class and code")
    void preflightFailures() throws Exception {
        record Case(String knob, String code, String message) {}
        List<Case> cases = List.of(
                new Case("userStatus=500", "40010", "GET /user answered 500"),
                new Case("repoStatus=401", "40002", "invalid to login"),
                new Case("repoStatus=500", "40010", "GET /repos/acme/parcel answered 500"),
                new Case("commitStatus=500", "40010", "GET the commit abcdef1 answered 500"),
                new Case("commitStatus=422", "40004", "specified commit does not exist"));

        try (Stub stub = Stub.start()) {
            for (Case one : cases) {
                stub.reset();
                stub.apply(one.knob());
                AtomResult result = run(stub, of());

                assertEquals(one.code(), result.field("errorCode"), one.knob() + ": " + result.file("output.json"));
                assertTrue(result.field("message").contains(one.message()),
                        one.knob() + ": message was " + result.field("message"));
                assertFalse(stub.sawWrite(), one.knob() + ": a failed preflight still wrote");
            }
        }
    }

    @Test
    @DisplayName("a failed lookup of existing runs is reported; a 404 simply means 'none yet'")
    void listRunsFailures() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.runsStatus = 401;
            assertEquals("40002", run(stub, of("externalId", "b-1")).field("errorCode"));

            stub.reset();
            stub.runsStatus = 500;
            AtomResult broken = run(stub, of("externalId", "b-1"));
            assertEquals("40010", broken.field("errorCode"), broken.toString());
            assertTrue(broken.field("message").contains("listing the check runs of " + CANONICAL),
                    broken.field("message"));

            stub.reset();
            stub.runsStatus = 404;
            AtomResult none = run(stub, of("externalId", "b-1"));
            none.assertOk();
            assertTrue(stub.sawWrite(), "a 404 on the lookup should still create the run");
        }
    }

    @Test
    @DisplayName("a refused create, a create without an id, and a refused append are all reported")
    void writeFailures() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.createStatus = 500;
            AtomResult refused = run(stub, of());
            assertEquals("40006", refused.field("errorCode"), refused.toString());
            assertTrue(refused.field("message").contains("for POST"), refused.field("message"));

            stub.reset();
            stub.createBody = "{\"name\":\"parcel/ci\"}";
            AtomResult noId = run(stub, of());
            assertEquals("40009", noId.field("errorCode"), noId.toString());
            assertTrue(noId.field("message").contains("without a check run object"), noId.field("message"));

            stub.reset();
            StringBuilder many = new StringBuilder("[");
            for (int i = 0; i < 60; i++) {
                if (i > 0) {
                    many.append(',');
                }
                many.append("{\"path\":\"src/F").append(i).append(".java\",\"startLine\":1,\"level\":\"warning\",")
                        .append("\"message\":\"finding ").append(i).append("\"}");
            }
            many.append(']');
            stub.patchStatus = 500;
            AtomResult append = run(stub, of("annotations", many.toString()));
            assertEquals("40006", append.field("errorCode"), append.toString());
            assertTrue(append.field("message").contains("appending annotations 50..60"),
                    append.field("message"));
        }
    }

    @Test
    @DisplayName("a size refusal whose numbers overflow is not retried, it is an error")
    void oversizedNumbersAreNotRetried() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.createStatus = 422;
            stub.errorBody = "{\"message\":\"Only 99999999999999999999 characters are allowed; 5 were supplied\"}";
            AtomResult result = run(stub, of());

            assertEquals("40006", result.field("errorCode"), result.toString());
            assertEquals(1, stub.posts, "the atom retried a limit it could not compute");
        }
    }

    @Test
    @DisplayName("the read-back catches an unreadable run, a non-object, and a different name/sha/status")
    void readBackFieldMismatches() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.readStatus = 500;
            AtomResult unreadable = run(stub, of());
            assertEquals("40009", unreadable.field("errorCode"), unreadable.toString());
            assertTrue(unreadable.field("message").contains("reading the check run back answered 500"),
                    unreadable.field("message"));

            stub.reset();
            stub.readBody = "<html>not a check run</html>";
            assertEquals("40007", run(stub, of()).field("errorCode"), "a non-JSON read-back");

            stub.reset();
            stub.readMutation = "name";
            AtomResult named = run(stub, of());
            assertEquals("40007", named.field("errorCode"), named.toString());
            assertTrue(named.field("message").contains("name is 'other'"), named.field("message"));

            stub.reset();
            stub.readMutation = "sha";
            assertTrue(run(stub, of()).field("message").contains("head_sha is"),
                    "the read-back must compare the commit");

            stub.reset();
            stub.readMutation = "status";
            assertTrue(run(stub, of()).field("message").contains("status is"),
                    "the read-back must compare the status");

            stub.reset();
            stub.readMutation = "conclusion-on-unfinished";
            assertTrue(run(stub, of("status", "in_progress")).field("message")
                            .contains("not completed but carries conclusion"),
                    "a conclusion on an unfinished run must be caught");
        }
    }

    @Test
    @DisplayName("the read-back catches a missing output, and a different title, summary or text")
    void readBackOutputMismatches() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.readMutation = "no-output";
            assertTrue(run(stub, of()).field("message").contains("without an output object"));

            stub.reset();
            stub.readMutation = "title";
            assertTrue(run(stub, of()).field("message").contains("output.title is"));

            stub.reset();
            stub.readMutation = "summary";
            AtomResult summary = run(stub, of());
            assertEquals("40007", summary.field("errorCode"));
            assertTrue(summary.field("message").contains("output.summary differs"), summary.field("message"));

            stub.reset();
            stub.readMutation = "text";
            AtomResult text = run(stub, of("text", "the long story"));
            assertEquals("40007", text.field("errorCode"), text.toString());
            assertTrue(text.field("message").contains("output.text differs"), text.field("message"));
        }
    }

    @Test
    @DisplayName("the annotations read back are compared field by field")
    void annotationMismatches() throws Exception {
        String three = "[{\"path\":\"a.java\",\"startLine\":2,\"endLine\":4,\"level\":\"warning\",\"message\":\"m1\"},"
                + "{\"path\":\"b.java\",\"startLine\":5,\"level\":\"failure\",\"message\":\"m2\"},"
                + "{\"path\":\"c.java\",\"startLine\":9,\"startColumn\":2,\"endColumn\":7,\"level\":\"notice\","
                + "\"message\":\"m3\"}]";
        try (Stub stub = Stub.start()) {
            stub.annStatus = 500;
            AtomResult failed = run(stub, of("annotations", three));
            assertEquals("40009", failed.field("errorCode"), failed.toString());
            assertTrue(failed.field("message").contains("reading the annotations back failed"),
                    failed.field("message"));

            stub.reset();
            stub.annBody = "{\"not\":\"an array\"}";
            assertEquals("40009", run(stub, of("annotations", three)).field("errorCode"), "a non-array body");

            stub.reset();
            stub.annBody = "[]";
            AtomResult count = run(stub, of("annotations", three));
            assertEquals("40007", count.field("errorCode"), count.toString());
            assertTrue(count.field("message").contains("expected 3 annotations, the API lists 0"),
                    count.field("message"));

            String single = "[{\"path\":\"a.java\",\"startLine\":2,\"endLine\":2,\"startColumn\":2,"
                    + "\"endColumn\":5,\"level\":\"warning\",\"message\":\"m1\"}]";
            record Case(String body, String message) {}
            List<Case> mismatches = List.of(
                    new Case("[{\"path\":\"z.java\",\"start_line\":2,\"end_line\":2,\"start_column\":2,"
                                    + "\"end_column\":5,\"annotation_level\":\"warning\",\"message\":\"m1\"}]",
                            "path is 'z.java'"),
                    new Case("[{\"path\":\"a.java\",\"start_line\":3,\"end_line\":3,\"start_column\":2,"
                                    + "\"end_column\":5,\"annotation_level\":\"warning\",\"message\":\"m1\"}]",
                            "lines are 3-3"),
                    new Case("[{\"path\":\"a.java\",\"start_line\":2,\"end_line\":2,\"start_column\":2,"
                                    + "\"end_column\":5,\"annotation_level\":\"notice\",\"message\":\"m1\"}]",
                            "level is 'notice'"),
                    new Case("[{\"path\":\"a.java\",\"start_line\":2,\"end_line\":2,\"start_column\":2,"
                                    + "\"end_column\":5,\"annotation_level\":\"warning\",\"message\":\"other\"}]",
                            "message is 'other'"),
                    new Case("[{\"path\":\"a.java\",\"start_line\":2,\"end_line\":2,\"start_column\":3,"
                                    + "\"end_column\":5,\"annotation_level\":\"warning\",\"message\":\"m1\"}]",
                            "columns are 3-5"));
            for (Case one : mismatches) {
                stub.reset();
                stub.annBody = one.body();
                AtomResult result = run(stub, of("annotations", single));
                assertEquals("40007", result.field("errorCode"), one.message() + ": " + result);
                assertTrue(result.field("message").contains(one.message()),
                        "expected '" + one.message() + "' in " + result.field("message"));
            }
        }
    }

    @Test
    @DisplayName("every malformed annotation shape is a 40005 with the annotation number, before any request")
    void refusesMalformedAnnotations() throws Exception {
        record Case(String json, String message) {}
        List<Case> cases = List.of(
                new Case("[{\"startLine\":1,\"message\":\"m\"}]", "path is required"),
                new Case("[{\"path\":\"a\",\"message\":\"m\"}]", "startLine is required"),
                new Case("[{\"path\":\"a\",\"startLine\":1,\"startColumn\":2,\"message\":\"m\"}]",
                        "startColumn and endColumn go together"),
                new Case("[{\"path\":\"a\",\"startLine\":1,\"startColumn\":0,\"endColumn\":2,\"message\":\"m\"}]",
                        "columns must be 1-based"),
                new Case("[{\"path\":\"a\",\"startLine\":1,\"message\":\"\"}]", "message is required"),
                new Case("[1,2]", "is not an object"),
                new Case("{\"a\":1}", "expected an array, or an object with an 'annotations' array"),
                new Case("\"text\"", "expected an array of annotations"));

        for (Case one : cases) {
            AtomResult result = run(null, of("annotations", one.json()));
            assertEquals("40005", result.field("errorCode"), one.message() + ": " + result.file("output.json"));
            assertTrue(result.field("message").contains(one.message()),
                    one.message() + ": message was " + result.field("message"));
        }
    }

    @Test
    @DisplayName("summaryFile/title defaults and a plain GIT_URL are all used the way the plugin used them")
    void readsFilesAndInfersTheRepository() throws Exception {
        try (Stub stub = Stub.start()) {
            AtomResult file = fresh()
                    .file("summary.md", "summary from a file")
                    .file("ann.json", "[{\"path\":\"a.java\",\"startLine\":1,\"message\":\"m\"}]")
                    .input(Json.write(of("apiUrl", stub.api(), "account", "acme", "repo", "parcel",
                            "sha", "abcdef1", "token", "t0ken", "status", "queued",
                            "summaryFile", "summary.md", "annotationsFile", "ann.json", "title", "")))
                    .run();
            file.assertOk();
            assertEquals("summary from a file", output(stub.stored).get("summary"));
            assertEquals("In progress", output(stub.stored).get("title"));
            assertEquals("1", file.field("annotations"));

            stub.reset();
            AtomResult https = run(stub, of("account", "", "repo", "", "GIT_URL",
                    "https://github.com/acme/inferred.git"));
            https.assertOk();
            assertEquals("acme/inferred", stub.repoPath(), "the https URL was not parsed");

            stub.reset();
            AtomResult ssh = run(stub, of("account", "", "repo", "", "GIT_URL",
                    "git@github.com:acme/ssh-repo.git"));
            ssh.assertOk();
            assertEquals("acme/ssh-repo", stub.repoPath(), "the scp-like URL was not parsed");

            stub.reset();
            AtomResult shortUrl = run(stub, of("account", "", "repo", "", "GIT_URL",
                    "https://github.com/acme"));
            assertEquals("40001", shortUrl.field("errorCode"), "a URL without a repository must be refused");
            assertTrue(shortUrl.field("message").contains("account and repo are required"),
                    shortUrl.field("message"));
        }
    }

    @Test
    @DisplayName("an escaped commit sha is decoded; one that is not a sha is left alone; a missing one is null")
    void decodesTheCommitBodyLikeJson() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.commitBody = "{\"sha\":\"abcdef1\\u00323\"}";
            AtomResult decoded = run(stub, of());
            decoded.assertOk();
            assertEquals("abcdef123", decoded.field("headSha"), "a \\u escape should be decoded");

            stub.reset();
            stub.commitBody = "{\"sha\":\"line\\n\\r\\t\\b\\f\\\"\\\\\\/x\"}";
            AtomResult escaped = run(stub, of());
            escaped.assertOk();
            assertEquals("abcdef1", escaped.field("headSha"),
                    "a sha that does not look like a sha must not replace the given one");

            stub.reset();
            stub.commitBody = "{\"other\":\"value\"}";
            assertEquals("abcdef1", run(stub, of()).field("headSha"), "no sha key means no resolution");

            stub.reset();
            stub.commitBody = "{\"sha\":12345}";
            assertEquals("abcdef1", run(stub, of()).field("headSha"), "a number is not a sha");
        }
    }

    @Test
    @DisplayName("a tiny maxMessageSize truncates instead of failing, and is reported as written")
    void truncatesAtASmallLimit() throws Exception {
        try (Stub stub = Stub.start()) {
            stub.createStatus = 0;
            String summary = "S".repeat(300);
            AtomResult result = run(stub, of("summary", summary, "maxMessageSize", "100"));

            result.assertOk();
            assertEquals(100, String.valueOf(output(stub.stored).get("summary")).length(),
                    "the summary should be cut at the requested limit");
            assertEquals("success", result.status());
        }
    }

    private static Map<String, Object> output(Map<String, Object> run) {
        return (Map<String, Object>) run.get("output");
    }

    /** A fake checks API that echoes what it was sent, unless a case says to break one thing. */
    private static final class Stub implements AutoCloseable {

        private final HttpServer server;
        private final List<String> requests = new ArrayList<>();
        int userStatus = 200;
        int repoStatus = 200;
        int commitStatus = 200;
        int runsStatus = 200;
        int createStatus;
        int patchStatus;
        int readStatus = 200;
        int annStatus = 200;
        String errorBody = "{\"message\":\"boom\"}";
        String commitBody = "{\"sha\":\"" + CANONICAL + "\"}";
        String runsBody = "{\"total_count\":0,\"check_runs\":[]}";
        String createBody;
        String readBody;
        String annBody;
        String readMutation = "";
        Map<String, Object> stored = new LinkedHashMap<>();
        List<Object> annotations = new ArrayList<>();
        int posts;

        private Stub(HttpServer server) {
            this.server = server;
        }

        static Stub start() throws IOException {
            Stub stub = new Stub(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            stub.server.createContext("/", stub::handle);
            stub.server.start();
            return stub;
        }

        String api() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            requests.clear();
            userStatus = repoStatus = commitStatus = runsStatus = readStatus = annStatus = 200;
            createStatus = patchStatus = 0;
            errorBody = "{\"message\":\"boom\"}";
            commitBody = "{\"sha\":\"" + CANONICAL + "\"}";
            runsBody = "{\"total_count\":0,\"check_runs\":[]}";
            createBody = null;
            readBody = null;
            annBody = null;
            readMutation = "";
            stored = new LinkedHashMap<>();
            annotations = new ArrayList<>();
            posts = 0;
        }

        void apply(String knob) {
            String[] parts = knob.split("=");
            switch (parts[0]) {
                case "userStatus" -> userStatus = Integer.parseInt(parts[1]);
                case "repoStatus" -> repoStatus = Integer.parseInt(parts[1]);
                case "commitStatus" -> commitStatus = Integer.parseInt(parts[1]);
                case "runsStatus" -> runsStatus = Integer.parseInt(parts[1]);
                default -> throw new IllegalArgumentException(knob);
            }
        }

        boolean sawWrite() {
            return requests.stream().anyMatch(r -> r.startsWith("POST") || r.startsWith("PATCH"));
        }

        String repoPath() {
            return requests.stream().filter(r -> r.startsWith("GET /repos/") && r.endsWith("/check-runs"))
                    .map(r -> r.substring("GET /repos/".length(), r.length() - "/check-runs".length()))
                    .map(rest -> rest.contains("/commits/") ? rest.substring(0, rest.indexOf("/commits/")) : rest)
                    .findFirst().orElse("");
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.replaceAll("^/+|/+$", "").split("/");
            synchronized (this) {
                requests.add(method + " " + path);
            }

            if (method.equals("GET") && parts.length == 1 && parts[0].equals("user")) {
                send(exchange, userStatus, userStatus / 100 == 2 ? "{\"login\":\"ci\"}" : errorBody);
                return;
            }
            if (method.equals("GET") && parts.length == 3 && parts[0].equals("repos")) {
                send(exchange, repoStatus, repoStatus / 100 == 2
                        ? "{\"full_name\":\"" + parts[1] + "/" + parts[2] + "\"}" : errorBody);
                return;
            }
            if (method.equals("GET") && parts.length == 5 && parts[3].equals("commits")) {
                send(exchange, commitStatus, commitStatus / 100 == 2 ? commitBody : errorBody);
                return;
            }
            if (method.equals("GET") && parts.length == 6 && parts[5].equals("check-runs")) {
                send(exchange, runsStatus, runsStatus / 100 == 2 ? runsBody : errorBody);
                return;
            }
            if (method.equals("POST") && parts.length == 4 && parts[3].equals("check-runs")) {
                posts++;
                synchronized (this) {
                    stored = Json.parseObject(body);
                    stored.put("id", 1);
                    Object output = stored.get("output");
                    if (output instanceof Map<?, ?> map && map.get("annotations") instanceof List<?> list) {
                        annotations = new ArrayList<>(list);
                    }
                }
                if (createStatus != 0) {
                    send(exchange, createStatus, errorBody);
                    return;
                }
                send(exchange, 201, createBody != null ? createBody : Json.write(stored));
                return;
            }
            if (method.equals("PATCH") && parts.length == 5 && parts[3].equals("check-runs")) {
                if (patchStatus != 0) {
                    send(exchange, patchStatus, errorBody);
                    return;
                }
                synchronized (this) {
                    merge(Json.parseObject(body));
                }
                send(exchange, 200, Json.write(stored));
                return;
            }
            if (method.equals("GET") && parts.length == 5 && parts[3].equals("check-runs")) {
                send(exchange, readStatus, readStatus / 100 == 2
                        ? (readBody != null ? readBody : mutated()) : errorBody);
                return;
            }
            if (method.equals("GET") && parts.length == 6 && parts[5].equals("annotations")) {
                if (annStatus / 100 != 2) {
                    send(exchange, annStatus, errorBody);
                    return;
                }
                send(exchange, annStatus, annBody != null ? annBody : Json.write(annotations));
                return;
            }
            send(exchange, 404, "{\"message\":\"not found\"}");
        }

        private synchronized void merge(Map<String, Object> patch) {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                if (!"output".equals(entry.getKey())) {
                    stored.put(entry.getKey(), entry.getValue());
                }
            }
            if (patch.get("output") instanceof Map<?, ?> incoming && stored.get("output") instanceof Map<?, ?> current) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) current);
                for (Map.Entry<?, ?> entry : incoming.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if ("annotations".equals(key) && entry.getValue() instanceof List<?> list) {
                        List<Object> all = new ArrayList<>((List<Object>) merged.getOrDefault("annotations",
                                new ArrayList<>()));
                        all.addAll(list);
                        merged.put("annotations", all);
                        annotations = all;
                    } else {
                        merged.put(key, entry.getValue());
                    }
                }
                stored.put("output", merged);
            }
        }

        private synchronized String mutated() {
            Map<String, Object> copy = new LinkedHashMap<>(stored);
            if ("no-output".equals(readMutation)) {
                copy.remove("output");
                return Json.write(copy);
            }
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) copy.get("output"));
            switch (readMutation) {
                case "name" -> copy.put("name", "other");
                case "sha" -> copy.put("head_sha", "0000000000000000000000000000000000000000");
                case "status" -> copy.put("status", "queued");
                case "conclusion-on-unfinished" -> copy.put("conclusion", "success");
                case "title" -> out.put("title", "other");
                case "summary" -> out.put("summary", "other");
                case "text" -> out.put("text", "other");
                default -> { }
            }
            copy.put("output", out);
            return Json.write(copy);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
