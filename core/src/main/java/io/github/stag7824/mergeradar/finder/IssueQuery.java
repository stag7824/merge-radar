package io.github.stag7824.mergeradar.finder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What to look for.
 *
 * @param repos repositories as {@code owner/name}
 * @param labels issues with any of these labels; empty means all open issues
 * @param since only issues created at or after this instant; {@code null} for no limit
 * @param outsiderReportsOnly only issues opened by people who are not maintainers
 * @param includeClaimed also return claimed and probably-fixed issues
 * @param issuesPerRepo how many of the newest matching issues to check per repository
 * @param healthWindow the window for {@link io.github.stag7824.mergeradar.health.RepoHealth}
 */
public record IssueQuery(
    List<String> repos,
    List<String> labels,
    Instant since,
    boolean outsiderReportsOnly,
    boolean includeClaimed,
    int issuesPerRepo,
    Duration healthWindow) {

  public IssueQuery {
    repos = List.copyOf(repos);
    labels = List.copyOf(labels);
    Objects.requireNonNull(healthWindow);
    if (issuesPerRepo < 1) {
      throw new IllegalArgumentException("issuesPerRepo must be at least 1");
    }
    for (String repo : repos) {
      if (!repo.matches("[\\w.-]+/[\\w.-]+")) {
        throw new IllegalArgumentException("Not a repository (owner/name): " + repo);
      }
    }
  }
}
