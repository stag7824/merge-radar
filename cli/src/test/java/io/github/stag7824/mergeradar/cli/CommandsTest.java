package io.github.stag7824.mergeradar.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Runs the real commands end to end against a fake GitHub API. */
class CommandsTest {

  @TempDir Path dir;

  private WireMockServer server;
  private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @BeforeEach
  void start() {
    server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private record Run(int exitCode, String out, String err) {}

  private Run run(String... args) {
    List<String> all = new ArrayList<>(List.of(args));
    all.addAll(List.of("--api-url", server.baseUrl(), "--token", "test-token"));
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine commandLine = MergeRadar.commandLine();
    commandLine.setOut(new PrintWriter(out));
    commandLine.setErr(new PrintWriter(err));
    int exitCode = commandLine.execute(all.toArray(String[]::new));
    return new Run(exitCode, out.toString(), err.toString());
  }

  private String ago(int days) {
    return now.minus(days, ChronoUnit.DAYS).toString();
  }

  private void stubRepo() {
    String pull =
        """
        {"number":10,"user":{"login":"outsider","type":"User"},"author_association":"NONE",
         "created_at":"%s","updated_at":"%s","closed_at":"%s","merged_at":"%s"}\
        """
            .formatted(ago(4), ago(3), ago(3), ago(3));
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/widget/pulls"))
            .withQueryParam("state", equalTo("closed"))
            .willReturn(okJson("[" + pull + "]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/widget/pulls"))
            .withQueryParam("state", equalTo("open"))
            .willReturn(okJson("[]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/widget/issues/10/timeline"))
            .willReturn(
                okJson(
                    """
                    [{"event":"reviewed","submitted_at":"%s","state":"approved",
                      "user":{"login":"lead","type":"User"},"author_association":"MEMBER"}]\
                    """
                        // Opened 96 hours ago, so this review is a first reply after 6 hours.
                        .formatted(now.minus(90, ChronoUnit.HOURS).toString()))));
    String issue =
        """
        {"number":%d,"title":"%s","html_url":"https://github.com/acme/widget/issues/%d",
         "user":{"login":"reporter"},"author_association":"NONE","created_at":"%s",
         "updated_at":"%s","comments":1,"labels":[{"name":"bug"}],"assignees":[]}\
        """;
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/widget/issues"))
            .willReturn(
                okJson(
                    "["
                        + issue.formatted(1, "Crash on empty input", 1, ago(2), ago(2))
                        + ","
                        + issue.formatted(2, "Wrong total", 2, ago(5), ago(5))
                        + "]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/widget/issues/1/timeline")).willReturn(okJson("[]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/widget/issues/2/timeline"))
            .willReturn(
                okJson(
                    """
                    [{"event":"commented","created_at":"%s","user":{"login":"newbie"},
                      "author_association":"NONE","body":"Can I work on this?"}]\
                    """
                        .formatted(ago(1)))));
  }

  @Test
  void healthPrintsTableWithReplyMetrics() {
    stubRepo();

    Run run = run("health", "acme/widget", "--no-cache");

    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(run.out()).contains("REPLY").contains("acme/widget").contains("6h").contains("0/1");
    server.verify(
        getRequestedFor(urlPathEqualTo("/repos/acme/widget/pulls"))
            .withHeader("Authorization", equalTo("Bearer test-token")));
  }

  @Test
  void issuesKeepsOnlyUnclaimedAndWritesJsonToFile() throws Exception {
    stubRepo();
    Path output = dir.resolve("out/report.json");

    Run run = run("issues", "acme/widget", "--no-cache", "-f", "json", "-o", output.toString());

    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(run.out()).isEmpty();
    JsonNode json = new ObjectMapper().readTree(Files.readString(output));
    assertThat(json.at("/issues").size()).isEqualTo(1);
    assertThat(json.at("/issues/0/number").asInt()).isEqualTo(1);
    assertThat(json.at("/repos/0/responses/sampled").asInt()).isEqualTo(1);
  }

  @Test
  void includeClaimedShowsWhyAnIssueIsTaken() {
    stubRepo();

    Run run = run("issues", "acme/widget", "--no-cache", "--include-claimed");

    assertThat(run.out()).contains("CLAIMED: @newbie asked to work on it 1 day ago");
  }

  @Test
  void reposFileAndCacheWorkTogether() throws Exception {
    stubRepo();
    Path repos = dir.resolve("repos.txt");
    Files.writeString(repos, "# watched\nacme/widget\n");
    Path cache = dir.resolve("cache.db");

    Run first = run("health", "--repos-file", repos.toString(), "--cache", cache.toString());

    assertThat(first.exitCode()).as(first.err()).isZero();
    assertThat(cache).exists();
  }

  @Test
  void htmlFormatProducesAPage() {
    stubRepo();

    Run run = run("issues", "acme/widget", "--no-cache", "-f", "html");

    assertThat(run.out()).startsWith("<!doctype html>").contains("Crash on empty input");
  }

  @Test
  void missingRepositoryFailsWithExitCodeOne() {
    server.stubFor(
        get(urlPathEqualTo("/repos/acme/gone/pulls")).willReturn(aResponse().withStatus(404)));

    Run run = run("health", "acme/gone", "--no-cache");

    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.out()).contains("error: acme/gone: Not found");
  }

  @Test
  void replySampleZeroSkipsTimelineCalls() {
    stubRepo();

    Run run = run("health", "acme/widget", "--no-cache", "--reply-sample", "0");

    assertThat(run.exitCode()).isZero();
    server.verify(0, getRequestedFor(urlPathEqualTo("/repos/acme/widget/issues/10/timeline")));
  }
}
