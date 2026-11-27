package io.github.stag7824.mergeradar.cli;

import io.github.stag7824.mergeradar.github.SqliteResponseCache;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Local cache maintenance, without GitHub credentials or network requests. */
@Command(
    name = "cache",
    mixinStandardHelpOptions = true,
    description = "Inspect or clear the response cache.")
final class CacheCommand implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  private static Path cachePath(Path file) {
    return (file != null
            ? file
            : Path.of(System.getProperty("user.home"), ".cache", "merge-radar", "cache.db"))
        .toAbsolutePath()
        .normalize();
  }

  @Command(
      name = "info",
      mixinStandardHelpOptions = true,
      description = "Show cache path, entries and file size.")
  int info(
      @Option(
              names = "--cache",
              paramLabel = "FILE",
              description = "Response cache (default: ~/.cache/merge-radar/cache.db).")
          Path file)
      throws java.io.IOException {
    Path path = cachePath(file);
    long entries = 0;
    if (Files.exists(path)) {
      try (SqliteResponseCache cache = new SqliteResponseCache(path)) {
        entries = cache.count();
      }
    }
    long bytes = Files.exists(path) ? Files.size(path) : 0;
    spec.commandLine()
        .getOut()
        .printf("Path: %s%nEntries: %d%nSize: %d bytes%n", path, entries, bytes);
    return 0;
  }

  @Command(
      name = "clear",
      mixinStandardHelpOptions = true,
      description = "Delete every cached response.")
  int clear(
      @Option(
              names = "--cache",
              paramLabel = "FILE",
              description = "Response cache (default: ~/.cache/merge-radar/cache.db).")
          Path file) {
    Path path = cachePath(file);
    if (Files.exists(path)) {
      try (SqliteResponseCache cache = new SqliteResponseCache(path)) {
        cache.clear();
      }
    }
    spec.commandLine().getOut().printf("Cleared cache: %s%n", path);
    return 0;
  }
}
