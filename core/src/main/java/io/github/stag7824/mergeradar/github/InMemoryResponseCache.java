package io.github.stag7824.mergeradar.github;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A cache that lives for one process. Useful in tests and for one-off runs. */
public final class InMemoryResponseCache implements ResponseCache {

  private final Map<String, CachedResponse> entries = new ConcurrentHashMap<>();

  @Override
  public Optional<CachedResponse> get(String url) {
    return Optional.ofNullable(entries.get(url));
  }

  @Override
  public void put(String url, CachedResponse response) {
    entries.put(url, response);
  }

  public int size() {
    return entries.size();
  }
}
