package io.github.stag7824.mergeradar.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stag7824.mergeradar.github.ResponseCache.CachedResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteResponseCacheTest {

  @TempDir Path dir;

  @Test
  void survivesReopening() {
    Path file = dir.resolve("nested/cache.db");
    try (SqliteResponseCache cache = new SqliteResponseCache(file)) {
      cache.put("https://api/x", new CachedResponse("\"e1\"", "[1]", "https://api/x?page=2"));
    }

    try (SqliteResponseCache reopened = new SqliteResponseCache(file)) {
      assertThat(reopened.get("https://api/x"))
          .contains(new CachedResponse("\"e1\"", "[1]", "https://api/x?page=2"));
      assertThat(reopened.get("https://api/other")).isEmpty();
    }
  }

  @Test
  void replacesExistingEntry() {
    try (SqliteResponseCache cache = new SqliteResponseCache(dir.resolve("cache.db"))) {
      cache.put("u", new CachedResponse("\"a\"", "1", null));
      cache.put("u", new CachedResponse("\"b\"", "2", null));

      assertThat(cache.get("u")).contains(new CachedResponse("\"b\"", "2", null));
    }
  }
}
