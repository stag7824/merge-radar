package io.github.stag7824.mergeradar.health;

import io.github.stag7824.mergeradar.github.GitHubClient;
import io.github.stag7824.mergeradar.model.GitHubJson;
import io.github.stag7824.mergeradar.model.PullRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Fetches a repository's recently closed pull requests and scores them. */
public final class RepoHealthService {

  private static final int PAGE_SIZE = 100;

  private final GitHubClient github;
  private final Clock clock;
  private final int maxPages;

  public RepoHealthService(GitHubClient github, Clock clock, int maxPages) {
    this.github = github;
    this.clock = clock;
    this.maxPages = maxPages;
  }

  public RepoHealthService(GitHubClient github) {
    this(github, Clock.systemUTC(), 3);
  }

  public RepoHealth health(String repo, Duration window) {
    Instant now = clock.instant();
    // Sorted by last update, newest first: every PR merged or closed inside the window was
    // updated inside it too, so they all come before the first PR updated earlier.
    List<PullRequest> closed =
        github
            .getAll(
                "repos/"
                    + repo
                    + "/pulls?state=closed&sort=updated&direction=desc&per_page="
                    + PAGE_SIZE,
                maxPages)
            .stream()
            .map(GitHubJson::pullRequest)
            .toList();
    return HealthCalculator.calculate(repo, closed, now, window, isPartial(closed, now, window));
  }

  /** The last page was full and still inside the window, so older PRs may be missing. */
  private boolean isPartial(List<PullRequest> closed, Instant now, Duration window) {
    if (closed.size() < maxPages * PAGE_SIZE) {
      return false;
    }
    Instant oldestUpdate = closed.get(closed.size() - 1).updatedAt();
    return oldestUpdate != null && oldestUpdate.isAfter(now.minus(window));
  }
}
