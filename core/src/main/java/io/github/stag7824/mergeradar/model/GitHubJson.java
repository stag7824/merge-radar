package io.github.stag7824.mergeradar.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Converts GitHub REST JSON into merge-radar's records. */
public final class GitHubJson {

  private GitHubJson() {}

  public static PullRequest pullRequest(JsonNode json) {
    JsonNode user = json.path("user");
    return new PullRequest(
        json.path("number").asInt(),
        user.path("login").asText(""),
        isBot(user),
        AuthorAssociation.parse(text(json, "author_association")),
        instant(json, "created_at"),
        instant(json, "updated_at"),
        instant(json, "closed_at"),
        instant(json, "merged_at"));
  }

  static boolean isBot(JsonNode user) {
    return "Bot".equals(user.path("type").asText())
        || user.path("login").asText().endsWith("[bot]");
  }

  static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  static Instant instant(JsonNode json, String field) {
    String value = text(json, field);
    return value == null ? null : Instant.parse(value);
  }
}
