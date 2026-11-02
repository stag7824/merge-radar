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
