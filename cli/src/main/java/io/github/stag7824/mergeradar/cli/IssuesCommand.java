package io.github.stag7824.mergeradar.cli;

import io.github.stag7824.mergeradar.finder.IssueQuery;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.github.ResponseCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code merge-radar issues}: find unclaimed issues in healthy repositories. */
@Command(
    name = "issues",
    mixinStandardHelpOptions = true,
    description = {
      "Find open issues nobody is working on, ranked by how healthy their repository is.",
      "",
      "Examples:",
      "  merge-radar issues apache/cloudstack --label 'good first issue'",
      "  merge-radar issues --repos-file repos.txt --recent 30d --outsider-reports -f markdown"
    })
final class IssuesCommand implements Callable<Integer> {

  @Mixin CommonOptions options;

  @Spec CommandSpec spec;

  @Option(
      names = {"-l", "--label"},
      paramLabel = "LABEL",
      description = "Only issues with this label. Repeat for several; any of them matches.")
  List<String> labels = new ArrayList<>();

  @Option(
      names = "--recent",
      paramLabel = "DURATION",
      description = "Only issues opened within this long, e.g. 30d.")
  Duration recent;

  @Option(
      names = "--outsider-reports",
      description = "Only issues reported by people who are not maintainers.")
  boolean outsiderReports;

  @Option(names = "--include-claimed", description = "Also list claimed and probably fixed issues.")
  boolean includeClaimed;

  @Option(
      names = "--per-repo",
      defaultValue = "30",
      description =
          "How many of the newest matching issues to check per repository (default: ${DEFAULT-VALUE}).")
  int perRepo;

  @Override
  public Integer call() throws Exception {
    Instant since = recent == null ? null : Instant.now().minus(recent);
    IssueQuery query =
        new IssueQuery(
            options.allRepos(),
            labels,
            since,
            outsiderReports,
            includeClaimed,
            perRepo,
            options.window);
    ResponseCache cache = options.openCache();
    try (AutoCloseable ignored = CommonOptions.closer(cache)) {
      Report report = options.finder(cache).find(query);
      options.write(options.format.render(report), spec.commandLine().getOut());
      return report.repos().isEmpty() ? 1 : 0;
    }
  }
}
