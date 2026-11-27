package io.github.stag7824.mergeradar.cli;

import io.github.stag7824.mergeradar.finder.IssueQuery;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.github.ResponseCache;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code merge-radar health}: score repositories only. */
@Command(
    name = "health",
    mixinStandardHelpOptions = true,
    description = "Score how often and how fast repositories merge pull requests from outsiders.")
final class HealthCommand implements Callable<Integer> {

  @Mixin CommonOptions options;

  @Spec CommandSpec spec;

  @Override
  public Integer call() throws Exception {
    IssueQuery query =
        new IssueQuery(
            options.allRepos(), List.of(), List.of(), null, false, false, 1, options.window);
    ResponseCache cache = options.openCache();
    try (AutoCloseable ignored = CommonOptions.closer(cache)) {
      Report report = options.finder(cache).health(query);
      options.write(options.format.render(report), spec.commandLine().getOut());
      return report.repos().isEmpty() ? 1 : 0;
    }
  }
}
