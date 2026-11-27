package io.github.stag7824.mergeradar.claims;

import io.github.stag7824.mergeradar.model.Issue;
import io.github.stag7824.mergeradar.model.TimelineEvent;
import io.github.stag7824.mergeradar.model.TimelineEvent.Assignment;
import io.github.stag7824.mergeradar.model.TimelineEvent.Comment;
import io.github.stag7824.mergeradar.model.TimelineEvent.CrossReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides whether an open issue is already being worked on. Pure: no I/O, the clock is a parameter.
 *
 * <p>The rules, in order of strength:
 *
 * <ol>
 *   <li>a merged pull request references the issue: {@link ClaimStatus#PROBABLY_FIXED};
 *   <li>an open pull request references it, or someone is assigned: {@link ClaimStatus#CLAIMED};
 *   <li>someone asked to work on it (for example "can I take this?") within the claim freshness
 *       window, default 30 days: {@link ClaimStatus#CLAIMED}. Older requests are reported as a
 *       stale claim but do not block the issue, because most of them are never followed up.
 * </ol>
 *
 * <p>Pull requests closed without merging do not block the issue; they are counted as earlier
 * attempts.
 */
public final class ClaimDetector {

  public static final Duration DEFAULT_CLAIM_FRESHNESS = Duration.ofDays(30);

  /**
   * Phrases people use to claim an issue. Kept deliberately narrow: a false "claimed" hides a good
   * issue, but a false "unclaimed" only costs the reader a glance at the thread.
   */
  private static final List<Pattern> CLAIM_PHRASES =
      List.of(
              "\\b(i'?d|i would) (like|love|be happy|be glad) to (work on|take|pick up|fix|tackle|"
                  + "handle|contribute)",
              "\\b(can|could|may) i (work on|take|pick up|fix|tackle|handle|try|have a go)",
              "\\bi(?:'ll| will| can| am going to|'m going to) (work on|take|fix|pick (this |it )?up|"
                  + "tackle|handle|(submit|open|raise|send|create) (a|the|my) (pr|fix|pull request))",
              "\\bi(?:'m| am) (working on|looking into|on) (this|it)\\b",
              "\\b(please |kindly |could you |can you )?assign (this|it)( issue)? to me\\b",
              "\\bassign me\\b",
              "^\\s*/(assign|take)\\b")
          .stream()
          .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE))
          .toList();

  private final Duration claimFreshness;

  public ClaimDetector(Duration claimFreshness) {
    this.claimFreshness = claimFreshness;
  }

  public ClaimDetector() {
    this(DEFAULT_CLAIM_FRESHNESS);
  }

  public ClaimAssessment assess(Issue issue, List<TimelineEvent> timeline, Instant now) {
    List<String> reasons = new ArrayList<>();
    List<String> notes = new ArrayList<>();

    List<CrossReference> pullRequests =
        timeline.stream()
            .filter(CrossReference.class::isInstance)
            .map(CrossReference.class::cast)
            .filter(CrossReference::pullRequest)
            .toList();
    List<Comment> comments =
        timeline.stream()
            .filter(Comment.class::isInstance)
            .map(Comment.class::cast)
            .filter(comment -> !comment.bot())
            .sorted(Comparator.comparing(Comment::createdAt))
            .toList();

    List<CrossReference> merged =
        pullRequests.stream().filter(CrossReference::mergedPullRequest).toList();
    merged.forEach(pr -> reasons.add("merged pull request " + ref(issue, pr) + " references it"));

    List<CrossReference> open =
        pullRequests.stream().filter(CrossReference::openPullRequest).toList();
    open.forEach(pr -> reasons.add("open pull request " + ref(issue, pr)));

    List<String> assignees = currentAssignees(issue, timeline);
    if (!assignees.isEmpty()) {
      reasons.add(
          "assigned to "
              + assignees.stream().map(login -> "@" + login).collect(Collectors.joining(", ")));
    }

    Optional<Comment> latestClaim =
        comments.stream().filter(ClaimDetector::isClaim).reduce((first, second) -> second);
    boolean freshClaim = false;
    if (latestClaim.isPresent()) {
      Comment claim = latestClaim.get();
      long days = Duration.between(claim.createdAt(), now).toDays();
      if (Duration.between(claim.createdAt(), now).compareTo(claimFreshness) <= 0) {
        freshClaim = true;
        reasons.add("@" + claim.author() + " asked to work on it " + ago(days));
      } else {
        notes.add("stale claim: @" + claim.author() + " asked to work on it " + ago(days));
      }
    }

    int abandoned =
        (int) pullRequests.stream().filter(CrossReference::abandonedPullRequest).count();
    if (abandoned > 0) {
      notes.add(abandoned + " earlier pull request(s) closed without merging");
    }

    ClaimStatus status;
    if (!merged.isEmpty()) {
      status = ClaimStatus.PROBABLY_FIXED;
    } else if (!open.isEmpty() || !assignees.isEmpty() || freshClaim) {
      status = ClaimStatus.CLAIMED;
    } else {
      status = ClaimStatus.UNCLAIMED;
    }

    Optional<Comment> lastMaintainerComment =
        comments.stream()
            .filter(comment -> comment.association().isMaintainer())
            .reduce((first, second) -> second);
    return new ClaimAssessment(status, reasons, notes, abandoned, lastMaintainerComment);
  }

  /** True if the comment asks to work on the issue. Quoted lines ("> ...") are ignored. */
  public static boolean isClaim(Comment comment) {
    String ownText =
        comment
            .body()
            .lines()
            .filter(line -> !line.stripLeading().startsWith(">"))
            .collect(Collectors.joining("\n"));
    return CLAIM_PHRASES.stream().anyMatch(phrase -> phrase.matcher(ownText).find());
  }

  /**
   * The issue's assignees. The issue itself is the source of truth; the timeline is only used when
   * the issue JSON has none, in case it was read from a stale cache.
   */
  private static List<String> currentAssignees(Issue issue, List<TimelineEvent> timeline) {
    if (!issue.assignees().isEmpty()) {
      return issue.assignees();
    }
    List<String> assigned = new ArrayList<>();
    timeline.stream()
        .filter(Assignment.class::isInstance)
        .map(Assignment.class::cast)
        .sorted(Comparator.comparing(Assignment::createdAt))
        .forEach(event -> {
          if (event.assigned()) {
            if (!assigned.contains(event.assignee())) {
              assigned.add(event.assignee());
            }
          } else {
            assigned.remove(event.assignee());
          }
        });
    return assigned;
  }

  private static String ref(Issue issue, CrossReference pr) {
    return pr.sourceRepo().isEmpty() || pr.sourceRepo().equals(issue.repo())
        ? "#" + pr.sourceNumber()
        : pr.sourceRepo() + "#" + pr.sourceNumber();
  }

  private static String ago(long days) {
    return days == 0 ? "today" : days == 1 ? "1 day ago" : days + " days ago";
  }
}
