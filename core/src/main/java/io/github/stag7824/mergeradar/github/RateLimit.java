package io.github.stag7824.mergeradar.github;

import java.time.Instant;

/**
 * What GitHub reported about the rate limit on the last response.
 *
 * @param limit requests allowed per window
 * @param remaining requests left in this window
 * @param reset when the window resets
 */
public record RateLimit(int limit, int remaining, Instant reset) {

  public static final RateLimit UNKNOWN = new RateLimit(-1, -1, Instant.EPOCH);
}
