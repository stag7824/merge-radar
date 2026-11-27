package io.github.stag7824.mergeradar.health;

import static io.github.stag7824.mergeradar.model.AuthorAssociation.CONTRIBUTOR;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.MEMBER;
import static io.github.stag7824.mergeradar.model.AuthorAssociation.NONE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stag7824.mergeradar.model.GitHubJson;
import io.github.stag7824.mergeradar.model.PullRequest;
import io.github.stag7824.mergeradar.model.TimelineEvent;
import io.github.stag7824.mergeradar.model.TimelineEvent.Comment;
import io.github.stag7824.mergeradar.model.TimelineEvent.Review;
import io.github.stag7824.mergeradar.testing.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FirstReplyTest {

  private static final Instant OPENED = Instant.parse("2026-11-01T00:00:00Z");

  private static PullRequest pr(int number) {
    return new PullRequest(number, "author", false, NONE, OPENED, OPENED, null, null);
  }

  private static Instant hoursLater(int hours) {
    return OPENED.plus(Duration.ofHours(hours));
  }

  @Test
  void firstHumanReplyFromSomeoneElseCounts() {
    List<TimelineEvent> timeline =
        List.of(
            new Comment(hoursLater(1), "author", false, NONE, "Ready for review"),
            new Comment(hoursLater(2), "codecov[bot]", true, NONE, "Coverage report"),
            new Comment(hoursLater(30), "maintainer", false, MEMBER, "Thanks, looking"),
            new Review(hoursLater(5), "reviewer", false, CONTRIBUTOR, "approved"));

    assertThat(FirstReply.timeToFirstReply(pr(1), timeline)).contains(Duration.ofHours(5));
  }

  @Test
  void noReplyIsEmpty() {
    List<TimelineEvent> timeline =
        List.of(
            new Comment(hoursLater(1), "author", false, NONE, "ping?"),
            new TimelineEvent.Other(hoursLater(2), "labeled"));

    assertThat(FirstReply.timeToFirstReply(pr(1), timeline)).isEmpty();
  }

  @Test
  void statsCountUnansweredAndTakeTheMedian() {
    Map<PullRequest, List<TimelineEvent>> timelines = new LinkedHashMap<>();
    timelines.put(pr(1), List.of(new Comment(hoursLater(2), "m", false, MEMBER, "hi")));
    timelines.put(pr(2), List.of(new Comment(hoursLater(10), "m", false, MEMBER, "hi")));
    timelines.put(pr(3), List.of());

    ResponseStats stats = FirstReply.stats(timelines);

    assertThat(stats.sampled()).isEqualTo(3);
    assertThat(stats.unanswered()).isEqualTo(1);
    assertThat(stats.medianTimeToFirstReply()).contains(Duration.ofHours(6));
  }

  @Test
  void apacheCommitterReviewCountsEvenThoughGitHubCallsThemAContributor() {
    PullRequest recorded = GitHubJson.pullRequest(Fixtures.json("pull-14723.json"));
    List<TimelineEvent> timeline = new ArrayList<>();
    Fixtures.json("timeline-pull-14723.json")
        .forEach(event -> timeline.add(GitHubJson.timelineEvent(event)));

    // The first comment is from github-actions[bot] about a merge conflict; the first human reply
    // is DaanHoogland's review, recorded as CONTRIBUTOR although he is on the Apache PMC.
    Review first = (Review) timeline.get(2);
    assertThat(first.author()).isEqualTo("DaanHoogland");
    assertThat(first.association()).isEqualTo(CONTRIBUTOR);
    assertThat(FirstReply.timeToFirstReply(recorded, timeline))
        .contains(Duration.between(recorded.createdAt(), Instant.parse("2026-11-24T08:48:57Z")));
  }
}
