package io.github.stag7824.mergeradar.cli;

import io.github.stag7824.mergeradar.claims.ClaimDetector;
import io.github.stag7824.mergeradar.finder.IssueFinder;
import io.github.stag7824.mergeradar.github.GitHubClient;
import io.github.stag7824.mergeradar.github.InMemoryResponseCache;
import io.github.stag7824.mergeradar.github.ResponseCache;
import io.github.stag7824.mergeradar.github.SqliteResponseCache;
import io.github.stag7824.mergeradar.health.RepoHealthService;
import io.github.stag7824.mergeradar.report.Format;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Options shared by every subcommand. */
final class CommonOptions {

  @Parameters(paramLabel = "OWNER/REPO", arity = "0..*", description = "Repositories to check.")
  List<String> repos = new ArrayList<>();

  @Option(
      names = "--repos-file",
      paramLabel = "FILE",
      description = "File with one OWNER/REPO per line (# starts a comment).")
  Path reposFile;

  @Option(
      names = "--window",
      defaultValue = "30d",
      description = "How far back to measure repository health (default: ${DEFAULT-VALUE}).")
  Duration window;

  @Option(
      names = {"-f", "--format"},
      defaultValue = "table",
      description = "table, markdown or json (default: ${DEFAULT-VALUE}).")
  Format format;

  @Option(
      names = {"-o", "--output"},
      paramLabel = "FILE",
      description = "Write to FILE.")
  Path output;

  @Option(
      names = "--token",
      description = "GitHub token. Defaults to $GITHUB_TOKEN, then to `gh auth token`.")
  String token;

  @Option(
      names = "--api-url",
      defaultValue = "https://api.github.com",
      description =
          "GitHub API base URL; for GitHub Enterprise Server use https://HOST/api/v3"
              + " (default: ${DEFAULT-VALUE}).")
  String apiUrl;

  @Option(
      names = "--cache",
      paramLabel = "FILE",
      description = "Response cache (default: ~/.cache/merge-radar/cache.db).")
  Path cache;

  @Option(names = "--no-cache", description = "Do not read or write the response cache.")
  boolean noCache;

  @Option(
      names = "--max-wait",
      defaultValue = "2m",
      description =
          "Longest wait for a rate-limit reset before giving up (default: ${DEFAULT-VALUE}).")
  Duration maxWait;

  @Option(
      names = "--reply-sample",
      defaultValue = "15",
      description =
          "Recent outsider pull requests per repository to check for a first reply; one API call"
              + " each, 0 to skip (default: ${DEFAULT-VALUE}).")
  int replySample;

  @Option(
      names = "--concurrency",
      defaultValue = "8",
      description = "Requests in flight at once, at least 1 (default: ${DEFAULT-VALUE}).")
  int concurrency;

  List<String> allRepos() {
    List<String> all = new ArrayList<>(repos);
    if (reposFile != null) {
      try {
        Files.readAllLines(reposFile, StandardCharsets.UTF_8).stream()
            .map(line -> line.replaceFirst("#.*", "").trim())
            .filter(line -> !line.isEmpty())
            .forEach(all::add);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read " + reposFile, e);
      }
    }
    if (all.isEmpty()) {
      throw new IllegalArgumentException("Give at least one OWNER/REPO, or --repos-file");
    }
    return all.stream().distinct().toList();
  }

  ResponseCache openCache() {
    if (noCache) {
      return new InMemoryResponseCache();
    }
    Path file =
        cache != null
            ? cache
            : Path.of(System.getProperty("user.home"), ".cache", "merge-radar", "cache.db");
    return new SqliteResponseCache(file);
  }

  IssueFinder finder(ResponseCache responseCache) {
    GitHubClient github =
        GitHubClient.builder()
            .baseUrl(apiUrl)
            .token(resolveToken())
            .cache(responseCache)
            .maxRateLimitWait(maxWait)
            .build();
    Clock clock = Clock.systemUTC();
    return new IssueFinder(
        github,
        new RepoHealthService(github, clock, 3, replySample),
        new ClaimDetector(),
        clock,
        concurrency);
  }

  void write(String text, java.io.PrintWriter out) {
    if (output == null) {
      out.print(text);
      out.flush();
      return;
    }
    try {
      Path parent = output.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(output, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + output, e);
    }
  }

  private String resolveToken() {
    if (token != null && !token.isBlank()) {
      return token;
    }
    String fromEnv = System.getenv("GITHUB_TOKEN");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv;
    }
    return ghAuthToken();
  }

  /** Borrows the GitHub CLI's token if it is installed and logged in; otherwise none. */
  private static String ghAuthToken() {
    try {
      Process process =
          new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      String token =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.exitValue() == 0 && !token.isEmpty() ? token : null;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  static AutoCloseable closer(ResponseCache cache) {
    return cache instanceof AutoCloseable closeable ? closeable : () -> {};
  }
}
