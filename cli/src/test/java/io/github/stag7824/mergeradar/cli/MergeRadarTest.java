package io.github.stag7824.mergeradar.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

class MergeRadarTest {

  @TempDir Path dir;

  private record Run(int exitCode, String out, String err) {}

  private static Run run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine commandLine = MergeRadar.commandLine();
    commandLine.setOut(new PrintWriter(out));
    commandLine.setErr(new PrintWriter(err));
    int exitCode = commandLine.execute(args);
    return new Run(exitCode, out.toString(), err.toString());
  }

  @Test
  void showsHelp() {
    Run run = run("issues", "--help");

    assertThat(run.exitCode()).isZero();
    assertThat(run.out()).contains("--outsider-reports", "--label", "--format");
  }

  @Test
  void rejectsBadDuration() {
    Run run = run("health", "a/b", "--window", "soon");

    assertThat(run.exitCode()).isEqualTo(2);
    assertThat(run.err()).contains("Expected a duration like 30d");
  }

  @Test
  void needsAtLeastOneRepository() {
    Run run = run("health", "--no-cache", "--token", "x");

    assertThat(run.exitCode()).isEqualTo(2);
    assertThat(run.err()).contains("Give at least one OWNER/REPO");
  }

  @Test
  void rejectsMalformedRepository() {
    Run run = run("health", "not-a-repo", "--no-cache", "--token", "x");

    assertThat(run.exitCode()).isEqualTo(2);
    assertThat(run.err()).contains("Not a repository");
  }

  @Test
  void readsReposFileWithComments() throws Exception {
    Path file = dir.resolve("repos.txt");
    Files.writeString(file, "# Java\napache/cloudstack  # cloud\n\napache/cloudstack\nb/c\n");
    CommonOptions options = new CommonOptions();
    options.reposFile = file;

    assertThat(options.allRepos()).containsExactly("apache/cloudstack", "b/c");
  }

  @Test
  void parsesDurations() {
    DurationConverter converter = new DurationConverter();

    assertThat(converter.convert("30d")).isEqualTo(Duration.ofDays(30));
    assertThat(converter.convert("12H")).isEqualTo(Duration.ofHours(12));
    assertThat(converter.convert("90m")).isEqualTo(Duration.ofMinutes(90));
    assertThatThrownBy(() -> converter.convert("1w"))
        .isInstanceOf(CommandLine.TypeConversionException.class);
  }

  @ParameterizedTest
  @CsvSource({"health, 0", "health, -1", "issues, 0", "issues, -1"})
  @Timeout(5)
  void rejectsNonPositiveConcurrency(String command, int concurrency) {
    Run run =
        run(
            command,
            "a/b",
            "--concurrency",
            Integer.toString(concurrency),
            "--no-cache",
            "--token",
            "x",
            "--api-url",
            "http://127.0.0.1:1");

    assertThat(run.exitCode()).isEqualTo(2);
    assertThat(run.err()).contains("maxConcurrentRequests must be at least 1");
  }
}
