package io.github.stag7824.mergeradar.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

  private WireMockServer server;
  private final List<Duration> sleeps = new ArrayList<>();
  private final InMemoryResponseCache cache = new InMemoryResponseCache();

  @BeforeEach
  void start() {
    server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private GitHubClient client(String token) {
    return GitHubClient.builder()
        .baseUrl(server.baseUrl())
        .token(token)
        .cache(cache)
        .maxRateLimitWait(Duration.ofMinutes(5))
        .clock(Clock.fixed(NOW, ZoneOffset.UTC))
        .sleeper(sleeps::add)
        .build();
  }

  @Test
  void sendsTokenAndApiVersion() {
    server.stubFor(get("/repos/a/b").willReturn(okJson("{\"full_name\":\"a/b\"}")));

    JsonNode repo = client("secret").get("repos/a/b");

    assertThat(repo.get("full_name").asText()).isEqualTo("a/b");
    server.verify(
        getRequestedFor(urlEqualTo("/repos/a/b"))
            .withHeader("Authorization", equalTo("Bearer secret"))
            .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28"))
            .withHeader("Accept", equalTo("application/vnd.github+json")));
  }

  @Test
  void worksWithoutToken() {
    server.stubFor(get("/repos/a/b").willReturn(okJson("{}")));

    client(null).get("repos/a/b");

    server.verify(getRequestedFor(urlEqualTo("/repos/a/b")).withHeader("Authorization", absent()));
  }

  @Test
  void followsPaginationLinks() {
    String page2 = server.baseUrl() + "/repos/a/b/pulls?page=2";
    server.stubFor(
        get("/repos/a/b/pulls")
            .willReturn(okJson("[{\"n\":1},{\"n\":2}]").withHeader("Link", linkTo(page2))));
    server.stubFor(get("/repos/a/b/pulls?page=2").willReturn(okJson("[{\"n\":3}]")));

    List<JsonNode> all = client(null).getAll("repos/a/b/pulls", 10);

    assertThat(all).extracting(node -> node.get("n").asInt()).containsExactly(1, 2, 3);
  }

  @Test
  void stopsAtMaxPages() {
    String page2 = server.baseUrl() + "/repos/a/b/pulls?page=2";
    server.stubFor(
        get("/repos/a/b/pulls")
            .willReturn(okJson("[{\"n\":1}]").withHeader("Link", linkTo(page2))));

    List<JsonNode> all = client(null).getAll("repos/a/b/pulls", 1);

    assertThat(all).hasSize(1);
    server.verify(0, getRequestedFor(urlEqualTo("/repos/a/b/pulls?page=2")));
  }

  @Test
  void reusesCachedBodyWhenNotModified() {
    server.stubFor(
        get("/repos/a/b")
            .withHeader("If-None-Match", absent())
            .willReturn(okJson("{\"stars\":1}").withHeader("ETag", "\"v1\"")));
    server.stubFor(
        get("/repos/a/b")
            .withHeader("If-None-Match", equalTo("\"v1\""))
            .willReturn(aResponse().withStatus(304)));
    GitHubClient client = client(null);

    client.get("repos/a/b");
    JsonNode second = client.get("repos/a/b");

    assertThat(second.get("stars").asInt()).isEqualTo(1);
    server.verify(
        getRequestedFor(urlEqualTo("/repos/a/b")).withHeader("If-None-Match", equalTo("\"v1\"")));
  }

  @Test
  void keepsPaginationWhenPageIsNotModified() {
    String page2 = server.baseUrl() + "/repos/a/b/pulls?page=2";
    server.stubFor(
        get("/repos/a/b/pulls")
            .withHeader("If-None-Match", absent())
            .willReturn(
                okJson("[{\"n\":1}]")
                    .withHeader("ETag", "\"p1\"")
                    .withHeader("Link", linkTo(page2))));
    server.stubFor(
        get("/repos/a/b/pulls")
            .withHeader("If-None-Match", equalTo("\"p1\""))
            .willReturn(aResponse().withStatus(304)));
    server.stubFor(get("/repos/a/b/pulls?page=2").willReturn(okJson("[{\"n\":2}]")));
    GitHubClient client = client(null);

    client.getAll("repos/a/b/pulls", 10);
    List<JsonNode> again = client.getAll("repos/a/b/pulls", 10);

    assertThat(again).hasSize(2);
  }

  @Test
  void waitsForRateLimitResetAndRetries() {
    server.stubFor(
        get("/repos/a/b")
            .inScenario("limit")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("X-RateLimit-Limit", "60")
                    .withHeader("X-RateLimit-Remaining", "0")
                    .withHeader(
                        "X-RateLimit-Reset", String.valueOf(NOW.plusSeconds(90).getEpochSecond())))
            .willSetStateTo("reset"));
    server.stubFor(
        get("/repos/a/b")
            .inScenario("limit")
            .whenScenarioStateIs("reset")
            .willReturn(okJson("{}")));

    client(null).get("repos/a/b");

    assertThat(sleeps).containsExactly(Duration.ofSeconds(91));
  }

  @Test
  void honoursRetryAfterForSecondaryLimits() {
    server.stubFor(
        get("/repos/a/b")
            .inScenario("secondary")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7"))
            .willSetStateTo("ok"));
    server.stubFor(
        get("/repos/a/b")
            .inScenario("secondary")
            .whenScenarioStateIs("ok")
            .willReturn(okJson("{}")));

    client(null).get("repos/a/b");

    assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
  }

  @Test
  void failsFastWhenResetIsTooFarAway() {
    server.stubFor(
        get("/repos/a/b")
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("X-RateLimit-Limit", "60")
                    .withHeader("X-RateLimit-Remaining", "0")
                    .withHeader(
                        "X-RateLimit-Reset",
                        String.valueOf(NOW.plusSeconds(3600).getEpochSecond()))));

    assertThatThrownBy(() -> client(null).get("repos/a/b"))
        .isInstanceOf(GitHubException.RateLimited.class)
        .hasMessageContaining("resets in 60 min");
    assertThat(sleeps).isEmpty();
  }

  @Test
  void treatsPlainForbiddenAsAnError() {
    server.stubFor(
        get("/repos/a/b")
            .willReturn(aResponse().withStatus(403).withHeader("X-RateLimit-Remaining", "42")));

    assertThatThrownBy(() -> client(null).get("repos/a/b"))
        .isInstanceOf(GitHubException.class)
        .isNotInstanceOf(GitHubException.RateLimited.class)
        .extracting(e -> ((GitHubException) e).status())
        .isEqualTo(403);
  }

  @Test
  void reportsMissingResource() {
    server.stubFor(get("/repos/a/missing").willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> client(null).get("repos/a/missing"))
        .isInstanceOf(GitHubException.NotFound.class);
  }

  @Test
  void recordsLastRateLimit() {
    server.stubFor(
        get("/repos/a/b")
            .willReturn(
                okJson("{}")
                    .withHeader("X-RateLimit-Limit", "5000")
                    .withHeader("X-RateLimit-Remaining", "4999")
                    .withHeader("X-RateLimit-Reset", "1800000000")));
    GitHubClient client = client(null);

    client.get("repos/a/b");

    assertThat(client.rateLimit().remaining()).isEqualTo(4999);
  }

  @Test
  void neverPrintsToken() {
    assertThat(client("secret-token").toString()).doesNotContain("secret-token");
  }

  @Test
  void parsesNextLinkAmongOthers() {
    String header =
        "<https://api.github.com/x?page=1>; rel=\"prev\", "
            + "<https://api.github.com/x?page=3>; rel=\"next\", "
            + "<https://api.github.com/x?page=9>; rel=\"last\"";

    assertThat(GitHubClient.parseNextLink(header)).contains("https://api.github.com/x?page=3");
    assertThat(GitHubClient.parseNextLink("<https://x>; rel=\"last\"")).isEmpty();
  }

  private static String linkTo(String next) {
    return "<" + next + ">; rel=\"next\"";
  }
}
