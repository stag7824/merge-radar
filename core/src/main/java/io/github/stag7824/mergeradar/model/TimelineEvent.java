package io.github.stag7824.mergeradar.model;

import java.time.Instant;

/** One entry of an issue's timeline, reduced to what claim detection needs. */
public sealed interface TimelineEvent {

  Instant createdAt();

  /**
   * Another issue or pull request mentioned this issue.
   *
   * @param state {@code open} or {@code closed}
   */
  record CrossReference(
      Instant createdAt,
      String sourceRepo,
      int sourceNumber,
      boolean pullRequest,
      String state,
      Instant mergedAt)
      implements TimelineEvent {

    public boolean openPullRequest() {
      return pullRequest && "open".equals(state);
    }

    public boolean mergedPullRequest() {
      return pullRequest && mergedAt != null;
    }

    public boolean abandonedPullRequest() {
      return pullRequest && "closed".equals(state) && mergedAt == null;
    }
  }

  /** A comment on the issue. */
  record Comment(
      Instant createdAt, String author, boolean bot, AuthorAssociation association, String body)
      implements TimelineEvent {}

  /**
   * A pull request review.
   *
   * @param state {@code approved}, {@code changes_requested}, {@code commented} or {@code
   *     dismissed}
   */
  record Review(
      Instant createdAt, String author, boolean bot, AuthorAssociation association, String state)
      implements TimelineEvent {}

  /** Someone was assigned to or unassigned from the issue. */
  record Assignment(Instant createdAt, String assignee, boolean assigned)
      implements TimelineEvent {}

  /** Any other event (labels, milestones, renames, ...). */
  record Other(Instant createdAt, String event) implements TimelineEvent {}
}
