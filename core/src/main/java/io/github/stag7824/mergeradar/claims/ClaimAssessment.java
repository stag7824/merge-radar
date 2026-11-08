package io.github.stag7824.mergeradar.claims;

import io.github.stag7824.mergeradar.model.TimelineEvent.Comment;
import java.util.List;
import java.util.Optional;

/**
 * The verdict on one issue, with the evidence behind it.
 *
 * @param reasons human-readable evidence, strongest first; empty for a clean unclaimed issue
 * @param notes things worth knowing that do not change the verdict, such as a stale claim
 * @param abandonedPullRequests pull requests that referenced the issue and were closed unmerged:
 *     earlier attempts, often worth reading before starting
 * @param lastMaintainerComment the most recent comment by a maintainer, if any
 */
public record ClaimAssessment(
    ClaimStatus status,
    List<String> reasons,
    List<String> notes,
    int abandonedPullRequests,
    Optional<Comment> lastMaintainerComment) {

  public ClaimAssessment {
    reasons = List.copyOf(reasons);
    notes = List.copyOf(notes);
  }

  public boolean available() {
    return status == ClaimStatus.UNCLAIMED;
  }
}
