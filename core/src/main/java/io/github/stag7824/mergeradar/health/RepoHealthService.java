package io.github.stag7824.mergeradar.health;

import io.github.stag7824.mergeradar.github.GitHubClient;
import io.github.stag7824.mergeradar.model.GitHubJson;
import io.github.stag7824.mergeradar.model.PullRequest;
import io.github.stag7824.mergeradar.model.TimelineEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Fetches a repository's recent pull requests and scores them. */
public final class RepoHealthService {

  private static final int PAGE_SIZE = 100;

  /** Open pull requests younger than this are too new to call "unanswered". */
  static final Duration GRACE_PERIOD = Duration.ofDays(7);

  private final GitHubClient github;
  private final Clock clock;
  private final int maxPages;
  private final int responseSample;

  /**
   * @param maxPages pages of closed pull requests to read (100 each)
   * @param responseSample how many recent outsider pull requests to check for a first reply; each
   *     costs one API call, and 0 turns the check off
   */
  public RepoHealthService(GitHubClient github, Clock clock, int maxPages, int responseSample) {
    this.github = github;
    this.clock = clock;
    this.maxPages = maxPages;
    this.responseSample = responseSample;
  }

  public RepoHealthService(GitHubClient github, Clock clock, int maxPages) {
    this(github, clock, maxPages, 0);
  }

  public RepoHealthService(GitHubClient github) {
    this(github, Clock.systemUTC(), 3, 15);
  }

  public RepoHealth health(String repo, Duration window) {
    Instant now = clock.instant();
    // Sorted by last update, newest first: every PR merged or closed inside the window was
    // updated inside it too, so they all come before the first PR updated earlier.
    List<PullRequest> closed = pulls(repo, "closed", maxPages);
    RepoHealth health =
        HealthCalculator.calculate(repo, closed, now, window, isPartial(closed, now, window));
    if (responseSample <= 0) {
      return health;
    }
    return health.withResponses(responses(repo, closed, now, window));
  }

  private ResponseStats responses(
      String repo, List<PullRequest> closed, Instant now, Duration window) {
    Instant since = now.minus(window);
    List<PullRequest> open = pulls(repo, "open", 1);
    List<PullRequest> sample =
        Stream.concat(
                closed.stream(),
                open.stream().filter(pr -> pr.createdAt().isBefore(now.minus(GRACE_PERIOD))))
            .filter(PullRequest::byOutsider)
            .filter(pr -> !pr.createdAt().isBefore(since))
            .sorted(Comparator.comparing(PullRequest::createdAt).reversed())
            .limit(responseSample)
            .toList();
    Map<PullRequest, List<TimelineEvent>> timelines = new LinkedHashMap<>();
    for (PullRequest pr : sample) {
      List<TimelineEvent> timeline = new ArrayList<>();
      github
          .getAll("repos/" + repo + "/issues/" + pr.number() + "/timeline?per_page=100", 1)
          .forEach(event -> timeline.add(GitHubJson.timelineEvent(event)));
      timelines.put(pr, timeline);
    }
    return FirstReply.stats(timelines);
  }

  private List<PullRequest> pulls(String repo, String state, int pages) {
    return github
        .getAll(
            "repos/"
                + repo
                + "/pulls?state="
                + state
                + "&sort=updated&direction=desc&per_page="
                + PAGE_SIZE,
            pages)
        .stream()
        .map(GitHubJson::pullRequest)
        .toList();
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
