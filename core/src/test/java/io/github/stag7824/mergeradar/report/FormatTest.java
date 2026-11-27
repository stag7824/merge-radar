package io.github.stag7824.mergeradar.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stag7824.mergeradar.claims.ClaimAssessment;
import io.github.stag7824.mergeradar.claims.ClaimStatus;
import io.github.stag7824.mergeradar.finder.Candidate;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.github.RateLimit;
import io.github.stag7824.mergeradar.health.RepoHealth;
import io.github.stag7824.mergeradar.health.ResponseStats;
import io.github.stag7824.mergeradar.model.AuthorAssociation;
import io.github.stag7824.mergeradar.model.Issue;
import io.github.stag7824.mergeradar.model.TimelineEvent.Comment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FormatTest {

  private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

  private static Report report() {
    RepoHealth health =
        new RepoHealth(
            "apache/cloudstack",
            Duration.ofDays(30),
            12,
            8,
            3,
            Optional.of(Duration.ofHours(100)),
            81,
            true,
            new ResponseStats(15, 2, Optional.of(Duration.ofHours(18))));
    Issue issue =
        new Issue(
            "apache/cloudstack",
            14715,
            "Template | URL too long for column",
            "https://github.com/apache/cloudstack/issues/14715",
            "reporter",
            AuthorAssociation.NONE,
            NOW.minus(Duration.ofDays(12)),
            NOW,
            2,
            List.of("type:bug"),
            List.of());
    Comment maintainer =
        new Comment(NOW, "lead", false, AuthorAssociation.MEMBER, "Confirmed, @someone can fix");
    ClaimAssessment claim =
        new ClaimAssessment(
            ClaimStatus.UNCLAIMED,
            List.of(),
            List.of("1 earlier pull request(s) closed without merging"),
            1,
            Optional.of(maintainer));
    return new Report(
        NOW,
        List.of(health),
        List.of(new Candidate(issue, health, claim)),
        List.of("gone/repo: Not found"),
        new RateLimit(5000, 4900, NOW));
  }

  @Test
  void tableShowsRepoAndIssue() {
    String table = Format.TABLE.render(report());

    assertThat(table)
        .contains("apache/cloudstack     81     12+       8    4.2d     80%     18h    2/15")
        .contains("[ 81] apache/cloudstack#14715  12d  Template | URL too long for column")
        .contains("note: 1 earlier pull request(s) closed without merging")
        .contains("maintainer @lead: Confirmed, @someone can fix")
        .contains("error: gone/repo: Not found");
  }

  @Test
  void markdownEscapesPipesAndMentions() {
    String markdown = Format.MARKDOWN.render(report());

    assertThat(markdown)
        .contains(
            "| [apache/cloudstack](https://github.com/apache/cloudstack) | 81 | 12+ | 8 | 4.2d | 80% |"
                + " 18h | 2/15 |")
        .contains("Template \\| URL too long")
        .contains("@​someone")
        .doesNotContain("@someone");
  }

  @Test
  void jsonHasStableFieldNames() throws Exception {
    JsonNode json = new ObjectMapper().readTree(Format.JSON.render(report()));

    assertThat(json.at("/repos/0/score").asInt()).isEqualTo(81);
    assertThat(json.at("/repos/0/medianHoursToMerge").asDouble()).isEqualTo(100.0);
    assertThat(json.at("/repos/0/partial").asBoolean()).isTrue();
    assertThat(json.at("/repos/0/responses/unanswered").asInt()).isEqualTo(2);
    assertThat(json.at("/repos/0/responses/medianHoursToFirstReply").asDouble()).isEqualTo(18.0);
    assertThat(json.at("/issues/0/status").asText()).isEqualTo("UNCLAIMED");
    assertThat(json.at("/issues/0/labels/0").asText()).isEqualTo("type:bug");
    assertThat(json.at("/issues/0/lastMaintainerComment/author").asText()).isEqualTo("lead");
    assertThat(json.at("/errors/0").asText()).isEqualTo("gone/repo: Not found");
    assertThat(json.at("/rateLimit/remaining").asInt()).isEqualTo(4900);
  }

  @Test
  void parsesFormatNames() {
    assertThat(Format.parse(" markdown ")).isEqualTo(Format.MARKDOWN);
  }
}
