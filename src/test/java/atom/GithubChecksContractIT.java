package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of github-checks that a happy-path fake never reaches: where the repository and commit come from, what the
 * atom refuses locally, and what it reports when the API fails one of its six calls.
 *
 * <p>The API here is a {@link HttpServer} that records what it was sent and can fail any single endpoint on demand;
 * {@code GithubChecksIT} keeps the payload and read-back contract against {@code tools/fake-check-api.py}.
 */
class GithubChecksContractIT {

    private static final String FULL_SHA = "abcdef1234567890abcdef1234567890abcdef12";

    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        servers.clear();
    }

    @AfterEach
    void tearDown() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
    }

    private record Resp(int status, String body) {}

    /** The checks API, with one knob per endpoint: status, body, and what the POST carried. */
    private static final class Stub {
        private final HttpServer server;
        private final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        private volatile String posted = "";
        private volatile int userStatus = 200;
        private volatile int repoStatus = 200;
        private volatile int commitStatus = 200;
        private volatile int listStatus = 200;
        private volatile int writeStatus = 201;
        private volatile int readStatus = 200;
        private volatile int appendStatus = 200;
        private volatile int annotationsStatus = 200;
        private volatile String commitBody = "{\"sha\":\"" + FULL_SHA + "\"}";
        private volatile String listBody = "{\"check_runs\":[]}";
        private volatile String writeBody = "{\"id\":1}";
        private volatile String readBody = null;
        private volatile String annotationsBody = "[]";

        private Stub(HttpServer server) {
            this.server = server;
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private long posts() {
            synchronized (seen) {
                return seen.stream().filter(line -> line.startsWith("POST ")).count();
            }
        }
    }

    private Stub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Stub stub = new Stub(server);
        server.createContext("/", exchange -> answer(exchange, stub));
        server.start();
        servers.add(server);
        return stub;
    }

    private static void answer(HttpExchange exchange, Stub stub) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
        stub.seen.add(exchange.getRequestMethod() + " " + path + (query.isEmpty() ? "" : "?" + query));
        Resp resp = route(exchange.getRequestMethod(), path, body, stub);
        byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(resp.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static Resp route(String method, String path, String body, Stub stub) {
        if (path.equals("/user")) {
            return new Resp(stub.userStatus, "{\"login\":\"ci\"}");
        }
        if (path.endsWith("/annotations")) {
            return new Resp(stub.annotationsStatus, stub.annotationsBody);
        }
        boolean singleRun = path.matches(".*/check-runs/\\d+");
        if (singleRun) {
            if (method.equals("GET")) {
                String echo = stub.readBody != null ? stub.readBody : echoOf(stub.posted);
                return new Resp(stub.readStatus, echo);
            }
            if (stub.appendStatus != 200) {
                return new Resp(stub.appendStatus, "{\"message\":\"append refused\"}");
            }
            stub.posted = body;
            return new Resp(200, "{\"id\":1}");
        }
        if (path.endsWith("/check-runs")) {
            if (method.equals("GET")) {
                return new Resp(stub.listStatus, stub.listBody);
            }
            stub.posted = body;
            return new Resp(stub.writeStatus, stub.writeBody);
        }
        if (path.contains("/commits/")) {
            return new Resp(stub.commitStatus, stub.commitBody);
        }
        if (path.startsWith("/repos/")) {
            return new Resp(stub.repoStatus, "{\"full_name\":\"acme/parcel\"}");
        }
        return new Resp(404, "{}");
    }

    /** What a server that stored the request body unchanged would answer to the read-back. */
    private static String echoOf(String posted) {
        if (posted == null || posted.isBlank()) {
            return "{\"id\":1}";
        }
        String inner = posted.strip();
        inner = inner.substring(1, inner.endsWith("}") ? inner.length() - 1 : inner.length());
        return "{\"id\":1," + inner + "}";
    }

    private static int deadPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Atom build(String api) throws IOException {
        return Atom.of()
                .file(".secrets.json", "{\"token\": \"good-token\"}")
                .input("apiUrl", api)
                .input("account", "acme")
                .input("repo", "parcel")
                .input("sha", "abcdef1")
                .input("name", "parcel/ci")
                .input("verify", "false");
    }

    /** Just enough of the build environment for the atom to expand: no BUILD_TAG, so no external_id. */
    private Atom build(String api, boolean environment) throws IOException {
        Atom atom = build(api);
        if (environment) {
            atom = atom.input("BUILD_NUMBER", "99")
                    .input("BUILD_STATUS", "UNSTABLE")
                    .input("BUILD_URL", "http://jenkins/job/parcel/99/");
        }
        return atom;
    }

    // ---------------------------------------------------------------- where the repository and sha come from

    @Test
    @DisplayName("account/repo, the Git URL and GIT_COMMIT are all understood, and a foreign account is refused")
    void findsTheRepositoryAndCommit() throws Exception {
        Stub stub = start();
        AtomResult split = build(stub.url())
                .input("repo", "acme/parcel")
                .input("name", "")
                .input("JOB_NAME", "parcel/from-jenkins")
                .input("GIT_COMMIT", "abcdef1")
                .run();
        split.assertOk();
        assertTrue(stub.seen.stream().anyMatch(line -> line.startsWith("POST /repos/acme/parcel/check-runs")),
                "account/name was not split into the request path: " + stub.seen);
        assertTrue(stub.posted.contains("\"head_sha\":\"" + FULL_SHA + "\""),
                "GIT_COMMIT was not used and resolved: " + stub.posted);
        assertTrue(stub.posted.contains("\"name\":\"parcel/from-jenkins\""),
                "JOB_NAME was not used as the check name: " + stub.posted);

        AtomResult foreign = build(stub.url()).input("repo", "someoneelse/parcel").run();
        foreign.assertRefused();
        assertEquals("40001", foreign.field("errorCode"), foreign.toString());
        assertTrue(foreign.field("message").contains("is not under account"), foreign.toString());

        AtomResult https = build(stub.url())
                .input("account", "")
                .input("repo", "")
                .input("GIT_URL", "https://github.com/acme/inferred.git")
                .run();
        https.assertOk();
        assertTrue(stub.seen.stream().anyMatch(line -> line.startsWith("POST /repos/acme/inferred/check-runs")),
                "the https GIT_URL did not name the repository: " + stub.seen);

        AtomResult useless = build(stub.url()).input("account", "").input("repo", "").input("GIT_URL", "acme").run();
        useless.assertRefused();
        assertEquals("40001", useless.field("errorCode"), useless.toString());
        assertTrue(useless.field("message").contains("account and repo are required"), useless.toString());
    }

    @Test
    @DisplayName("without a name the check is called jenkins/atom, and a commit is mandatory")
    void usesTheApiDefaults() throws Exception {
        Stub stub = start();
        AtomResult noName = build(stub.url(), true).input("name", "").input("account", "acme").run();

        noName.assertOk();
        assertTrue(stub.posted.contains("\"name\":\"jenkins/atom\""),
                "the default check name is not jenkins/atom: " + stub.posted);
        assertEquals("http://jenkins/job/parcel/99/", noName.field("checkUrl"),
                "BUILD_URL is the details url: " + noName.toString());
        assertTrue(stub.posted.contains("\"conclusion\":\"neutral\""),
                "UNSTABLE did not map to neutral: " + stub.posted);
        assertTrue(stub.posted.contains("\"summary\":\"Build #99 finished as UNSTABLE.\""),
                "the default summary is not the build's: " + stub.posted);
        assertTrue(stub.posted.contains("\"title\":\"Unstable\""), stub.posted);

        AtomResult noSha = build(stub.url()).input("sha", "").run();
        noSha.assertRefused();
        assertEquals("40001", noSha.field("errorCode"), noSha.toString());
        assertTrue(noSha.field("message").contains("sha (or GIT_COMMIT) is required"), noSha.toString());
    }

    @Test
    @DisplayName("status and conclusion are held to the API vocabulary, and canceled becomes cancelled")
    void normalisesTheConclusion() throws Exception {
        Stub stub = start();
        AtomResult badStatus = build(stub.url()).input("status", "running").run();
        badStatus.assertRefused();
        assertEquals("40001", badStatus.field("errorCode"), badStatus.toString());
        assertTrue(badStatus.field("message").contains("queued, in_progress or completed"), badStatus.toString());

        AtomResult badConclusion = build(stub.url()).input("conclusion", "weird").run();
        badConclusion.assertRefused();
        assertTrue(badConclusion.field("message").contains("unknown conclusion"), badConclusion.toString());

        AtomResult american = build(stub.url()).input("conclusion", "canceled").run();
        american.assertOk();
        assertTrue(stub.posted.contains("\"conclusion\":\"cancelled\""),
                "canceled was not normalised to cancelled: " + stub.posted);
        assertEquals("cancelled", american.field("conclusion"));
    }

    @Test
    @DisplayName("a message limit below the API minimum is refused before anything else")
    void refusesAMessageLimitBelowTheMinimum() throws Exception {
        Stub stub = start();
        AtomResult run = build(stub.url()).input("maxMessageSize", "50").run();

        run.assertRefused();
        assertEquals("40001", run.field("errorCode"), run.toString());
        assertTrue(run.field("message").contains("at least 100"), run.toString());
        assertTrue(stub.seen.isEmpty(), "a local refusal still called the API: " + stub.seen);
    }

    // ---------------------------------------------------------------- annotations, validated locally

    @Test
    @DisplayName("every annotation rule is checked locally, with the annotation's number and path in the message")
    void validatesAnnotations() throws Exception {
        Stub stub = start();
        String[][] cases = {
                {"[{\"path\":\"/abs/Main.java\",\"startLine\":1,\"message\":\"m\"}]", "relative to the repository root"},
                {"[{\"startLine\":1,\"message\":\"m\"}]", "path is required"},
                {"[{\"path\":\"a.java\",\"message\":\"m\"}]", "startLine is required"},
                {"[{\"path\":\"a.java\",\"startLine\":1,\"startColumn\":2,\"message\":\"m\"}]",
                        "startColumn and endColumn go together"},
                {"[{\"path\":\"a.java\",\"startLine\":1,\"startColumn\":0,\"endColumn\":1,\"message\":\"m\"}]",
                        "columns must be 1-based"},
                {"[{\"path\":\"a.java\",\"startLine\":1,\"message\":\"\"}]", "message is required"},
                {"[\"not an annotation\"]", "is not an object"},
                {"{\"items\":[]}", "expected an array"},
                {"\"just a string\"", "expected an array of annotations"},
        };
        for (String[] test : cases) {
            AtomResult run = build(stub.url()).input("annotations", test[0]).run();
            run.assertRefused();
            assertEquals("40005", run.field("errorCode"), test[0] + " must be a 40005:\n" + run.toString());
            assertTrue(run.field("message").contains(test[1]),
                    test[0] + ": the message does not say '" + test[1] + "':\n" + run.toString());
        }
        assertTrue(stub.seen.isEmpty(), "a malformed annotation file still called the API: " + stub.seen);
    }

    @Test
    @DisplayName("an annotation may leave out endLine and level, and startLine may arrive as a string")
    void acceptsTolerantAnnotationShapes() throws Exception {
        Stub stub = start();
        AtomResult run = build(stub.url())
                .input("verify", "false")
                .input("annotations", "[{\"path\":\"src/a.java\",\"startLine\":\"7\",\"message\":\"m\","
                        + "\"title\":\"t\",\"raw_details\":\"d\"}]")
                .run();

        run.assertOk();
        assertEquals("1", run.field("annotations"), run.toString());
        assertTrue(stub.posted.contains("\"start_line\":7"), "a numeric string was not read as a line: " + stub.posted);
        assertTrue(stub.posted.contains("\"end_line\":7"), "endLine did not default to startLine: " + stub.posted);
        assertTrue(stub.posted.contains("\"annotation_level\":\"warning\""),
                "a missing level did not default to warning: " + stub.posted);
        assertTrue(stub.posted.contains("\"raw_details\":\"d\""), stub.posted);
    }

    @Test
    @DisplayName("annotations may also be wrapped in an object with an annotations array")
    void acceptsWrappedAnnotations() throws Exception {
        Stub stub = start();
        AtomResult run = build(stub.url())
                .input("annotations", "{\"annotations\":[{\"path\":\"a.java\",\"startLine\":1,\"message\":\"m\"}]}")
                .run();

        run.assertOk();
        assertEquals("1", run.field("annotations"), run.toString());
        assertTrue(stub.posted.contains("\"path\":\"a.java\""), stub.posted);
    }

    @Test
    @DisplayName("a summary or text file that cannot be read is a user error with the file name")
    void refusesAMissingTextFile() throws Exception {
        Stub stub = start();
        AtomResult run = build(stub.url()).input("textFile", "reports/missing.md").run();

        run.assertRefused();
        assertTrue(run.field("message").contains("could not be read"), run.toString());
        assertTrue(run.field("message").contains("reports/missing.md"), run.toString());
    }

    // ---------------------------------------------------------------- the API failing one call

    @Test
    @DisplayName("each preflight call has its own refusal: 401 names the credential, 5xx names the call")
    void reportsAFailedPreflight() throws Exception {
        Stub userBroken = start();
        userBroken.userStatus = 500;
        AtomResult user = build(userBroken.url()).run();
        user.assertRefused();
        assertEquals("40010", user.field("errorCode"), user.toString());
        assertTrue(user.field("message").contains("GET /user"), user.toString());

        Stub repoUnauthorized = start();
        repoUnauthorized.repoStatus = 401;
        AtomResult repo401 = build(repoUnauthorized.url()).run();
        repo401.assertRefused();
        assertEquals("40002", repo401.field("errorCode"), repo401.toString());

        Stub repoBroken = start();
        repoBroken.repoStatus = 500;
        AtomResult repo500 = build(repoBroken.url()).run();
        repo500.assertRefused();
        assertEquals("40010", repo500.field("errorCode"), repo500.toString());
        assertTrue(repo500.field("message").contains("GET /repos/acme/parcel"), repo500.toString());

        Stub commitUnauthorized = start();
        commitUnauthorized.commitStatus = 401;
        AtomResult commit401 = build(commitUnauthorized.url()).run();
        commit401.assertRefused();
        assertEquals("40002", commit401.field("errorCode"), commit401.toString());

        Stub commitBroken = start();
        commitBroken.commitStatus = 500;
        AtomResult commit500 = build(commitBroken.url()).run();
        commit500.assertRefused();
        assertEquals("40010", commit500.field("errorCode"), commit500.toString());
        assertTrue(commit500.field("message").contains("GET the commit"), commit500.toString());
    }

    @Test
    @DisplayName("a failing listing, write, read-back or annotation page is a third-party failure naming the call")
    void reportsAFailedWritePath() throws Exception {
        Stub listUnauthorized = start();
        listUnauthorized.listStatus = 401;
        AtomResult list401 = build(listUnauthorized.url()).run();
        list401.assertRefused();
        assertEquals("40002", list401.field("errorCode"), list401.toString());

        Stub listBroken = start();
        listBroken.listStatus = 500;
        AtomResult list500 = build(listBroken.url()).run();
        list500.assertRefused();
        assertEquals("40010", list500.field("errorCode"), list500.toString());
        assertTrue(list500.field("message").contains("listing the check runs"), list500.toString());

        Stub noId = start();
        noId.writeBody = "{}";
        AtomResult withoutId = build(noId.url()).run();
        withoutId.assertRefused();
        assertEquals("40009", withoutId.field("errorCode"), withoutId.toString());
        assertTrue(withoutId.field("message").contains("without a check run object"), withoutId.toString());

        Stub writeBroken = start();
        writeBroken.writeStatus = 500;
        writeBroken.writeBody = "{\"message\":\"boom\"}";
        AtomResult write500 = build(writeBroken.url()).run();
        write500.assertRefused();
        assertEquals("40006", write500.field("errorCode"), write500.toString());
        assertTrue(write500.field("message").contains("POST"), write500.toString());

        Stub readBroken = start();
        readBroken.readStatus = 500;
        AtomResult read500 = build(readBroken.url()).input("verify", "true").run();
        read500.assertRefused();
        assertEquals("40009", read500.field("errorCode"), read500.toString());
        assertTrue(read500.field("message").contains("reading the check run back"), read500.toString());

        Stub annotationsBroken = start();
        annotationsBroken.annotationsStatus = 500;
        AtomResult annotations500 = build(annotationsBroken.url()).input("verify", "true").run();
        annotations500.assertRefused();
        assertEquals("40009", annotations500.field("errorCode"), annotations500.toString());
        assertTrue(annotations500.field("message").contains("reading the annotations back"), annotations500.toString());
    }

    @Test
    @DisplayName("an appended batch that is refused names the range that failed")
    void reportsAFailedAppend() throws Exception {
        StringBuilder annotations = new StringBuilder("[");
        for (int i = 1; i <= 60; i++) {
            if (i > 1) {
                annotations.append(",");
            }
            annotations.append("{\"path\":\"a.java\",\"startLine\":").append(i).append(",\"message\":\"m\"}");
        }
        annotations.append("]");

        Stub stub = start();
        stub.appendStatus = 500;
        AtomResult run = build(stub.url())
                .input("verify", "false")
                .input("annotations", annotations.toString())
                .run();

        run.assertRefused();
        assertEquals("40006", run.field("errorCode"), run.toString());
        assertTrue(run.field("message").contains("appending annotations 50..60"), run.toString());
    }

    @Test
    @DisplayName("an annotation that comes back changed is caught field by field")
    void reportsAnAnnotationThatChanged() throws Exception {
        String sent = "[{\"path\":\"a.java\",\"startLine\":2,\"startColumn\":3,\"endColumn\":4,\"message\":\"m\"}]";
        String[][] wrong = {
                {"[{\"path\":\"b.java\",\"start_line\":2,\"end_line\":2,\"start_column\":3,\"end_column\":4,"
                        + "\"annotation_level\":\"warning\",\"message\":\"m\"}]", "path is"},
                {"[{\"path\":\"a.java\",\"start_line\":9,\"end_line\":9,\"start_column\":3,\"end_column\":4,"
                        + "\"annotation_level\":\"warning\",\"message\":\"m\"}]", "lines are"},
                {"[{\"path\":\"a.java\",\"start_line\":2,\"end_line\":2,\"start_column\":3,\"end_column\":4,"
                        + "\"annotation_level\":\"failure\",\"message\":\"m\"}]", "level is"},
                {"[{\"path\":\"a.java\",\"start_line\":2,\"end_line\":2,\"start_column\":3,\"end_column\":4,"
                        + "\"annotation_level\":\"warning\",\"message\":\"other\"}]", "message is"},
                {"[{\"path\":\"a.java\",\"start_line\":2,\"end_line\":2,\"start_column\":1,\"end_column\":4,"
                        + "\"annotation_level\":\"warning\",\"message\":\"m\"}]", "columns are"},
        };
        for (String[] test : wrong) {
            Stub stub = start();
            stub.annotationsBody = test[0];
            AtomResult run = build(stub.url()).input("verify", "true").input("annotations", sent).run();
            run.assertRefused();
            assertEquals("40007", run.field("errorCode"), test[0] + " must be caught:\n" + run.toString());
            assertTrue(run.field("message").contains(test[1]),
                    test[0] + ": the mismatch does not say '" + test[1] + "':\n" + run.toString());
        }
    }

    @Test
    @DisplayName("a check run comes back as a different object, a different conclusion or no output at all")
    void reportsAStoredRunThatDiffers() throws Exception {
        Stub wrongName = start();
        wrongName.readBody = "{\"id\":1,\"name\":\"other\",\"head_sha\":\"" + FULL_SHA + "\",\"status\":\"completed\"}";
        AtomResult name = build(wrongName.url()).input("verify", "true").run();
        name.assertRefused();
        assertEquals("40007", name.field("errorCode"), name.toString());
        assertTrue(name.field("message").contains("name is"), name.toString());

        Stub wrongStatus = start();
        wrongStatus.readBody = "{\"id\":1,\"name\":\"parcel/ci\",\"head_sha\":\"" + FULL_SHA
                + "\",\"status\":\"queued\",\"conclusion\":\"success\"}";
        AtomResult status = build(wrongStatus.url()).input("verify", "true").run();
        status.assertRefused();
        assertEquals("40007", status.field("errorCode"), status.toString());
        assertTrue(status.field("message").contains("status is"), status.toString());

        Stub noOutput = start();
        noOutput.readBody = "{\"id\":1,\"name\":\"parcel/ci\",\"head_sha\":\"" + FULL_SHA
                + "\",\"status\":\"completed\",\"conclusion\":\"neutral\"}";
        AtomResult output = build(noOutput.url()).input("verify", "true").run();
        output.assertRefused();
        assertEquals("40007", output.field("errorCode"), output.toString());
        assertTrue(output.field("message").contains("without an output object"), output.toString());
    }

    @Test
    @DisplayName("a check run that is not a completed one must not come back with a conclusion")
    void reportsAConclusionOnAnUnfinishedRun() throws Exception {
        Stub stub = start();
        stub.readBody = "{\"id\":1,\"name\":\"parcel/ci\",\"head_sha\":\"" + FULL_SHA
                + "\",\"status\":\"in_progress\",\"conclusion\":\"success\",\"output\":{\"title\":\"In progress\","
                + "\"summary\":\"Build #99 is running.\"}}";
        AtomResult run = build(stub.url(), true)
                .input("verify", "true")
                .input("status", "in_progress")
                .run();

        run.assertRefused();
        assertEquals("40007", run.field("errorCode"), run.toString());
        assertTrue(run.field("message").contains("not completed but carries conclusion"), run.toString());
    }

    // ---------------------------------------------------------------- existing checks and odd answers

    @Test
    @DisplayName("an unfinished check run is reported without a conclusion, and re-used only by external id")
    void reportsAnUnfinishedRun() throws Exception {
        Stub stub = start();
        AtomResult run = build(stub.url(), true)
                .input("status", "in_progress")
                .input("conclusion", "")
                .input("verify", "false")
                .run();

        run.assertOk();
        assertEquals("none", run.field("conclusion"), run.toString());
        assertEquals("not-checked", run.field("verified"), "verify=false must say so: " + run.toString());

        Stub junk = start();
        junk.listBody = "<html>not json</html>";
        AtomResult withoutExternalId = build(junk.url()).input("externalId", "build-99").run();
        withoutExternalId.assertOk();
        assertEquals("1", withoutExternalId.field("id"),
                "a listing that is not JSON must not stop the check from being written: " + withoutExternalId);

        Stub noRuns = start();
        noRuns.listBody = "{\"items\":[]}";
        AtomResult noArray = build(noRuns.url()).input("externalId", "build-99").run();
        noArray.assertOk();
        assertEquals("1", noArray.field("id"), noArray.toString());
    }

    @Test
    @DisplayName("a commit answer whose sha cannot be read leaves the given sha in place")
    void keepsAShaItCannotRead() throws Exception {
        Stub nested = start();
        nested.commitBody = "{\"message\":\"no sha in this answer\"}";
        AtomResult missing = build(nested.url()).run();
        missing.assertOk();
        assertTrue(nested.posted.contains("\"head_sha\":\"abcdef1\""),
                "a commit without sha did not keep the given one: " + nested.posted);

        Stub numeric = start();
        numeric.commitBody = "{\"sha\":12345}";
        AtomResult number = build(numeric.url()).run();
        number.assertOk();
        assertTrue(numeric.posted.contains("\"head_sha\":\"abcdef1\""),
                "a numeric sha was used as one: " + numeric.posted);

        Stub escaped = start();
        escaped.commitBody = "{\"sha\":\"abcdef1\\u0032\"}";
        AtomResult escapedSha = build(escaped.url()).run();
        escapedSha.assertOk();
        assertEquals("abcdef12", escapedSha.field("headSha"),
                "the escaped sha was not decoded: " + escapedSha.toString());
    }

    @Test
    @DisplayName("an API that is not reachable refuses cleanly instead of failing with a stack trace")
    void reportsAnUnreachableApi() throws Exception {
        int dead = deadPort();
        AtomResult run = Atom.of()
                .file(".secrets.json", "{\"token\": \"good-token\"}")
                .input("apiUrl", "http://127.0.0.1:" + dead)
                .input("account", "acme")
                .input("repo", "parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("127.0.0.1:" + dead) || run.console().contains("Connect"),
                "the failure does not name the endpoint:\n" + run.toString() + "\n" + run.console());
        assertFalse(run.field("message").isEmpty(), "the atom refused without a message: " + run.toString());
    }
}
