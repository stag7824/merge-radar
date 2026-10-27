package io.github.stag7824.mergeradar.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stag7824.mergeradar.github.ResponseCache.CachedResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, read-only client for the GitHub REST API.
 *
 * <p>It does three things a naive client does not:
 *
 * <ul>
 *   <li>sends conditional requests ({@code If-None-Match}) using a {@link ResponseCache}, so
 *       unchanged pages come back as {@code 304} and do not use up the rate limit;
 *   <li>follows {@code Link: rel="next"} pagination;
 *   <li>when the rate limit is exhausted, waits until it resets and retries, but only up to a
 *       maximum wait. Beyond that it fails fast with {@link GitHubException.RateLimited} instead of
 *       hanging for an hour.
 * </ul>
 *
 * <p>The client is thread-safe and is meant to be shared, including across virtual threads.
 */
public final class GitHubClient {

  private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
  private static final int MAX_RATE_LIMIT_RETRIES = 3;

  private final HttpClient http;
  private final URI baseUrl;
  private final String token;
  private final ResponseCache cache;
  private final Duration maxRateLimitWait;
  private final Clock clock;
  private final Sleeper sleeper;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicReference<RateLimit> lastRateLimit = new AtomicReference<>(RateLimit.UNKNOWN);

  private GitHubClient(Builder builder) {
    this.http =
        Objects.requireNonNullElseGet(
            builder.http,
            () -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    this.baseUrl = URI.create(stripTrailingSlash(builder.baseUrl));
    this.token = builder.token;
    this.cache = builder.cache;
    this.maxRateLimitWait = builder.maxRateLimitWait;
    this.clock = builder.clock;
    this.sleeper = builder.sleeper;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fetches one resource, for example {@code repos/apache/cloudstack}. */
  public JsonNode get(String pathAndQuery) {
    return fetch(resolve(pathAndQuery)).body();
  }

  /**
   * Fetches a list resource and follows pagination, returning the elements of every page.
   *
   * @param maxPages stop after this many pages, even if more exist
   */
  public List<JsonNode> getAll(String pathAndQuery, int maxPages) {
    List<JsonNode> items = new ArrayList<>();
    String url = resolve(pathAndQuery);
    for (int page = 0; page < maxPages && url != null; page++) {
      Page result = fetch(url);
      if (!result.body().isArray()) {
        throw new GitHubException(200, "Expected a JSON array from " + url);
      }
      result.body().forEach(items::add);
      url = result.nextUrl();
    }
    return items;
  }

  /** The rate limit GitHub reported on the most recent response. */
  public RateLimit rateLimit() {
    return lastRateLimit.get();
  }

  private Page fetch(String url) {
    Optional<CachedResponse> cached = cache.get(url);
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response = send(request(url, cached));
      RateLimit rateLimit = readRateLimit(response);
      if (rateLimit != RateLimit.UNKNOWN) {
        lastRateLimit.set(rateLimit);
      }
      int status = response.statusCode();
      if (status == 304 && cached.isPresent()) {
        return new Page(parse(cached.get().body(), url), cached.get().nextUrl());
      }
      if (status == 200) {
        String next = nextLink(response).orElse(null);
        response
            .headers()
            .firstValue("ETag")
            .ifPresent(etag -> cache.put(url, new CachedResponse(etag, response.body(), next)));
        return new Page(parse(response.body(), url), next);
      }
      if (isRateLimited(response, rateLimit)) {
        Duration wait = rateLimitWait(response, rateLimit);
        if (attempt >= MAX_RATE_LIMIT_RETRIES || wait.compareTo(maxRateLimitWait) > 0) {
          throw new GitHubException.RateLimited(
              status,
              "GitHub rate limit exhausted; it resets in "
                  + wait.toMinutes()
                  + " min, more than the allowed wait of "
                  + maxRateLimitWait.toMinutes()
                  + " min. Use a token, or a warm cache, or try later.");
        }
        sleep(wait);
        continue;
      }
      if (status == 404) {
        throw new GitHubException.NotFound("Not found: " + url);
      }
      throw new GitHubException(status, "GitHub returned " + status + " for " + url);
    }
  }

  private HttpRequest request(String url, Optional<CachedResponse> cached) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "merge-radar")
            .GET();
    if (token != null && !token.isBlank()) {
      request.header("Authorization", "Bearer " + token);
    }
    cached.ifPresent(entry -> request.header("If-None-Match", entry.etag()));
    return request.build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new GitHubException("Request to " + request.uri() + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException("Interrupted while calling " + request.uri(), e);
    }
  }

  private void sleep(Duration wait) {
    try {
      sleeper.sleep(wait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException("Interrupted while waiting for the rate limit to reset", e);
    }
  }

  private JsonNode parse(String body, String url) {
    try {
      return mapper.readTree(body);
    } catch (JsonProcessingException e) {
      throw new GitHubException("Invalid JSON from " + url, e);
    }
  }

  private String resolve(String pathAndQuery) {
    if (pathAndQuery.startsWith("http://") || pathAndQuery.startsWith("https://")) {
      return pathAndQuery;
    }
    String path = pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery;
    return baseUrl + "/" + path;
  }

  private static boolean isRateLimited(HttpResponse<?> response, RateLimit rateLimit) {
    int status = response.statusCode();
    if (status != 403 && status != 429) {
      return false;
    }
    // 403 is also used for "forbidden"; only treat it as a rate limit when GitHub says so.
    return rateLimit.remaining() == 0 || response.headers().firstValue("Retry-After").isPresent();
  }

  private Duration rateLimitWait(HttpResponse<?> response, RateLimit rateLimit) {
    Optional<String> retryAfter = response.headers().firstValue("Retry-After");
    if (retryAfter.isPresent()) {
      return Duration.ofSeconds(Long.parseLong(retryAfter.get().trim()));
    }
    Duration untilReset = Duration.between(clock.instant(), rateLimit.reset());
    // GitHub's reset time has one-second precision; add a second so we do not wake up early.
    return untilReset.isNegative() ? Duration.ofSeconds(1) : untilReset.plusSeconds(1);
  }

  private static RateLimit readRateLimit(HttpResponse<?> response) {
    var headers = response.headers();
    Optional<String> limit = headers.firstValue("X-RateLimit-Limit");
    Optional<String> remaining = headers.firstValue("X-RateLimit-Remaining");
    Optional<String> reset = headers.firstValue("X-RateLimit-Reset");
    if (limit.isEmpty() || remaining.isEmpty() || reset.isEmpty()) {
      return RateLimit.UNKNOWN;
    }
    return new RateLimit(
        Integer.parseInt(limit.get()),
        Integer.parseInt(remaining.get()),
        Instant.ofEpochSecond(Long.parseLong(reset.get())));
  }

  static Optional<String> nextLink(HttpResponse<?> response) {
    return response.headers().firstValue("Link").flatMap(GitHubClient::parseNextLink);
  }

  static Optional<String> parseNextLink(String linkHeader) {
    Matcher matcher = NEXT_LINK.matcher(linkHeader);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  @Override
  public String toString() {
    // Never include the token.
    return "GitHubClient[" + baseUrl + ", authenticated=" + (token != null) + "]";
  }

  private record Page(JsonNode body, String nextUrl) {}

  /** Pauses the calling thread. Replaced in tests so they do not actually wait. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  /** Builds a {@link GitHubClient}. */
  public static final class Builder {
    private HttpClient http;
    private String baseUrl = "https://api.github.com";
    private String token;
    private ResponseCache cache = ResponseCache.none();
    private Duration maxRateLimitWait = Duration.ofMinutes(2);
    private Clock clock = Clock.systemUTC();
    private Sleeper sleeper = duration -> Thread.sleep(duration);

    private Builder() {}

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = Objects.requireNonNull(baseUrl);
      return this;
    }

    public Builder token(String token) {
      this.token = token;
      return this;
    }

    public Builder cache(ResponseCache cache) {
      this.cache = Objects.requireNonNull(cache);
      return this;
    }

    public Builder maxRateLimitWait(Duration maxRateLimitWait) {
      this.maxRateLimitWait = Objects.requireNonNull(maxRateLimitWait);
      return this;
    }

    public Builder httpClient(HttpClient http) {
      this.http = http;
      return this;
    }

    Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock);
      return this;
    }

    Builder sleeper(Sleeper sleeper) {
      this.sleeper = Objects.requireNonNull(sleeper);
      return this;
    }

    public GitHubClient build() {
      return new GitHubClient(this);
    }
  }
}
