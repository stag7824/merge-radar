package io.github.stag7824.mergeradar.claims;

import static io.github.stag7824.mergeradar.model.AuthorAssociation.CONTRIBUTOR;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.MEMBER;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stag7824.mergeradar.model.AuthorAssociation;
import io.github.stag7824.mergeradar.model.GitHubJson;
import io.github.stag7824.mergeradar.model.Issue;
import io.github.stag7824.mergeradar.model.TimelineEvent;
import io.github.stag7824.mergeradar.model.TimelineEvent.Assignment;
import io.github.stag7824.mergeradar.model.TimelineEvent.Comment;
import io.github.stag7824.mergeradar.model.TimelineEvent.CrossReference;
import io.github.stag7824.mergeradar.testing.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimDetectorTest {

  private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
  private final ClaimDetector detector = new ClaimDetector();

  private static Issue issue(List<String> assignees) {
    return new Issue(
        "a/b",
        1,
        "Bug",
        "https://github.com/a/b/issues/1",
        "reporter",
        NONE,
        NOW.minus(Duration.ofDays(60)),
        NOW,
        0,
        List.of("bug"),
        assignees);
  }

  private static Issue issue() {
    return issue(List.of());
  }

  private static Instant daysAgo(int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  private static CrossReference pr(int number, String state, Instant mergedAt) {
    return new CrossReference(daysAgo(5), "a/b", number, true, state, mergedAt);
  }

  private static Comment comment(
      String author, AuthorAssociation association, int daysAgo, String body) {
    return new Comment(daysAgo(daysAgo), author, false, association, body);
  }

  @Test
  void quietIssueIsUnclaimed() {
    ClaimAssessment result =
        detector.assess(issue(), List.of(comment("x", NONE, 3, "Same problem here on 4.22")), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.UNCLAIMED);
    assertThat(result.reasons()).isEmpty();
    assertThat(result.available()).isTrue();
  }

  @Test
  void openPullRequestClaimsIt() {
    ClaimAssessment result = detector.assess(issue(), List.of(pr(7, "open", null)), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.CLAIMED);
    assertThat(result.reasons()).containsExactly("open pull request #7");
  }

  @Test
  void mergedPullRequestMeansProbablyFixed() {
    ClaimAssessment result =
        detector.assess(issue(), List.of(pr(7, "closed", daysAgo(1)), pr(8, "open", null)), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.PROBABLY_FIXED);
    assertThat(result.reasons()).first().asString().contains("merged pull request #7");
  }

  @Test
  void abandonedPullRequestsAreEarlierAttemptsNotClaims() {
    ClaimAssessment result =
        detector.assess(issue(), List.of(pr(7, "closed", null), pr(9, "closed", null)), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.UNCLAIMED);
    assertThat(result.abandonedPullRequests()).isEqualTo(2);
    assertThat(result.notes()).contains("2 earlier pull request(s) closed without merging");
  }

  @Test
  void mentionFromAnotherIssueIsNotAPullRequest() {
    CrossReference issueMention = new CrossReference(daysAgo(2), "a/b", 3, false, "open", null);

    assertThat(detector.assess(issue(), List.of(issueMention), NOW).status())
        .isEqualTo(ClaimStatus.UNCLAIMED);
  }

  @Test
  void pullRequestFromAnotherRepositoryIsNamedInFull() {
    CrossReference fork = new CrossReference(daysAgo(2), "c/d", 3, true, "open", null);

    assertThat(detector.assess(issue(), List.of(fork), NOW).reasons())
        .containsExactly("open pull request c/d#3");
  }

  @Test
  void assigneeClaimsIt() {
    ClaimAssessment result = detector.assess(issue(List.of("dev")), List.of(), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.CLAIMED);
    assertThat(result.reasons()).containsExactly("assigned to @dev");
  }

  @Test
  void timelineAssignmentsAreReplayedWhenIssueHasNone() {
    List<TimelineEvent> assignedThenUnassigned =
        List.of(new Assignment(daysAgo(9), "dev", true), new Assignment(daysAgo(4), "dev", false));
    List<TimelineEvent> assigned = List.of(new Assignment(daysAgo(9), "dev", true));

    assertThat(detector.assess(issue(), assignedThenUnassigned, NOW).status())
        .isEqualTo(ClaimStatus.UNCLAIMED);
    assertThat(detector.assess(issue(), assigned, NOW).status()).isEqualTo(ClaimStatus.CLAIMED);
  }

  @Test
  void recentClaimCommentClaimsIt() {
    ClaimAssessment result =
        detector.assess(
            issue(), List.of(comment("newbie", NONE, 4, "Hi! Can I work on this?")), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.CLAIMED);
    assertThat(result.reasons()).containsExactly("@newbie asked to work on it 4 days ago");
  }

  @Test
  void staleClaimIsANoteOnly() {
    ClaimAssessment result =
        detector.assess(
            issue(), List.of(comment("ghost", NONE, 45, "I'd like to work on this")), NOW);

    assertThat(result.status()).isEqualTo(ClaimStatus.UNCLAIMED);
    assertThat(result.notes())
        .containsExactly("stale claim: @ghost asked to work on it 45 days ago");
  }

  @Test
  void freshnessIsConfigurable() {
    ClaimDetector strict = new ClaimDetector(Duration.ofDays(7));

    assertThat(
            strict
                .assess(issue(), List.of(comment("x", NONE, 10, "can I take this one?")), NOW)
                .status())
        .isEqualTo(ClaimStatus.UNCLAIMED);
  }

  @Test
  void botCommentsAreIgnored() {
    Comment botClaim = new Comment(daysAgo(1), "stale[bot]", true, NONE, "/assign");

    assertThat(detector.assess(issue(), List.of(botClaim), NOW).status())
        .isEqualTo(ClaimStatus.UNCLAIMED);
  }

  @Test
  void keepsLatestMaintainerComment() {
    List<TimelineEvent> timeline = new ArrayList<>();
    timeline.add(comment("lead", MEMBER, 20, "Confirmed."));
    timeline.add(comment("user", CONTRIBUTOR, 10, "Any news?"));
    timeline.add(comment("lead", MEMBER, 5, "Happy to review a fix for this."));

    assertThat(detector.assess(issue(), timeline, NOW).lastMaintainerComment())
        .get()
        .extracting(Comment::body)
        .isEqualTo("Happy to review a fix for this.");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Can I work on this?",
        "could i take this issue",
        "I'd like to work on this.",
        "I would love to fix this",
        "I'll submit a PR later today",
        "I will work on it",
        "I'm working on this",
        "I am looking into it",
        "Please assign this to me",
        "assign me",
        "/assign",
        "Hey, I'm going to pick this up"
      })
  void recognisesClaims(String body) {
    assertThat(ClaimDetector.isClaim(comment("x", NONE, 1, body))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "I can reproduce this on 4.22",
        "Is anyone working on this?",
        "Who can I assign this to?",
        "This also happens with VMware",
        "> Can I work on this?\nThe person above never opened a PR."
      })
  void ignoresNonClaims(String body) {
    assertThat(ClaimDetector.isClaim(comment("x", NONE, 1, body))).isFalse();
  }

  @Test
  void recordedCloudStackIssueWithOpenFixIsClaimed() {
    Issue recorded = GitHubJson.issue("apache/cloudstack", Fixtures.json("issue-14709.json"));
    List<TimelineEvent> timeline = new ArrayList<>();
    Fixtures.json("timeline-14709.json")
        .forEach(event -> timeline.add(GitHubJson.timelineEvent(event)));

    ClaimAssessment result =
        detector.assess(recorded, timeline, Instant.parse("2026-11-27T00:00:00Z"));

    assertThat(recorded.number()).isEqualTo(14709);
    assertThat(result.status()).isEqualTo(ClaimStatus.CLAIMED);
    assertThat(result.reasons()).containsExactly("open pull request #14722");
  }
}
