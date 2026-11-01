package com.jenkinschina.atom.githubchecks;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publish a GitHub check run, with line-level annotations.
 *
 * <p>Port of {@code github-checks} (3,534 installs) plus the parts of {@code checks-api} it delegates to: the plugin
 * itself is 25 classes of glue that turns a {@code ChecksDetails} into {@code GHCheckRunBuilder} calls, and the payload
 * rules (status, conclusion, truncation, 50-annotations-per-request) live one level down. Both were read, the rules are
 * reproduced here, and the ones that cannot survive outside a Jenkins job (the multibranch context, the check id parked
 * in a build action) are replaced with what the API offers.
 *
 * <p>Three things this atom does that the plugin does not, all of them about not trusting a status code:
 * <ol>
 *   <li>after writing it reads the check run back ({@code GET /check-runs/{id}}) and its annotations back
 *       ({@code GET /check-runs/{id}/annotations}) and compares them with what it meant to write;</li>
 *   <li>an existing check run with the same name on the same commit is found through
 *       {@code GET /commits/{sha}/check-runs?check_name=} instead of a build action, so a re-run updates the check
 *       rather than growing a second one;</li>
 *   <li>annotations are validated locally (path relative to the repo, end line not before the start line, columns only
 *       on a single line, level in the API vocabulary) so a typo is a USER error with a line number, not a 422.</li>
 * </ol>
 */
public class GitHubChecksAtom implements AtomTask {

    /** The API takes at most 50 annotations per request; more than that has to be appended by updating the run. */
    private static final int ANNOTATIONS_PER_REQUEST = 50;

    /** {@code GitHubChecksDetails.MAX_MESSAGE_SIZE_TO_CHECKS_API}. */
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65535;

