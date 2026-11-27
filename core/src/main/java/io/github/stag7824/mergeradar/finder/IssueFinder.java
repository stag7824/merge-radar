package io.github.stag7824.mergeradar.finder;

import io.github.stag7824.mergeradar.claims.ClaimAssessment;
import io.github.stag7824.mergeradar.claims.ClaimDetector;
import io.github.stag7824.mergeradar.github.GitHubClient;
import io.github.stag7824.mergeradar.github.GitHubException;
import io.github.stag7824.mergeradar.health.RepoHealth;
import io.github.stag7824.mergeradar.health.RepoHealthService;
import io.github.stag7824.mergeradar.model.GitHubJson;
import io.github.stag7824.mergeradar.model.Issue;
import io.github.stag7824.mergeradar.model.TimelineEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Finds issues worth working on across repositories.
 *
 * <p>Each repository and each issue timeline is fetched on its own virtual thread. A semaphore caps
 * how many requests are in flight at once, because GitHub's secondary rate limits punish bursts,
 * not totals. A failure in one repository is reported and does not stop the others.
 */
public final class IssueFinder {

  private static final int TIMELINE_MAX_PAGES = 3;

  private final GitHubClient github;
  private final RepoHealthService healthService;
  private final ClaimDetector claimDetector;
  private final Clock clock;
  private final Semaphore inFlight;

  /**
   * @param maxConcurrentRequests maximum requests in flight; must be at least 1
   * @throws IllegalArgumentException if {@code maxConcurrentRequests} is less than 1
   */
  public IssueFinder(
      GitHubClient github,
      RepoHealthService healthService,
      ClaimDetector claimDetector,
      Clock clock,
      int maxConcurrentRequests) {
    if (maxConcurrentRequests < 1) {
      throw new IllegalArgumentException("maxConcurrentRequests must be at least 1");
    }
    this.github = github;
    this.healthService = healthService;
    this.claimDetector = claimDetector;
    this.clock = clock;
    this.inFlight = new Semaphore(maxConcurrentRequests);
  }

  /** Scores repositories only. */
  public Report health(IssueQuery query) {
    List<String> errors = Collections.synchronizedList(new ArrayList<>());
    List<RepoHealth> repos = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<String, Future<RepoHealth>> futures = new LinkedHashMap<>();
      for (String repo : query.repos()) {
        futures.put(
            repo,
            executor.submit(() -> limited(() -> healthService.health(repo, query.healthWindow()))));
      }
      futures.forEach((repo, future) -> {
        try {
          repos.add(future.get());
        } catch (ExecutionException e) {
          errors.add(repo + ": " + e.getCause().getMessage());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          errors.add(repo + ": interrupted");
        }
      });
    }
    repos.sort(Comparator.comparingInt(RepoHealth::score).reversed());
    return new Report(now(), repos, List.of(), errors, github.rateLimit());
  }

  /** Scores repositories, then finds and ranks issues in them. */
  public Report find(IssueQuery query) {
    Report health = health(query);
    List<String> errors = Collections.synchronizedList(new ArrayList<>(health.errors()));
    List<Candidate> candidates = Collections.synchronizedList(new ArrayList<>());
    Instant now = now();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> tasks = new ArrayList<>();
      for (RepoHealth repo : health.repos()) {
        List<Issue> issues;
        try {
          issues = limited(() -> openIssues(repo.repo(), query));
        } catch (GitHubException e) {
          errors.add(repo.repo() + ": " + e.getMessage());
          continue;
        }
        for (Issue issue : issues) {
          tasks.add(
              executor.submit(() -> {
                try {
                  ClaimAssessment claim = limited(() -> assess(issue, now));
                  if (query.includeClaimed() || claim.available()) {
                    candidates.add(new Candidate(issue, repo, claim));
                  }
                } catch (GitHubException e) {
                  errors.add(issue.repo() + "#" + issue.number() + ": " + e.getMessage());
                }
              }));
        }
      }
      for (Future<?> task : tasks) {
        try {
          task.get();
        } catch (ExecutionException e) {
          errors.add(String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    List<Candidate> ranked = new ArrayList<>(candidates);
    ranked.sort(Ranking.DEFAULT);
    return new Report(now, health.repos(), ranked, errors, github.rateLimit());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }

  private List<Issue> openIssues(String repo, IssueQuery query) {
    List<String> urls = new ArrayList<>();
    String base =
        "repos/"
            + repo
            + "/issues?state=open&sort=created&direction=desc&per_page="
            + Math.min(query.issuesPerRepo(), 100)
            + (query.since() == null ? "" : "&since=" + query.since());
    if (query.labels().isEmpty()) {
      urls.add(base);
    } else {
      query.labels().forEach(label -> urls.add(base + "&labels=" + encode(label)));
    }

    Map<Integer, Issue> issues = new LinkedHashMap<>();
    for (String url : urls) {
      int pages = (query.issuesPerRepo() + 99) / 100;
      github.getAll(url, pages).stream()
          .filter(json -> !GitHubJson.isPullRequest(json))
          .map(json -> GitHubJson.issue(repo, json))
          // "since" on this endpoint filters by last update, so check creation too.
          .filter(issue -> query.since() == null || !issue.createdAt().isBefore(query.since()))
          .filter(issue -> !query.outsiderReportsOnly() || issue.association().isOutsider())
          .filter(issue -> issue.labels().stream().noneMatch(query.excludeLabels()::contains))
          // An assignee already settles it; skip the timeline call.
          .filter(issue -> query.includeClaimed() || issue.assignees().isEmpty())
          .forEach(issue -> issues.putIfAbsent(issue.number(), issue));
    }
    return issues.values().stream()
        .sorted(Comparator.comparing(Issue::createdAt).reversed())
        .limit(query.issuesPerRepo())
        .toList();
  }

  private ClaimAssessment assess(Issue issue, Instant now) {
    List<TimelineEvent> timeline =
        github
            .getAll(
                "repos/" + issue.repo() + "/issues/" + issue.number() + "/timeline?per_page=100",
                TIMELINE_MAX_PAGES)
            .stream()
            .map(GitHubJson::timelineEvent)
            .toList();
    return claimDetector.assess(issue, timeline, now);
  }

  private <T> T limited(Supplier<T> call) {
    try {
      inFlight.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitHubException("Interrupted while waiting to call GitHub", e);
    }
    try {
      return call.get();
    } finally {
      inFlight.release();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
