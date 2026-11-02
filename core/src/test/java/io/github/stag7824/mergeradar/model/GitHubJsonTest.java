package io.github.stag7824.mergeradar.model;

import static io.github.stag7824.mergeradar.testing.Fixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GitHubJsonTest {

  @Test
  void readsMergedPullRequest() {
    PullRequest pr = GitHubJson.pullRequest(json("pull-merged.json"));

    assertThat(pr.number()).isEqualTo(14163);
    assertThat(pr.author()).isEqualTo("stag7824");
    assertThat(pr.association()).isEqualTo(AuthorAssociation.CONTRIBUTOR);
    assertThat(pr.merged()).isTrue();
    assertThat(pr.mergedAt()).isEqualTo(Instant.parse("2026-10-08T06:33:04Z"));
    assertThat(pr.byOutsider()).isTrue();
  }

  @Test
  void dependabotIsABotEvenThoughGitHubCallsItAContributor() {
    PullRequest pr = GitHubJson.pullRequest(json("pull-bot.json"));

    assertThat(pr.association()).isEqualTo(AuthorAssociation.CONTRIBUTOR);
    assertThat(pr.bot()).isTrue();
    assertThat(pr.byOutsider()).isFalse();
    assertThat(pr.closedWithoutMerge()).isTrue();
  }

  @Test
  void unknownAssociationCountsAsNone() {
    assertThat(AuthorAssociation.parse("SOMETHING_NEW")).isEqualTo(AuthorAssociation.NONE);
    assertThat(AuthorAssociation.parse(null)).isEqualTo(AuthorAssociation.NONE);
  }

  @Test
  void mannequinsAreNeitherMaintainersNorOutsiders() {
    assertThat(AuthorAssociation.MANNEQUIN.isMaintainer()).isFalse();
    assertThat(AuthorAssociation.MANNEQUIN.isOutsider()).isFalse();
  }
}
