package io.github.stag7824.mergeradar.model;

import java.time.Instant;

/**
 * The parts of a pull request merge-radar needs.
 *
 * @param closedAt {@code null} while open
 * @param mergedAt {@code null} unless merged
 */
public record PullRequest(
    int number,
    String author,
    boolean bot,
    AuthorAssociation association,
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt,
    Instant mergedAt) {

  public boolean merged() {
    return mergedAt != null;
  }

  public boolean closedWithoutMerge() {
    return closedAt != null && mergedAt == null;
  }

  /** Opened by a human who is not a maintainer. */
  public boolean byOutsider() {
    return !bot && association.isOutsider();
  }
}
