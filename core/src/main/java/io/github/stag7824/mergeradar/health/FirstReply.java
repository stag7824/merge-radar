package io.github.stag7824.mergeradar.health;

import io.github.stag7824.mergeradar.model.PullRequest;
import io.github.stag7824.mergeradar.model.TimelineEvent;
import io.github.stag7824.mergeradar.model.TimelineEvent.Comment;
import io.github.stag7824.mergeradar.model.TimelineEvent.Review;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Time until someone other than the author replies to a pull request. Pure.
 *
 * <p>A reply is a comment or review by anyone except the author and bots. It deliberately does not
 * require the replier to be a maintainer: GitHub's {@code author_association} cannot be trusted for
 * that. Members with private organisation membership show as {@code CONTRIBUTOR}, and so do Apache
 * committers, whose rights come from the ASF rather than from GitHub. In practice the first human
 * reply on an outsider's pull request almost always comes from a maintainer or a reviewer they
 * asked.
 */
public final class FirstReply {

  private FirstReply() {}

  public static Optional<Duration> timeToFirstReply(
      PullRequest pullRequest, List<TimelineEvent> timeline) {
    return timeline.stream()
        .filter(event -> isReply(pullRequest, event))
        .map(TimelineEvent::createdAt)
        .filter(at -> at != null && !at.isBefore(pullRequest.createdAt()))
        .min(Instant::compareTo)
        .map(at -> Duration.between(pullRequest.createdAt(), at));
  }

  /** Summarises replies for a set of pull requests and their timelines. */
  public static ResponseStats stats(Map<PullRequest, List<TimelineEvent>> timelines) {
    List<Duration> replies = new ArrayList<>();
    int unanswered = 0;
    for (var entry : timelines.entrySet()) {
      Optional<Duration> reply = timeToFirstReply(entry.getKey(), entry.getValue());
      if (reply.isPresent()) {
        replies.add(reply.get());
      } else {
        unanswered++;
      }
    }
    return new ResponseStats(timelines.size(), unanswered, HealthCalculator.median(replies));
  }

  private static boolean isReply(PullRequest pullRequest, TimelineEvent event) {
    return switch (event) {
      case Comment comment -> !comment.bot() && !comment.author().equals(pullRequest.author());
      case Review review -> !review.bot() && !review.author().equals(pullRequest.author());
      default -> false;
    };
  }
}
