package io.github.stag7824.mergeradar.health;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.stag7824.mergeradar.github.GitHubClient;
import io.github.stag7824.mergeradar.testing.Fixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepoHealthServiceTest {

  private WireMockServer server;

  @BeforeEach
  void start() {
    server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private RepoHealthService service(String now, int maxPages) {
    GitHubClient client = GitHubClient.builder().baseUrl(server.baseUrl()).build();
    return new RepoHealthService(client, Clock.fixed(Instant.parse(now), ZoneOffset.UTC), maxPages);
  }

  @Test
  void scoresRecordedPullRequests() {
    String body =
        "[" + Fixtures.text("pull-merged.json") + "," + Fixtures.text("pull-bot.json") + "]";
    server.stubFor(get(urlPathEqualTo("/repos/apache/cloudstack/pulls")).willReturn(okJson(body)));

    RepoHealth health =
        service("2026-10-20T00:00:00Z", 3).health("apache/cloudstack", Duration.ofDays(30));

    assertThat(health.outsiderMerged()).isEqualTo(1);
    assertThat(health.outsiderClosedUnmerged()).isZero();
    assertThat(health.partial()).isFalse();
    server.verify(
        getRequestedFor(urlPathEqualTo("/repos/apache/cloudstack/pulls"))
            .withQueryParam("state", equalTo("closed"))
            .withQueryParam("sort", equalTo("updated")));
  }

  @Test
  void samplesOutsiderPullRequestsForFirstReply() {
    String merged =
        """
        {"number":5,"user":{"login":"out","type":"User"},"author_association":"NONE",
         "created_at":"2026-11-10T00:00:00Z","updated_at":"2026-11-12T00:00:00Z",
         "closed_at":"2026-11-12T00:00:00Z","merged_at":"2026-11-12T00:00:00Z"}\
        """;
    String staleOpen =
        """
        {"number":6,"user":{"login":"out2","type":"User"},"author_association":"NONE",
         "created_at":"2026-11-05T00:00:00Z","updated_at":"2026-11-05T00:00:00Z",
         "closed_at":null,"merged_at":null}\
        """;
    String freshOpen =
        """
        {"number":7,"user":{"login":"out3","type":"User"},"author_association":"NONE",
         "created_at":"2026-11-19T00:00:00Z","updated_at":"2026-11-19T00:00:00Z",
         "closed_at":null,"merged_at":null}\
        """;
    server.stubFor(
        get(urlPathEqualTo("/repos/a/b/pulls"))
            .withQueryParam("state", equalTo("closed"))
            .willReturn(okJson("[" + merged + "]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/a/b/pulls"))
            .withQueryParam("state", equalTo("open"))
            .willReturn(okJson("[" + staleOpen + "," + freshOpen + "]")));
    server.stubFor(
        get(urlPathEqualTo("/repos/a/b/issues/5/timeline"))
            .willReturn(
                okJson(
                    "[{\"event\":\"commented\",\"created_at\":\"2026-11-10T12:00:00Z\","
                        + "\"user\":{\"login\":\"m\",\"type\":\"User\"},"
                        + "\"author_association\":\"MEMBER\",\"body\":\"thanks\"}]")));
    server.stubFor(get(urlPathEqualTo("/repos/a/b/issues/6/timeline")).willReturn(okJson("[]")));
    GitHubClient client = GitHubClient.builder().baseUrl(server.baseUrl()).build();
    RepoHealthService service =
        new RepoHealthService(
            client, Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC), 1, 10);

    RepoHealth health = service.health("a/b", Duration.ofDays(30));

    // #7 is open but younger than the grace period, so it is not sampled.
    assertThat(health.responses().sampled()).isEqualTo(2);
    assertThat(health.responses().unanswered()).isEqualTo(1);
    assertThat(health.responses().medianTimeToFirstReply()).contains(Duration.ofHours(12));
    server.verify(0, getRequestedFor(urlPathEqualTo("/repos/a/b/issues/7/timeline")));
  }

  @Test
  void flagsPartialWhenThePageLimitEndsInsideTheWindow() {
    StringBuilder page = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      page.append(i == 0 ? "" : ",").append(Fixtures.text("pull-merged.json"));
    }
    page.append("]");
    server.stubFor(get(urlPathEqualTo("/repos/a/b/pulls")).willReturn(okJson(page.toString())));

    RepoHealth health = service("2026-10-20T00:00:00Z", 1).health("a/b", Duration.ofDays(30));

    assertThat(health.partial()).isTrue();
  }
}
