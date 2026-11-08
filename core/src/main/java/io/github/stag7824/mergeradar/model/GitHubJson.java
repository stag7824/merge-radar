package io.github.stag7824.mergeradar.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

  public static Issue issue(String repo, JsonNode json) {
    return new Issue(
        repo,
        json.path("number").asInt(),
        json.path("title").asText(""),
        json.path("html_url").asText(""),
        json.path("user").path("login").asText(""),
        AuthorAssociation.parse(text(json, "author_association")),
        instant(json, "created_at"),
        instant(json, "updated_at"),
        json.path("comments").asInt(),
        names(json.path("labels"), "name"),
        names(json.path("assignees"), "login"));
  }

  /** True for entries of the issues API that are really pull requests. */
  public static boolean isPullRequest(JsonNode issueJson) {
    return issueJson.has("pull_request");
  }

  public static TimelineEvent timelineEvent(JsonNode json) {
    String event = json.path("event").asText("");
    Instant at = instant(json, "created_at");
    return switch (event) {
      case "cross-referenced" -> {
        JsonNode source = json.path("source").path("issue");
        JsonNode pullRequest = source.get("pull_request");
        boolean isPullRequest = pullRequest != null && !pullRequest.isNull();
        yield new TimelineEvent.CrossReference(
            at,
            source.path("repository").path("full_name").asText(""),
            source.path("number").asInt(),
            isPullRequest,
            source.path("state").asText(""),
            isPullRequest ? instant(pullRequest, "merged_at") : null);
      }
      case "commented" ->
          new TimelineEvent.Comment(
              at,
              json.path("user").path("login").asText(""),
              isBot(json.path("user")),
              AuthorAssociation.parse(text(json, "author_association")),
              json.path("body").asText(""));
      case "assigned", "unassigned" ->
          new TimelineEvent.Assignment(
              at, json.path("assignee").path("login").asText(""), event.equals("assigned"));
      default -> new TimelineEvent.Other(at, event);
    };
  }

  private static List<String> names(JsonNode array, String field) {
    List<String> names = new ArrayList<>();
    array.forEach(item -> names.add(item.path(field).asText()));
    return names;
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
