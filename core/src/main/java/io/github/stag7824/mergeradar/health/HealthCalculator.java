package io.github.stag7824.mergeradar.health;

import io.github.stag7824.mergeradar.model.PullRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes {@link RepoHealth} from closed pull requests. Pure: no I/O, the clock is a parameter.
 *
 * <p>Only pull requests from outsiders count (see {@link PullRequest#byOutsider()}); bots and
 * maintainers are ignored, because what matters to a newcomer is whether people like them get
 * merged. The score adds four parts, each capped so one huge number cannot dominate:
 *
 * <table>
 *   <caption>Score parts</caption>
 *   <tr><th>Part</th><th>Points</th><th>Full marks at</th></tr>
 *   <tr><td>Activity: outsider PRs merged</td><td>40</td><td>10 or more</td></tr>
 *   <tr><td>Breadth: distinct outsiders merged</td><td>20</td><td>5 or more</td></tr>
 *   <tr><td>Speed: median time to merge</td><td>25</td><td>3 days or less, 0 at 30 days</td></tr>
 *   <tr><td>Acceptance: merged / (merged + closed unmerged)</td><td>15</td><td>100%</td></tr>
 * </table>
 *
 * <p>Breadth exists because ten merges from one regular contributor say less about a project's
 * openness than five merges from five different people.
 */
public final class HealthCalculator {

  static final int ACTIVITY_POINTS = 40;
  static final int ACTIVITY_FULL_AT = 10;
  static final int BREADTH_POINTS = 20;
  static final int BREADTH_FULL_AT = 5;
  static final int SPEED_POINTS = 25;
  static final Duration SPEED_FULL_AT = Duration.ofDays(3);
  static final Duration SPEED_ZERO_AT = Duration.ofDays(30);
  static final int ACCEPTANCE_POINTS = 15;

  private HealthCalculator() {}

  /**
   * @param closedPullRequests closed pull requests, in any order; open ones are ignored
   * @param partial whether {@code closedPullRequests} may be missing some PRs from the window
   */
  public static RepoHealth calculate(
      String repo,
      List<PullRequest> closedPullRequests,
      Instant now,
      Duration window,
      boolean partial) {
    Instant since = now.minus(window);
    List<PullRequest> merged =
        closedPullRequests.stream()
            .filter(PullRequest::byOutsider)
            .filter(pr -> pr.merged() && !pr.mergedAt().isBefore(since))
            .toList();
    long closedUnmerged =
        closedPullRequests.stream()
            .filter(PullRequest::byOutsider)
            .filter(pr -> pr.closedWithoutMerge() && !pr.closedAt().isBefore(since))
            .count();
    Set<String> authors = merged.stream().map(PullRequest::author).collect(Collectors.toSet());
    Optional<Duration> median =
        median(merged.stream().map(pr -> Duration.between(pr.createdAt(), pr.mergedAt())).toList());

    int score =
        (int)
            Math.round(
                activity(merged.size())
                    + breadth(authors.size())
                    + speed(median)
                    + acceptance(merged.size(), closedUnmerged));
    return new RepoHealth(
        repo, window, merged.size(), authors.size(), (int) closedUnmerged, median, score, partial);
  }

  static double activity(int outsiderMerged) {
    return ACTIVITY_POINTS * Math.min(outsiderMerged, ACTIVITY_FULL_AT) / (double) ACTIVITY_FULL_AT;
  }

  static double breadth(int outsiderAuthors) {
    return BREADTH_POINTS * Math.min(outsiderAuthors, BREADTH_FULL_AT) / (double) BREADTH_FULL_AT;
  }

  static double speed(Optional<Duration> medianTimeToMerge) {
    if (medianTimeToMerge.isEmpty()) {
      return 0;
    }
    long seconds = medianTimeToMerge.get().toSeconds();
    long full = SPEED_FULL_AT.toSeconds();
    long zero = SPEED_ZERO_AT.toSeconds();
    if (seconds <= full) {
      return SPEED_POINTS;
    }
    if (seconds >= zero) {
      return 0;
    }
    return SPEED_POINTS * (zero - seconds) / (double) (zero - full);
  }

  static double acceptance(int merged, long closedUnmerged) {
    long decided = merged + closedUnmerged;
    return decided == 0 ? 0 : ACCEPTANCE_POINTS * merged / (double) decided;
  }

  static Optional<Duration> median(List<Duration> durations) {
    if (durations.isEmpty()) {
      return Optional.empty();
    }
    List<Duration> sorted = durations.stream().sorted().toList();
    int middle = sorted.size() / 2;
    if (sorted.size() % 2 == 1) {
      return Optional.of(sorted.get(middle));
    }
    return Optional.of(sorted.get(middle - 1).plus(sorted.get(middle)).dividedBy(2));
  }
}
