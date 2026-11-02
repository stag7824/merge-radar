package io.github.stag7824.mergeradar.health;

import static io.github.stag7824.mergeradar.model.AuthorAssociation.CONTRIBUTOR;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.FIRST_TIME_CONTRIBUTOR;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.MEMBER;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.stag7824.mergeradar.model.AuthorAssociation;
import io.github.stag7824.mergeradar.model.PullRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HealthCalculatorTest {

  private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
  private static final Duration WINDOW = Duration.ofDays(30);

  private static PullRequest merged(
      String author, AuthorAssociation association, int daysAgo, int daysOpen) {
    Instant mergedAt = NOW.minus(Duration.ofDays(daysAgo));
    return new PullRequest(
        1,
        author,
        false,
        association,
        mergedAt.minus(Duration.ofDays(daysOpen)),
        mergedAt,
        mergedAt,
        mergedAt);
  }

  private static PullRequest rejected(String author, int daysAgo) {
    Instant closedAt = NOW.minus(Duration.ofDays(daysAgo));
    return new PullRequest(
        2, author, false, NONE, closedAt.minus(Duration.ofDays(1)), closedAt, closedAt, null);
  }

  private static PullRequest bot(int daysAgo) {
    Instant at = NOW.minus(Duration.ofDays(daysAgo));
    return new PullRequest(3, "dependabot[bot]", true, CONTRIBUTOR, at, at, at, at);
  }

  private static RepoHealth calculate(List<PullRequest> prs) {
    return HealthCalculator.calculate("a/b", prs, NOW, WINDOW, false);
  }

  @Test
  void emptyRepoScoresZero() {
    RepoHealth health = calculate(List.of());

    assertThat(health.score()).isZero();
    assertThat(health.medianTimeToMerge()).isEmpty();
    assertThat(health.acceptanceRate()).isEmpty();
  }

  @Test
  void countsOnlyOutsidersInsideTheWindow() {
    RepoHealth health =
        calculate(
            List.of(
                merged("alice", CONTRIBUTOR, 1, 2),
                merged("bob", FIRST_TIME_CONTRIBUTOR, 29, 2),
                merged("maintainer", MEMBER, 1, 1),
                merged("carol", NONE, 31, 1),
                bot(1)));

    assertThat(health.outsiderMerged()).isEqualTo(2);
    assertThat(health.outsiderAuthors()).isEqualTo(2);
  }

  @Test
  void breadthCountsPeopleNotPullRequests() {
    RepoHealth health =
        calculate(
            List.of(
                merged("alice", CONTRIBUTOR, 1, 1),
                merged("alice", CONTRIBUTOR, 2, 1),
                merged("alice", CONTRIBUTOR, 3, 1)));

    assertThat(health.outsiderMerged()).isEqualTo(3);
    assertThat(health.outsiderAuthors()).isEqualTo(1);
  }

  @Test
  void medianOfEvenCountIsTheMeanOfTheMiddleTwo() {
    RepoHealth health =
        calculate(
            List.of(
                merged("a", CONTRIBUTOR, 1, 1),
                merged("b", CONTRIBUTOR, 1, 2),
                merged("c", CONTRIBUTOR, 1, 4),
                merged("d", CONTRIBUTOR, 1, 100)));

    assertThat(health.medianTimeToMerge()).contains(Duration.ofDays(3));
  }

  @Test
  void acceptanceRateUsesOutsiderPullRequestsDecidedInTheWindow() {
    RepoHealth health =
        calculate(
            List.of(
                merged("a", CONTRIBUTOR, 1, 1),
                rejected("b", 2),
                rejected("c", 3),
                rejected("d", 40)));

    assertThat(health.outsiderClosedUnmerged()).isEqualTo(2);
    assertThat(health.acceptanceRate().orElseThrow()).isCloseTo(1 / 3.0, within(1e-9));
  }

  @Test
  void busyFastWelcomingRepoGetsFullMarks() {
    List<PullRequest> prs = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      prs.add(merged("person" + i, CONTRIBUTOR, 1 + i, 1));
    }

    assertThat(calculate(prs).score()).isEqualTo(100);
  }

  @Test
  void speedFallsLinearlyBetweenThreeAndThirtyDays() {
    assertThat(HealthCalculator.speed(Optional.of(Duration.ofDays(2)))).isEqualTo(25);
    assertThat(HealthCalculator.speed(Optional.of(Duration.ofDays(3)))).isEqualTo(25);
    assertThat(HealthCalculator.speed(Optional.of(Duration.ofDays(16).plusHours(12))))
        .isCloseTo(12.5, within(1e-9));
    assertThat(HealthCalculator.speed(Optional.of(Duration.ofDays(30)))).isZero();
    assertThat(HealthCalculator.speed(Optional.of(Duration.ofDays(90)))).isZero();
    assertThat(HealthCalculator.speed(Optional.empty())).isZero();
  }

  @Test
  void activityAndBreadthAreCapped() {
    assertThat(HealthCalculator.activity(1000)).isEqualTo(40);
    assertThat(HealthCalculator.activity(5)).isEqualTo(20);
    assertThat(HealthCalculator.breadth(50)).isEqualTo(20);
    assertThat(HealthCalculator.breadth(1)).isEqualTo(4);
  }

  @Test
  void slowSingleContributorRepoScoresLow() {
    // One outsider, merged after 20 days, and two rejections.
    RepoHealth health =
        calculate(List.of(merged("a", CONTRIBUTOR, 1, 20), rejected("b", 1), rejected("c", 1)));

    // activity 4 + breadth 4 + speed 25*(10/27)=9.26 + acceptance 5 = 22.26
    assertThat(health.score()).isEqualTo(22);
  }

  @Test
  void keepsPartialFlag() {
    assertThat(HealthCalculator.calculate("a/b", List.of(), NOW, WINDOW, true).partial()).isTrue();
  }
}
