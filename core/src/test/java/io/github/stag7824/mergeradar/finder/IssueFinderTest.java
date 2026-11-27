package io.github.stag7824.mergeradar.finder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.stag7824.mergeradar.claims.ClaimDetector;
import io.github.stag7824.mergeradar.claims.ClaimStatus;
import io.github.stag7824.mergeradar.github.GitHubClient;
import io.github.stag7824.mergeradar.health.RepoHealthService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IssueFinderTest {

  private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

  private WireMockServer server;
  private IssueFinder finder;

  @BeforeEach
  void start() {
    server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    GitHubClient github = GitHubClient.builder().baseUrl(server.baseUrl()).build();
    finder =
        new IssueFinder(
            github, new RepoHealthService(github, clock, 3), new ClaimDetector(), clock, 4);
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private static String pull(String login, String association, String created, String merged) {
    return """
    {"number":1,"user":{"login":"%s","type":"User"},"author_association":"%s",
     "created_at":"%s","updated_at":"%s","closed_at":"%s","merged_at":"%s"}\
    """
        .formatted(login, association, created, merged, merged, merged);
  }

  private static String issue(int number, String association, String created, String assignees) {
    return """
    {"number":%d,"title":"Issue %d","html_url":"https://github.com/x/y/issues/%d",
     "user":{"login":"reporter"},"author_association":"%s","created_at":"%s",
     "updated_at":"%s","comments":1,"labels":[{"name":"bug"}],"assignees":%s}\
    """
        .formatted(number, number, number, association, created, created, assignees);
  }

  private void stubHealthyRepo(String repo) {
    server.stubFor(
        get(urlPathEqualTo("/repos/" + repo + "/pulls"))
            .willReturn(
                okJson(
                    "["
                        + pull("a", "CONTRIBUTOR", "2026-11-20T00:00:00Z", "2026-11-21T00:00:00Z")
                        + ","
                        + pull("b", "NONE", "2026-11-10T00:00:00Z", "2026-11-12T00:00:00Z")
                        + "]")));
  }

  private IssueQuery query(List<String> repos, boolean outsiderOnly, boolean includeClaimed) {
    return new IssueQuery(
        repos,
        List.of("bug"),
        List.of("status: team-only"),
        null,
        outsiderOnly,
        includeClaimed,
        30,
        Duration.ofDays(30));
  }

  @Test
  void findsUnclaimedIssuesAndSkipsTheRest() {
    stubHealthyRepo("x/y");
    String pullRequestInIssueList =
        "{\"number\":4,\"pull_request\":{},\"title\":\"PR\",\"user\":{\"login\":\"p\"},"
            + "\"created_at\":\"2026-11-25T00:00:00Z\",\"labels\":[],\"assignees\":[]}";
    server.stubFor(
        get(urlPathEqualTo("/repos/x/y/issues"))
            .withQueryParam("labels", equalTo("bug"))
            .willReturn(
                okJson(
                    "["
                        + issue(1, "NONE", "2026-11-20T00:00:00Z", "[]")
                        + ","
                        + issue(2, "NONE", "2026-11-19T00:00:00Z", "[]")
                        + ","
                        + issue(3, "NONE", "2026-11-18T00:00:00Z", "[{\"login\":\"dev\"}]")
                        + ","
                        + pullRequestInIssueList
                        + "]")));
    server.stubFor(get(urlPathEqualTo("/repos/x/y/issues/1/timeline")).willReturn(okJson("[]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/x/y/issues/2/timeline"))
            .willReturn(
                okJson(
                    "[{\"event\":\"cross-referenced\",\"created_at\":\"2026-11-21T00:00:00Z\","
                        + "\"source\":{\"issue\":{\"number\":9,\"state\":\"open\","
                        + "\"repository\":{\"full_name\":\"x/y\"},\"pull_request\":{}}}}]")));

    Report report = finder.find(query(List.of("x/y"), false, false));

    assertThat(report.candidates()).extracting(c -> c.issue().number()).containsExactly(1);
    assertThat(report.errors()).isEmpty();
    // Issue 3 is assigned, so its timeline is never fetched.
    server.verify(0, getRequestedFor(urlPathEqualTo("/repos/x/y/issues/3/timeline")));
  }

  @Test
  void includeClaimedKeepsEverythingWithItsStatus() {
    stubHealthyRepo("x/y");
    server.stubFor(
        get(urlPathEqualTo("/repos/x/y/issues"))
            .willReturn(
                okJson(
                    "["
                        + issue(3, "NONE", "2026-11-18T00:00:00Z", "[{\"login\":\"dev\"}]")
                        + "]")));
    server.stubFor(get(urlPathEqualTo("/repos/x/y/issues/3/timeline")).willReturn(okJson("[]")));

    Report report = finder.find(query(List.of("x/y"), false, true));

    assertThat(report.candidates())
        .singleElement()
        .extracting(c -> c.claim().status())
        .isEqualTo(ClaimStatus.CLAIMED);
  }

  @Test
  void outsiderReportsOnlyDropsMaintainerIssues() {
    stubHealthyRepo("x/y");
    server.stubFor(
        get(urlPathEqualTo("/repos/x/y/issues"))
            .willReturn(
                okJson(
                    "["
                        + issue(1, "MEMBER", "2026-11-20T00:00:00Z", "[]")
                        + ","
                        + issue(2, "NONE", "2026-11-19T00:00:00Z", "[]")
                        + "]")));
    server.stubFor(get(urlPathEqualTo("/repos/x/y/issues/2/timeline")).willReturn(okJson("[]")));

    Report report = finder.find(query(List.of("x/y"), true, false));

    assertThat(report.candidates()).extracting(c -> c.issue().number()).containsExactly(2);
  }

  @Test
  void excludedLabelsAreSkippedWithoutFetchingTheirTimeline() {
    stubHealthyRepo("x/y");
    String teamOnly =
        issue(1, "NONE", "2026-11-20T00:00:00Z", "[]")
            .replace(
                "[{\"name\":\"bug\"}]", "[{\"name\":\"bug\"},{\"name\":\"status: team-only\"}]");
    server.stubFor(
        get(urlPathEqualTo("/repos/x/y/issues"))
            .willReturn(
                okJson(
                    "[" + teamOnly + "," + issue(2, "NONE", "2026-11-19T00:00:00Z", "[]") + "]")));
    server.stubFor(get(urlPathEqualTo("/repos/x/y/issues/2/timeline")).willReturn(okJson("[]")));

    Report report = finder.find(query(List.of("x/y"), false, false));

    assertThat(report.candidates()).extracting(c -> c.issue().number()).containsExactly(2);
    server.verify(0, getRequestedFor(urlPathEqualTo("/repos/x/y/issues/1/timeline")));
  }

  @Test
  void missingRepositoryIsReportedWithoutStoppingOthers() {
    stubHealthyRepo("x/y");
    server.stubFor(get(urlPathEqualTo("/repos/x/y/issues")).willReturn(okJson("[]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/gone/repo/pulls")).willReturn(aResponse().withStatus(404)));

    Report report = finder.find(query(List.of("gone/repo", "x/y"), false, false));

    assertThat(report.repos()).extracting(r -> r.repo()).containsExactly("x/y");
    assertThat(report.errors()).singleElement().asString().startsWith("gone/repo: Not found");
  }

  @Test
  void healthierRepositoriesRankFirst() {
    stubHealthyRepo("good/repo");
    server.stubFor(get(urlPathEqualTo("/repos/quiet/repo/pulls")).willReturn(okJson("[]")));
    for (String repo : List.of("good/repo", "quiet/repo")) {
      server.stubFor(
          get(urlPathEqualTo("/repos/" + repo + "/issues"))
              .willReturn(okJson("[" + issue(1, "NONE", "2026-11-20T00:00:00Z", "[]") + "]")));
      server.stubFor(
          get(urlPathEqualTo("/repos/" + repo + "/issues/1/timeline")).willReturn(okJson("[]")));
    }

    Report report = finder.find(query(List.of("quiet/repo", "good/repo"), false, false));

    assertThat(report.candidates())
        .extracting(c -> c.issue().repo())
        .containsExactly("good/repo", "quiet/repo");
  }

  @Test
  void rejectsMalformedRepositoryNames() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> query(List.of("not-a-repo"), false, false));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsNonPositiveConcurrency(int concurrency) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    GitHubClient github = GitHubClient.builder().baseUrl(server.baseUrl()).build();

    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new IssueFinder(
                github,
                new RepoHealthService(github, clock, 3),
                new ClaimDetector(),
                clock,
                concurrency))
        .withMessage("maxConcurrentRequests must be at least 1");
  }

  @Test
  void singleConcurrentRequestCanComplete() {
    stubHealthyRepo("x/y");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    GitHubClient github = GitHubClient.builder().baseUrl(server.baseUrl()).build();
    IssueFinder single =
        new IssueFinder(
            github, new RepoHealthService(github, clock, 3, 0), new ClaimDetector(), clock, 1);

    Report report = single.health(query(List.of("x/y"), false, false));

    assertThat(report.errors()).isEmpty();
    assertThat(report.repos()).extracting(r -> r.repo()).containsExactly("x/y");
  }
}
