package io.github.stag7824.mergeradar.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stag7824.mergeradar.github.ResponseCache.CachedResponse;
import io.github.stag7824.mergeradar.github.SqliteResponseCache;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CacheCommandTest {
  @TempDir Path dir;

  private record Run(int exitCode, String out, String err) {}

  private Run run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine commandLine = MergeRadar.commandLine();
    commandLine.setOut(new PrintWriter(out));
    commandLine.setErr(new PrintWriter(err));
    int exit = commandLine.execute(args);
    return new Run(exit, out.toString(), err.toString());
  }

  @Test
  void infoReportsPathCountAndSizeAndClearRemovesEntries() throws Exception {
    Path file = dir.resolve("cache.db");
    try (SqliteResponseCache cache = new SqliteResponseCache(file)) {
      cache.put("url", new CachedResponse("etag", "body", null));
    }
    long bytes = Files.size(file);
    Run info = run("cache", "info", "--cache", file.toString());
    assertThat(info.exitCode()).as(info.err()).isZero();
    assertThat(info.out()).contains(file.toString(), "Entries: 1", "Size: " + bytes + " bytes");

    Run clear = run("cache", "clear", "--cache", file.toString());
    assertThat(clear.exitCode()).as(clear.err()).isZero();
    assertThat(clear.out()).contains("Cleared cache: " + file);
    try (SqliteResponseCache cache = new SqliteResponseCache(file)) {
      assertThat(cache.count()).isZero();
    }
  }

  @Test
  void missingCacheIsEmptyAndMaintenanceDoesNotCreateIt() {
    Path file = dir.resolve("missing/cache.db");
    Run info = run("cache", "info", "--cache", file.toString());
    assertThat(info.exitCode()).as(info.err()).isZero();
    assertThat(info.out()).contains(file.toString(), "Entries: 0", "Size: 0 bytes");
    Run clear = run("cache", "clear", "--cache", file.toString());
    assertThat(clear.exitCode()).as(clear.err()).isZero();
    assertThat(file.getParent()).doesNotExist();
  }

  @Test
  void invalidCacheReturnsAnError() {
    Run info = run("cache", "info", "--cache", dir.toString());
    assertThat(info.exitCode()).isEqualTo(2);
    assertThat(info.err()).contains("Cannot open cache");
    Run clear = run("cache", "clear", "--cache", dir.toString());
    assertThat(clear.exitCode()).isEqualTo(2);
    assertThat(clear.err()).contains("Cannot open cache");
  }

  @Test
  void cacheWithoutSubcommandShowsHelp() {
    Run help = run("cache");
    assertThat(help.exitCode()).as(help.err()).isZero();
    assertThat(help.out()).contains("info", "clear");
  }
}
