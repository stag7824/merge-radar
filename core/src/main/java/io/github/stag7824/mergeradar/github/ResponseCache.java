package io.github.stag7824.mergeradar.github;

import java.util.Optional;

/**
 * Stores GitHub responses by URL so repeat runs can send conditional requests. A {@code 304 Not
 * Modified} answer does not count against the rate limit, so a warm cache makes a second run almost
 * free.
 */
public interface ResponseCache {

  Optional<CachedResponse> get(String url);

  void put(String url, CachedResponse response);

  /**
   * A cached page.
   *
   * @param etag the ETag GitHub sent with the page
   * @param body the raw JSON body
   * @param nextUrl the {@code rel="next"} link of the page, or {@code null} on the last page
   */
  record CachedResponse(String etag, String body, String nextUrl) {}

  /** A cache that never stores anything. */
  static ResponseCache none() {
    return new ResponseCache() {
      @Override
      public Optional<CachedResponse> get(String url) {
        return Optional.empty();
      }

      @Override
      public void put(String url, CachedResponse response) {}
    };
  }
}
