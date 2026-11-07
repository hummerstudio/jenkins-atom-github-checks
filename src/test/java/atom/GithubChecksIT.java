package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The github-checks atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: not "exit code 0" but the payload the API received, the shape the API demands
 * (50 annotations per request, appended), and the state the server holds afterwards — recomputed by
 * {@code tools/check.py} from the atom's input, not read back from the atom's own output. The same fake API
 * ({@code tools/fake-check-api.py}) serves each case here, with the same failure-injection flags.
 */
class GithubChecksIT {

    private Atom atom;
    private Path module;
    private Path fixtures;
    private final List<Process> servers = new ArrayList<>();
    private final List<Path> scratch = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        fixtures = module.resolve("tools/fixtures");
    }

    @AfterEach
    void tearDown() {
        for (Process server : servers) {
            if (server.isAlive()) {
                server.destroy();
            }
        }
        servers.clear();
        for (Path directory : scratch) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
                // a leaked temp directory is not a test result
            }
        }
        scratch.clear();
    }

    /** One fake checks API with its own request log and state, so ids start at 1 in every case. */
    private final class Fake {
        private final Process process;
        private final Path log;
        private final Path state;
        private final String api;

        private Fake(Process process, Path log, Path state, int port) {
            this.process = process;
            this.log = log;
            this.state = state;
            this.api = "http://127.0.0.1:" + port;
        }

        private int mark() throws IOException {
            return Files.readAllLines(log, StandardCharsets.UTF_8).size();
        }

        private List<String> since(int mark) throws IOException {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return lines.subList(Math.min(mark, lines.size()), lines.size());
        }

        private List<String> all() throws IOException {
            return Files.readAllLines(log, StandardCharsets.UTF_8);
        }
    }

    /** Starts {@code tools/fake-check-api.py} on a free port and waits until it answers. */
    private Fake start(String... flags) throws Exception {
        int port = freePort();
        Path directory = Files.createTempDirectory("checks-api");
        scratch.add(directory);
        Path log = directory.resolve("wire.tsv");
        Files.createFile(log);
        Path state = directory.resolve("state.json");
        List<String> command = new ArrayList<>(List.of("python3", "tools/fake-check-api.py",
                log.toString(), String.valueOf(port), state.toString()));
        command.addAll(List.of(flags));
        Process process = new ProcessBuilder(command).directory(module.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        servers.add(process);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest probe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/repos/probe/repo"))
                .header("Authorization", "Bearer probe")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> response = http.send(probe, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) {
                    return new Fake(process, log, state, port);
                }
            } catch (IOException | InterruptedException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("the fake checks API did not come up on " + port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String fixtures(String name) {
        return fixtures.resolve(name).toString();
    }

    /** {@code tools/check.py} recomputes what the server should hold from the input; it must agree. */
    private String check(AtomResult run, Fake fake, String... extra) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3", "tools/check.py",
                run.work().toString(), fake.state.toString(), fake.log.toString()));
        command.addAll(List.of(extra));
        Process process = new ProcessBuilder(command).directory(module.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), "check.py disagrees:\n" + output);
        return output;
    }

    /** The error code the atom refused with, or an empty string. */
    private static String code(AtomResult run) {
        Map<String, Object> document = Json.parseObject(run.file("output.json"));
        Object code = document.get("errorCode");
        return code == null ? "" : String.valueOf(code);
    }

    /** A fresh case: the token credential, plus a build environment the atom can expand. */
    private Atom build(String api) throws IOException {
        return Atom.of()
                .file(".secrets.json", "{\"token\": \"good-token\"}")
                .input("BUILD_NUMBER", "99")
                .input("JOB_NAME", "parcel")
                .input("BUILD_URL", "http://jenkins/job/parcel/99/")
                .input("BUILD_TAG", "jenkins-parcel-99")
                .input("BUILD_STATUS", "UNSTABLE")
                .input("apiUrl", api);
    }

    @Test
    @DisplayName("the happy path: neutral conclusion, resolved sha, 3 annotations with columns, read back")
    void publishesACheckRun() throws Exception {
        Fake fake = start();
        AtomResult run = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/ci\", \"annotationsFile\": \"" + fixtures("annotations.json") + "\","
                        + " \"text\": \"see ${BUILD_URL}console\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status(), run.console());
        check(run, fake);
        assertEquals("1", run.field("id"));
        assertEquals("neutral", run.field("conclusion"), "UNSTABLE must map to neutral");
        assertEquals("abcdef1234567890abcdef1234567890abcdef12", run.field("headSha"),
                "the short sha must be resolved");
        assertEquals("3", run.field("annotationsVerified"), "annotations were not read back");

        List<String> log = fake.all();
        String created = log.stream().filter(line -> line.startsWith("POST\t/repos/acme/parcel/check-runs"))
                .findFirst().orElseThrow(() -> new AssertionError("the create did not go to /check-runs: " + log));
        assertTrue(created.contains("\"conclusion\":\"neutral\""), "the payload did not carry the mapped conclusion");
        assertTrue(created.contains("\"start_column\":5"), "the columns were dropped from the payload");
        assertTrue(created.contains("\"head_sha\":\"abcdef1234567890abcdef1234567890abcdef12\""),
                "the resolved sha was not used");
        assertFalse(run.console().contains("good-token"), "the token must not be in the atom's log:\n" + run.console());
        for (String line : log) {
            String[] columns = line.split("\t", -1);
            if (columns.length > 1 && columns[1].startsWith("/repos/acme") && !"2022-11-28".equals(columns[3])) {
                throw new AssertionError("the API version header is not pinned: " + line);
            }
        }
    }

    @Test
    @DisplayName("SUCCESS/FAILURE/ABORTED/NOT_BUILT -> success/failure/failure/skipped, verified against the server")
    void mapsTheBuildStatus() throws Exception {
        Fake fake = start();
        Map<String, String> expected = Map.of("SUCCESS", "success", "FAILURE", "failure",
                "ABORTED", "failure", "NOT_BUILT", "skipped");
        for (Map.Entry<String, String> pair : expected.entrySet()) {
            AtomResult run = build(fake.api)
                    .input("BUILD_STATUS", pair.getKey())
                    .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                            + " \"name\": \"parcel/" + pair.getKey() + "\", \"summary\": \"from ${JOB_NAME}\"}")
                    .run();

            run.assertOk();
            assertEquals(pair.getValue(), run.field("conclusion"),
                    pair.getKey() + " must map to " + pair.getValue() + "\n" + run.console());
            check(run, fake);
        }
    }

    @Test
    @DisplayName("5 malformed annotation files and an unfinished conclusion are USER errors before any request")
    void refusesLocally() throws Exception {
        Fake fake = start();
        int before = fake.mark();

        AtomResult unfinished = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"status\": \"in_progress\", \"conclusion\": \"success\"}")
                .run();
        assertEquals("40001", code(unfinished), "a conclusion on an unfinished check must be refused");

        Map<String, String> malformed = Map.of(
                "annotations-bad-line.json", "endLine (2) is before startLine (5)",
                "annotations-absolute.json", "relative to the repository root",
                "annotations-level.json", "level must be notice, warning or failure",
                "annotations-columns.json", "columns are only accepted on a single line",
                "annotations-broken.json", "not JSON");
        for (Map.Entry<String, String> entry : malformed.entrySet()) {
            AtomResult run = build(fake.api)
                    .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                            + " \"annotationsFile\": \"" + fixtures(entry.getKey()) + "\"}")
                    .run();
            assertEquals("40005", code(run), entry.getKey() + " must be a 40005:\n" + run.file("output.json"));
            assertTrue(run.console().contains(entry.getValue()),
                    entry.getKey() + ": the message does not say '" + entry.getValue() + "':\n" + run.console());
        }

        AtomResult missing = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"annotationsFile\": \"" + fixtures("nope.json") + "\"}")
                .run();
        assertEquals("40005", code(missing), "a missing annotations file must be a 40005");

        assertEquals(before, fake.mark(), "a locally refused run still talked to the API");
    }

    @Test
    @DisplayName("credential/repository/commit are checked first; nothing is written when one is wrong")
    void preflight() throws Exception {
        Fake fake = start();
        int before = fake.mark();

        AtomResult badToken = build(fake.api)
                .file(".secrets.json", "{\"token\": \"wrong-token\"}")
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\"}")
                .run();
        assertEquals("40002", code(badToken), "a bad token must be 40002");

        AtomResult badRepo = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"nope\", \"sha\": \"abcdef1\"}")
                .run();
        assertEquals("40003", code(badRepo), "a missing repository must be 40003");

        AtomResult badCommit = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"deadbeef\"}")
                .run();
        assertEquals("40004", code(badCommit), "a missing commit must be 40004");

        for (String line : fake.since(before)) {
            assertFalse(line.startsWith("POST"), "a failed preflight still wrote a check run: " + line);
            assertFalse(line.startsWith("PATCH"), "a failed preflight still patched a check run: " + line);
        }
    }

    @Test
    @DisplayName("120 annotations go out as 50 + 50 + 20 and come back as 120 in order")
    void appendsMoreThanFifty() throws Exception {
        Fake fake = start();
        int before = fake.mark();
        Path annotations = Files.createTempFile("annotations", ".json");
        scratch.add(annotations);
        Process generator = new ProcessBuilder("python3", "tools/gen-many-annotations.py",
                annotations.toString(), "120")
                .directory(module.toFile()).redirectErrorStream(true).start();
        assertEquals(0, generator.waitFor(), "gen-many-annotations.py failed");

        AtomResult run = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/many\", \"annotationsFile\": \"" + annotations + "\"}")
                .run();

        run.assertOk();
        String checked = check(run, fake, "--since", String.valueOf(before));
        assertEquals("120", run.field("annotationsVerified"), "not 120 annotations on the server");
        assertTrue(checked.contains("\"batches\": [50, 50, 20]"),
                "the batches are not 50/50/20:\n" + checked);
    }

    @Test
    @DisplayName("the same external_id updates the check, a different build gets its own")
    void reusesTheSameCheck() throws Exception {
        Fake fake = start();
        AtomResult first = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/reuse\", \"externalId\": \"parcel-99\"}")
                .run();
        first.assertOk();
        String firstId = first.field("id");

        AtomResult second = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/reuse\", \"externalId\": \"parcel-99\","
                        + " \"status\": \"completed\", \"conclusion\": \"success\"}")
                .run();
        second.assertOk();
        assertEquals(firstId, second.field("id"), "the same build's check was duplicated instead of updated");
        check(second, fake);
        assertTrue(fake.all().stream().anyMatch(line -> line.startsWith("PATCH\t/repos/acme/parcel/check-runs/"
                        + firstId)), "re-running did not PATCH the existing check:\n" + fake.all());

        AtomResult other = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/reuse\", \"externalId\": \"parcel-100\"}")
                .run();
        other.assertOk();
        assertNotEquals(firstId, other.field("id"), "another build must not overwrite the first build's check");
    }

    @Test
    @DisplayName("a lying conclusion and a dropped annotation are both caught by the read-back (40007)")
    void trustsTheServerNotTheStatusCode() throws Exception {
        Fake liar = start("--lie-conclusion");
        AtomResult lied = build(liar.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/lie\", \"conclusion\": \"success\"}")
                .run();
        assertEquals("40007", code(lied), "a server that stores another conclusion must be caught");
        assertTrue(lied.console().contains("conclusion"),
                "the mismatch message does not say which field:\n" + lied.console());

        Fake dropper = start("--drop-annotations");
        AtomResult dropped = build(dropper.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/drop\", \"annotationsFile\": \"" + fixtures("annotations.json") + "\"}")
                .run();
        assertEquals("40007", code(dropped), "a server that drops annotations must be caught");
        assertTrue(dropped.console().contains("annotations"),
                "the mismatch message does not mention annotations:\n" + dropped.console());
    }

    @Test
    @DisplayName("an oversized text is retried at the reduced limit, and the stored text is truncated")
    void retriesAtTheSizeLimit() throws Exception {
        Fake fake = start("--limit-size", "400");
        StringBuilder text = new StringBuilder();
        text.append("line of a long report\n".repeat(60));
        AtomResult run = build(fake.api)
                .file("text.md", text.toString())
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/big\", \"textFile\": \"text.md\"}")
                .run();

        run.assertOk();
        check(run, fake, "--limit", "400");
        long posted = fake.all().stream().filter(line -> line.startsWith("POST")).count();
        assertTrue(posted >= 2, "the atom did not retry after the 422 (" + posted + " POSTs)");
        assertTrue(run.console().contains("retrying with max message size"), "the retry was silent:\n" + run.console());

        Process truncated = new ProcessBuilder("python3", "tools/assert-truncated.py",
                fake.state.toString(), "400")
                .directory(module.toFile()).redirectErrorStream(true).start();
        String output = new String(truncated.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, truncated.waitFor(), "the stored text was not truncated at the retried limit:\n" + output);
    }

    @Test
    @DisplayName("verify=false skips the read-back, GIT_URL is understood, a missing token is a USER error")
    void verifyAndInference() throws Exception {
        Fake fake = start();
        int before = fake.mark();
        AtomResult noVerify = build(fake.api)
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\","
                        + " \"name\": \"parcel/noverify\", \"verify\": \"false\"}")
                .run();
        noVerify.assertOk();
        assertEquals("not-checked", noVerify.field("verified"), "verify=false must say so");
        assertFalse(fake.since(before).stream().anyMatch(line -> line.startsWith("GET\t/repos/acme/parcel/check-runs/")),
                "verify=false still read the run back:\n" + fake.since(before));

        AtomResult inferred = build(fake.api)
                .input("{\"sha\": \"abcdef1\", \"name\": \"parcel/inferred\","
                        + " \"GIT_URL\": \"git@github.com:acme/inferred.git\"}")
                .run();
        inferred.assertOk();
        assertTrue(fake.all().stream().anyMatch(line -> line.startsWith("POST\t/repos/acme/inferred/check-runs")),
                "GIT_URL was not used to find the repository:\n" + fake.all());

        AtomResult noToken = build(fake.api)
                .file(".secrets.json", "{}")
                .input("{\"account\": \"acme\", \"repo\": \"parcel\", \"sha\": \"abcdef1\"}")
                .run();
        assertEquals("40002", code(noToken), "a missing token must be 40002");
    }
}