    /** The message {@code GHCheckRunBuilder} gets back when GitHub refuses an oversized summary/text. */
    private static final Pattern SIZE_EXCEEDED =
            Pattern.compile("Only (\\d+) characters are allowed; (\\d+) were supplied", Pattern.CASE_INSENSITIVE);

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String apiUrl = context.getParam("apiUrl", "https://api.github.com").trim().replaceAll("/+$", "");
        if (apiUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "apiUrl is required (https://api.github.com, or your GHE root)");
        }

        String account = context.getParam("account", "").trim();
        String repo = context.getParam("repo", "").trim();
        if (repo.isEmpty()) {
            repo = inferRepoFromGitUrl(context);
        }
        if (repo.contains("/")) {
            String[] parts = repo.split("/", 2);
            if (account.isEmpty()) {
                account = parts[0];
                repo = parts[1];
            } else if (account.equalsIgnoreCase(parts[0])) {
                repo = parts[1];
            } else {
                return AtomResult.error(ErrorType.USER, 40001, "repo '" + repo + "' is not under account '"
                        + account + "'; give the repository name alone, or fix the account");
            }
        }
        repo = repo.replaceAll("/+$", "");
        String fullRepo = account.isEmpty() ? repo : account + "/" + repo;
        if (repo.isBlank() || account.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "account and repo are required (or a 'owner/name' repo, or GIT_URL to infer them from)");
        }

        String sha = context.getParam("sha", "").trim();
        if (sha.isEmpty()) {
            sha = context.getParam("GIT_COMMIT", "").trim();
        }
        if (sha.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "sha (or GIT_COMMIT) is required: a check run hangs off a commit");
        }

        String token = orEmpty(context.getSecret("token")).trim();
        if (token.isEmpty()) {
            token = orEmpty(context.getParam("token")).trim();
        }
        if (token.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "bind the 'token' credential: the checks API refuses anonymous writes");
        }

        String name = expand(context, context.getParam("name", "")).trim();
        if (name.isEmpty()) {
            name = context.getParam("JOB_NAME", "").trim();
        }
        if (name.isEmpty()) {
            name = "jenkins/atom";
        }

        String status = context.getParam("status", "completed").trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!status.matches("queued|in_progress|completed")) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "status must be queued, in_progress or completed; '" + status + "' is none of them");
        }

        String buildStatus = context.getParam("BUILD_STATUS", "").trim();
        String conclusion = context.getParam("conclusion", "").trim().toLowerCase(Locale.ROOT);
        if (conclusion.isEmpty() && "completed".equals(status)) {
            conclusion = conclusionFor(buildStatus);
        }
        if (!"completed".equals(status) && !conclusion.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "the API only accepts a conclusion on a completed check run (status is '" + status + "')");
        }
        if (!conclusion.isEmpty() && !conclusion.matches(
                "success|failure|neutral|cancelled|canceled|skipped|stale|timed_out|action_required")) {
            return AtomResult.error(ErrorType.USER, 40001, "unknown conclusion '" + conclusion + "'");
        }
        if ("canceled".equals(conclusion)) {
            conclusion = "cancelled";
        }

        String title = expand(context, context.getParam("title", "")).trim();
        if (title.isEmpty()) {
            title = "completed".equals(status) ? titleFor(buildStatus) : "In progress";
        }

        String summary = textOf(context, "summary", "summaryFile");
        if (summary.isEmpty()) {
            summary = defaultSummary(context, status, buildStatus);
        }
        String text = textOf(context, "text", "textFile");

        String detailsUrl = expand(context, context.getParam("detailsUrl",
                context.getParam("BUILD_URL", ""))).trim();
        String externalId = expand(context, context.getParam("externalId",
                context.getParam("BUILD_TAG", ""))).trim();

        List<Ann> annotations;
        try {
            annotations = loadAnnotations(context);
        } catch (RuntimeException e) {
            return AtomResult.error(ErrorType.USER, 40005,
                    "the annotations file is not usable: " + e.getMessage());
        }

        int maxMessageSize = context.getIntParam("maxMessageSize", DEFAULT_MAX_MESSAGE_SIZE);
        if (maxMessageSize < 100) {
            return AtomResult.error(ErrorType.USER, 40001, "maxMessageSize must be at least 100");
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        String authorization = "Bearer " + token;

        // ---- the preflight: credential, repository, commit. A check run for a commit that is not there is a lie.
        String headSha = sha;
        if (context.getBooleanParam("preflight", true)) {
            Resp me = get(client, apiUrl + "/user", authorization);
            if (me.code == 401) {
                return AtomResult.error(ErrorType.USER, 40002, "the supplied credentials are invalid to login (GET /user answered 401)");
            }
            if (me.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010,
                        "GET /user answered " + me.code + ": " + abbreviate(me.body));
            }

            Resp repository = get(client, apiUrl + "/repos/" + fullRepo, authorization);
            if (repository.code == 401) {
                return AtomResult.error(ErrorType.USER, 40002, "the supplied credentials are invalid to login");
            }
            if (repository.code == 404) {
                return AtomResult.error(ErrorType.USER, 40003, "the specified repository does not exist: " + fullRepo);
            }
            if (repository.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010,
                        "GET /repos/" + fullRepo + " answered " + repository.code + ": " + abbreviate(repository.body));
            }

            Resp commit = get(client, apiUrl + "/repos/" + fullRepo + "/commits/" + enc(sha), authorization);
            if (commit.code == 401) {
                return AtomResult.error(ErrorType.USER, 40002, "the supplied credentials are invalid to login");
            }
            if (commit.code == 404 || commit.code == 422) {
                return AtomResult.error(ErrorType.USER, 40004,
                        "the specified commit does not exist in the specified repository: " + sha);
            }
            if (commit.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010,
                        "GET the commit " + sha + " answered " + commit.code + ": " + abbreviate(commit.body));
            }
            String canonical = firstString(commit.body, "sha");
            if (canonical != null && canonical.matches("[0-9a-fA-F]{7,64}")) {
                headSha = canonical;
                if (!canonical.equalsIgnoreCase(sha)) {
                    context.log("[github-checks] commit " + sha + " resolved to " + headSha);
                }
            }
        }

        // ---- an existing check with this name on this commit is updated, not duplicated (the plugin keeps the id in a
        // build action; a build action does not exist here, so ask the API).
        Long existingId = null;
        if (context.getBooleanParam("reuseExisting", true)) {
            Resp runs = get(client, apiUrl + "/repos/" + fullRepo + "/commits/" + enc(headSha)
                    + "/check-runs?check_name=" + enc(name) + "&filter=all&per_page=100", authorization);
            if (runs.code == 401) {
                return AtomResult.error(ErrorType.USER, 40002, "the supplied credentials are invalid to login");
            }
            if (runs.code / 100 == 2) {
                existingId = idOfNamedRun(runs.body, name, externalId);
            } else if (runs.code != 404) {
                return AtomResult.error(ErrorType.THIRD_PART, 40010,
                        "listing the check runs of " + headSha + " answered " + runs.code + ": " + abbreviate(runs.body));
            }
        }

        // ---- the write. First request carries the run and the first batch of annotations; the rest are appended.
        String writeUrl = existingId == null
                ? apiUrl + "/repos/" + fullRepo + "/check-runs"
                : apiUrl + "/repos/" + fullRepo + "/check-runs/" + existingId;
        boolean updating = existingId != null;

        Resp response = null;
        long checkId = existingId == null ? -1 : existingId;
        int limit = maxMessageSize;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> body = runBody(updating, name, headSha, status, conclusion, title,
                    summary, text, detailsUrl, externalId, annotations, limit);
            response = send(client, updating ? "PATCH" : "POST", writeUrl, authorization, Json.write(body));
            if (response.code / 100 == 2) {
                Long id = firstNumber(response.body, "id");
                if (id == null) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40009,
                            "the API answered " + response.code + " without a check run object: " + abbreviate(response.body));
                }
                checkId = id;
                break;
            }
            Integer reduced = reducedLimit(response.body);
            if (reduced != null && attempt == 0) {
                context.log("[github-checks] GitHub refused the size, retrying with max message size " + reduced
                        + ": " + abbreviate(response.body));
                limit = reduced;
                continue;
            }
            return AtomResult.error(ErrorType.THIRD_PART, 40006,
                    "the API answered " + response.code + " for " + (updating ? "PATCH " : "POST ") + writeUrl
                            + ": " + abbreviate(response.body));
        }

        // ---- annotations beyond the first batch: the API appends them, one update per 50.
        for (int from = ANNOTATIONS_PER_REQUEST; from < annotations.size(); from += ANNOTATIONS_PER_REQUEST) {
            List<Object> chunk = new ArrayList<>();
            for (Ann ann : annotations.subList(from, Math.min(from + ANNOTATIONS_PER_REQUEST, annotations.size()))) {
                chunk.add(ann.toMap());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("output", Map.of("annotations", chunk));
            Resp appended = send(client, "PATCH", apiUrl + "/repos/" + fullRepo + "/check-runs/" + checkId,
                    authorization, Json.write(body));
            if (appended.code / 100 != 2) {
                return AtomResult.error(ErrorType.THIRD_PART, 40006,
                        "appending annotations " + from + ".." + (from + chunk.size())
                                + " answered " + appended.code + ": " + abbreviate(appended.body));
            }
        }

        String summaryWritten = truncateIfNeeded(summary, limit);
        String textWritten = truncateIfNeeded(text, limit);

        AtomResult result = AtomResult.success()
                .data("status", String.valueOf(response.code))
                .data("id", String.valueOf(checkId))
                .data("name", name)
                .data("headSha", headSha)
                .data("conclusion", conclusion.isEmpty() ? "none" : conclusion)
                .data("annotations", String.valueOf(annotations.size()))
                .data("checkUrl", detailsUrl.isEmpty() ? "" : detailsUrl)
                .data("apiUrl", apiUrl + "/repos/" + fullRepo + "/check-runs/" + checkId);

        if (!context.getBooleanParam("verify", true)) {
            return result.data("verified", "not-checked");
        }

        // ---- read it back: the object, and the annotations. A 201 is not evidence that anything was stored.
        Resp stored = get(client, apiUrl + "/repos/" + fullRepo + "/check-runs/" + checkId, authorization);
        if (stored.code / 100 != 2) {
            return AtomResult.error(ErrorType.THIRD_PART, 40009,
                    "reading the check run back answered " + stored.code + ": " + abbreviate(stored.body));
        }
        String mismatch = compare(stored.body, name, headSha, status, conclusion, title, summaryWritten, textWritten);
        if (mismatch != null) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007, "the check run the API stored is not the one sent: " + mismatch);
        }

        List<Map<String, Object>> listed = listAnnotations(client, apiUrl + "/repos/" + fullRepo + "/check-runs/"
                + checkId + "/annotations", authorization);
        if (listed == null) {
            return AtomResult.error(ErrorType.THIRD_PART, 40009, "reading the annotations back failed");
        }
        String annotationMismatch = compareAnnotations(annotations, listed);
        if (annotationMismatch != null) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007,
                    "the annotations the API stored are not the ones sent: " + annotationMismatch);
        }

        context.log("[github-checks] check run " + checkId + " '" + name + "' " + status
                + (conclusion.isEmpty() ? "" : "/" + conclusion) + " verified, " + listed.size() + " annotations on the server");
        return result.data("verified", (conclusion.isEmpty() ? status : conclusion))
                .data("annotationsVerified", String.valueOf(listed.size()));
    }

    // ---------------------------------------------------------------- payload

    private static Map<String, Object> runBody(boolean updating, String name, String headSha, String status,
                                               String conclusion, String title, String summary, String text,
                                               String detailsUrl, String externalId, List<Ann> annotations,
                                               int maxMessageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (!updating) {
            body.put("head_sha", headSha);
        }
        body.put("status", status);
        if ("completed".equals(status)) {
            body.put("conclusion", conclusion);
            body.put("completed_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        } else if ("in_progress".equals(status)) {
            body.put("started_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        }
        if (!detailsUrl.isEmpty()) {
            body.put("details_url", detailsUrl);
        }
        if (!externalId.isEmpty()) {
            body.put("external_id", externalId);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", title);
        output.put("summary", truncateIfNeeded(summary, maxMessageSize));
        String writtenText = truncateIfNeeded(text, maxMessageSize);
        if (writtenText != null && !writtenText.isEmpty()) {
            output.put("text", writtenText);
        }
        if (!annotations.isEmpty()) {
            List<Object> first = new ArrayList<>();
            for (Ann ann : annotations.subList(0, Math.min(ANNOTATIONS_PER_REQUEST, annotations.size()))) {
                first.add(ann.toMap());
            }
            output.put("annotations", first);
        }
        body.put("output", output);
        return body;
    }

    /** {@code GitHubChecksDetails.truncateIfNeeded}: keep the head, say how long the whole thing was. */
    static String truncateIfNeeded(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int originalLength = text.length();
        String note = "\n\n... [Output truncated; original size was " + originalLength + " characters] ...";
        if (maxLength <= note.length()) {
            return text.substring(0, Math.max(0, maxLength));
        }
        int cut = maxLength - note.length();
        while (cut > 1 && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + note;
    }

    /** {@code GitHubChecksPublisher.calculateReducedLimit}: what is left after overshoot and a 100-char margin. */
    static Integer reducedLimit(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = SIZE_EXCEEDED.matcher(body.replace("\\n", " "));
        if (!matcher.find()) {
            return null;
        }
        try {
            int allowed = Integer.parseInt(matcher.group(1));
            int supplied = Integer.parseInt(matcher.group(2));
            int overshoot = Math.max(0, supplied - allowed);
            return Math.max(100, allowed - overshoot - 100);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- conclusions

    /** {@code BuildStatusChecksPublisher.getConclusion} with the mapping {@code GitHubChecksDetails} applies. */
    static String conclusionFor(String buildStatus) {
        String status = buildStatus == null ? "" : buildStatus.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS", "OK", "PASSED" -> "success";
            case "UNSTABLE" -> "neutral";
            case "FAILURE", "FAILED", "ERROR" -> "failure";
            case "NOT_BUILT", "NOTBUILT", "SKIPPED" -> "skipped";
            // aborted is `cancelled` in checks-api, but GitHub only lets its own Actions write that conclusion,
            // so the plugin publishes FAILURE and so does this atom (pass conclusion=cancelled to override).
            case "ABORTED" -> "failure";
            default -> "neutral";
        };
    }

    static String titleFor(String buildStatus) {
        String status = buildStatus == null ? "" : buildStatus.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS", "OK", "PASSED" -> "Success";
            case "UNSTABLE" -> "Unstable";
            case "FAILURE", "FAILED", "ERROR" -> "Failure";
            case "NOT_BUILT", "NOTBUILT", "SKIPPED" -> "Skipped";
            case "ABORTED" -> "Aborted";
            default -> "Completed";
        };
    }

    private static String defaultSummary(AtomContext context, String status, String buildStatus) {
        String number = context.getParam("BUILD_NUMBER", "").trim();
        String what = number.isEmpty() ? "This build" : "Build #" + number;
        return switch (status) {
            case "queued" -> what + " is queued.";
            case "in_progress" -> what + " is running.";
            default -> what + " finished as " + (buildStatus.isBlank() ? "UNKNOWN" : buildStatus.trim()) + ".";
        };
    }

    // ---------------------------------------------------------------- annotations

    private record Ann(String path, int startLine, int endLine, Integer startColumn, Integer endColumn,
                       String level, String title, String message, String rawDetails) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("start_line", startLine);
            map.put("end_line", endLine);
            if (startColumn != null) {
                map.put("start_column", startColumn);
                map.put("end_column", endColumn);
            }
            map.put("annotation_level", level);
            if (!title.isEmpty()) {
                map.put("title", title);
            }
            map.put("message", message);
            if (!rawDetails.isEmpty()) {
                map.put("raw_details", rawDetails);
            }
            return map;
        }
    }

    private static List<Ann> loadAnnotations(AtomContext context) {
        String inline = context.getParam("annotations", "").trim();
        String file = context.getParam("annotationsFile", "").trim();
        String json;
        if (!file.isEmpty()) {
            Path path = resolve(context, file);
            try {
                json = Files.readString(path, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalArgumentException("'" + file + "' could not be read: " + e.getMessage());
            }
        } else if (!inline.isEmpty()) {
            json = inline;
        } else {
            return List.of();
        }

        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage());
        }
        List<?> items;
        if (root instanceof List<?> list) {
            items = list;
        } else if (root instanceof Map<?, ?> map) {
            Object nested = map.get("annotations");
            if (!(nested instanceof List<?> list)) {
                throw new IllegalArgumentException("expected an array, or an object with an 'annotations' array");
            }
            items = list;
        } else {
            throw new IllegalArgumentException("expected an array of annotations");
        }

        List<Ann> annotations = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("annotation " + (i + 1) + " is not an object");
            }
            String path = text(item, "path", "fileName", "file");
            Integer startLine = number(item, "startLine", "start_line");
            Integer endLine = number(item, "endLine", "end_line");
            Integer startColumn = number(item, "startColumn", "start_column");
            Integer endColumn = number(item, "endColumn", "end_column");
            String level = text(item, "level", "annotationLevel", "annotation_level");
            String message = text(item, "message");
            String title = text(item, "title");
            String rawDetails = text(item, "rawDetails", "raw_details");
            String where = "annotation " + (i + 1) + (path.isEmpty() ? "" : " (" + path + ")") + ": ";

            if (path.isBlank()) {
                throw new IllegalArgumentException(where + "path is required");
            }
            if (path.startsWith("/") || path.contains("..")) {
                throw new IllegalArgumentException(where + "path must be relative to the repository root");
            }
            if (startLine == null || startLine < 1) {
                throw new IllegalArgumentException(where + "startLine is required and must be >= 1");
            }
            if (endLine == null) {
                endLine = startLine;
            }
            if (endLine < startLine) {
                throw new IllegalArgumentException(where + "endLine (" + endLine + ") is before startLine (" + startLine + ")");
            }
            if ((startColumn == null) != (endColumn == null)) {
                throw new IllegalArgumentException(where + "startColumn and endColumn go together");
            }
            if (startColumn != null) {
                if (startLine != endLine) {
                    throw new IllegalArgumentException(where + "columns are only accepted on a single line");
                }
                if (startColumn < 1 || endColumn < startColumn) {
                    throw new IllegalArgumentException(where + "columns must be 1-based and endColumn >= startColumn");
                }
            }
            if (level.isBlank()) {
                level = "warning";
            }
            level = level.toLowerCase(Locale.ROOT);
            if (!level.matches("notice|warning|failure")) {
                throw new IllegalArgumentException(where + "level must be notice, warning or failure; got '" + level + "'");
            }
            if (message.isBlank()) {
                throw new IllegalArgumentException(where + "message is required");
            }
            annotations.add(new Ann(path, startLine, endLine, startColumn, endColumn, level, title, message, rawDetails));
        }
        return annotations;
    }

    /** The annotations endpoint, paginated; {@code null} when a page could not be read. */
    private static List<Map<String, Object>> listAnnotations(HttpClient client, String url, String authorization)
            throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            Resp resp = get(client, url + "?per_page=100&page=" + page, authorization);
            if (resp.code / 100 != 2) {
                return null;
            }
            Object parsed = Json.parse(resp.body);
            if (!(parsed instanceof List<?> items)) {
                return null;
            }
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> annotation = new LinkedHashMap<>();
                    map.forEach((key, value) -> annotation.put(String.valueOf(key), value));
                    all.add(annotation);
                }
            }
            if (items.size() < 100) {
                break;
            }
        }
        return all;
    }

    static String compareAnnotations(List<Ann> expected, List<Map<String, Object>> listed) {
        if (listed.size() != expected.size()) {
            return "expected " + expected.size() + " annotations, the API lists " + listed.size();
        }
        for (int i = 0; i < expected.size(); i++) {
            Ann want = expected.get(i);
            Map<String, Object> got = listed.get(i);
            String where = "annotation " + (i + 1) + ": ";
            if (!String.valueOf(got.get("path")).equals(want.path())) {
                return where + "path is '" + got.get("path") + "', not '" + want.path() + "'";
            }
            if (asInt(got.get("start_line")) != want.startLine() || asInt(got.get("end_line")) != want.endLine()) {
                return where + "lines are " + got.get("start_line") + "-" + got.get("end_line")
                        + ", not " + want.startLine() + "-" + want.endLine();
            }
            if (!String.valueOf(got.get("annotation_level")).equalsIgnoreCase(want.level())) {
                return where + "level is '" + got.get("annotation_level") + "', not '" + want.level() + "'";
            }
            if (!String.valueOf(got.get("message")).equals(want.message())) {
                return where + "message is '" + abbreviate(String.valueOf(got.get("message"))) + "'";
            }
            if (want.startColumn() != null) {
                if (asInt(got.get("start_column")) != want.startColumn() || asInt(got.get("end_column")) != want.endColumn()) {
                    return where + "columns are " + got.get("start_column") + "-" + got.get("end_column")
                            + ", not " + want.startColumn() + "-" + want.endColumn();
                }
            }
        }
        return null;
    }

    /** The check run the API stored, field by field, against the one that was meant. */
    private static String compare(String body, String name, String headSha, String status, String conclusion,
                                  String title, String summary, String text) {
        Map<String, Object> stored;
        try {
            stored = Json.parseObject(body);
        } catch (RuntimeException e) {
            return "the read-back is not a check run object: " + abbreviate(body);
        }
        if (!name.equals(String.valueOf(stored.get("name")))) {
            return "name is '" + stored.get("name") + "', not '" + name + "'";
        }
        if (!headSha.equalsIgnoreCase(String.valueOf(stored.get("head_sha")))) {
            return "head_sha is '" + stored.get("head_sha") + "', not '" + headSha + "'";
        }
        if (!status.equals(String.valueOf(stored.get("status")))) {
            return "status is '" + stored.get("status") + "', not '" + status + "'";
        }
        String storedConclusion = stored.get("conclusion") == null ? "" : String.valueOf(stored.get("conclusion"));
        if (!"completed".equals(status)) {
            if (!storedConclusion.isEmpty()) {
                return "the check run is not completed but carries conclusion '" + storedConclusion + "'";
            }
        } else if (!conclusion.equals(storedConclusion)) {
            return "conclusion is '" + storedConclusion + "', not '" + conclusion + "'";
        }
        Object output = stored.get("output");
        if (!(output instanceof Map<?, ?> out)) {
            return "the check run came back without an output object";
        }
        if (!title.equals(String.valueOf(out.get("title")))) {
            return "output.title is '" + out.get("title") + "', not '" + title + "'";
        }
        if (!summary.equals(String.valueOf(out.get("summary")))) {
            return "output.summary differs from what was sent (" + String.valueOf(out.get("summary")).length()
                    + " vs " + summary.length() + " characters)";
        }
        if (text != null && !text.isEmpty() && !text.equals(String.valueOf(out.get("text")))) {
            return "output.text differs from what was sent";
        }
        return null;
    }

    // ---------------------------------------------------------------- plumbing

    private record Resp(int code, String body) {
    }

    private static Resp get(HttpClient client, String url, String authorization) throws Exception {
        return send(client, "GET", url, authorization, null);
    }

    private static Resp send(HttpClient client, String method, String url, String authorization, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authorization)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(20));
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static Path resolve(AtomContext context, String file) {
        Path path = Path.of(file);
        if (path.isAbsolute()) {
            return path;
        }
        Path workspace = context.getWorkspace();
        return workspace == null ? path : workspace.resolve(path);
    }

    private static String textOf(AtomContext context, String inlineParam, String fileParam) {
        String file = context.getParam(fileParam, "").trim();
        if (!file.isEmpty()) {
            try {
                return Files.readString(resolve(context, file), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalArgumentException("'" + file + "' could not be read: " + e.getMessage());
            }
        }
        return expand(context, context.getParam(inlineParam, ""));
    }

    /**
     * The id of the check run that belongs to <em>this build</em>: same name, same {@code external_id}.
     *
     * <p>The plugin finds its check id in a build action, which only exists inside a Jenkins build. Here the id is
     * recovered from the API, but by name alone a second build would adopt the first one's check run and append its
     * annotations to it; {@code external_id} is what keeps the two builds apart.
     */
    static Long idOfNamedRun(String json, String name, String externalId) {
        if (externalId.isEmpty()) {
            return null;
        }
        Object root;
        try {
            root = Json.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("check_runs") instanceof List<?> runs)) {
            return null;
        }
        Long best = null;
        for (Object item : runs) {
            if (!(item instanceof Map<?, ?> run)) {
                continue;
            }
            if (!name.equals(String.valueOf(run.get("name")))
                    || !externalId.equals(String.valueOf(run.get("external_id")))) {
                continue;
            }
            if (run.get("id") instanceof Number id && (best == null || id.longValue() > best)) {
                best = id.longValue();
            }
        }
        return best;
    }

    private static String text(Map<?, ?> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static Integer number(Map<?, ?> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value instanceof Number n) {
                return n.intValue();
            }
            if (value instanceof String s && !s.isBlank()) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    private static Long firstNumber(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+)").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static String firstString(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int value = valueStart(json, at + needle.length());
        return value < 0 ? null : decodeString(json, value);
    }

    private static int valueStart(String json, int from) {
        int p = from;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != ':') {
            return -1;
        }
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        return p < json.length() && json.charAt(p) == '"' ? p : -1;
    }

    private static String decodeString(String json, int quote) {
        StringBuilder value = new StringBuilder();
        int p = quote + 1;
        while (p < json.length()) {
            char c = json.charAt(p);
            if (c == '"') {
                break;
            }
            if (c == '\\' && p + 1 < json.length()) {
                char escape = json.charAt(p + 1);
                switch (escape) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (p + 5 < json.length()) {
                            value.append((char) Integer.parseInt(json.substring(p + 2, p + 6), 16));
                            p += 4;
                        }
                    }
                    default -> value.append(escape);
                }
                p += 2;
            } else {
                value.append(c);
                p++;
            }
        }
        return value.toString();
    }

    static String inferRepoFromGitUrl(AtomContext context) {
        String gitUrl = context.getParam("GIT_URL", "").trim();
        if (gitUrl.isEmpty()) {
            return "";
        }
        String path = gitUrl;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            path = path.substring(scheme + 3);
            int slash = path.indexOf('/');
            path = slash < 0 ? "" : path.substring(slash + 1);
        } else {
            int colon = path.indexOf(':');
            int at = path.indexOf('@');
            if (colon >= 0 && at >= 0 && at < colon) {
                path = path.substring(colon + 1);
            }
        }
        path = path.replaceAll("^/+", "").replaceAll("\\.git$", "");
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return "";
        }
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    static String expand(AtomContext context, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(context.getParam(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String abbreviate(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
