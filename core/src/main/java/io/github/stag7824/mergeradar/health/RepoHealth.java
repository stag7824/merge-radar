package io.github.stag7824.mergeradar.health;

import java.time.Duration;
import java.util.Optional;

/**
 * How friendly a repository is to outside contributors, measured over a recent window.
 *
 * @param repo {@code owner/name}
 * @param window how far back the numbers look
 * @param outsiderMerged pull requests from outsiders merged in the window
 * @param outsiderAuthors distinct outsiders among them
 * @param outsiderClosedUnmerged pull requests from outsiders closed without merging in the window
 * @param medianTimeToMerge median time from opening to merge for the merged outsider PRs
 * @param score 0 to 100, see {@link HealthCalculator}
 * @param partial {@code true} when the page limit was reached before the start of the window, so
 *     the counts are a lower bound
 */
public record RepoHealth(
    String repo,
    Duration window,
    int outsiderMerged,
    int outsiderAuthors,
    int outsiderClosedUnmerged,
    Optional<Duration> medianTimeToMerge,
    int score,
    boolean partial) {

  /** Share of decided outsider PRs that were merged, or empty when none were decided. */
  public Optional<Double> acceptanceRate() {
    int decided = outsiderMerged + outsiderClosedUnmerged;
    return decided == 0 ? Optional.empty() : Optional.of((double) outsiderMerged / decided);
  }
}
