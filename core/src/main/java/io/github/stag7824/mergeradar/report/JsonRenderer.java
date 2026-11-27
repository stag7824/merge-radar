package io.github.stag7824.mergeradar.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.stag7824.mergeradar.finder.Candidate;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.health.RepoHealth;

/**
 * A stable JSON document. Built by hand rather than by serialising the records, so that renaming a
 * Java field can never silently change the output that scripts depend on.
 */
final class JsonRenderer implements Format.Renderer {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Override
  public String render(Report report) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("generatedAt", report.generatedAt().toString());

    ArrayNode repos = root.putArray("repos");
    for (RepoHealth repo : report.repos()) {
      ObjectNode node = repos.addObject();
      node.put("repo", repo.repo());
      node.put("score", repo.score());
      node.put("windowDays", repo.window().toDays());
      node.put("outsiderMerged", repo.outsiderMerged());
      node.put("outsiderAuthors", repo.outsiderAuthors());
      node.put("outsiderClosedUnmerged", repo.outsiderClosedUnmerged());
      repo.medianTimeToMerge()
          .ifPresentOrElse(
              median -> node.put("medianHoursToMerge", median.toMinutes() / 60.0),
              () -> node.putNull("medianHoursToMerge"));
      repo.acceptanceRate()
          .ifPresentOrElse(
              rate -> node.put("acceptanceRate", rate), () -> node.putNull("acceptanceRate"));
      node.put("partial", repo.partial());
      ObjectNode responses = node.putObject("responses");
      responses.put("sampled", repo.responses().sampled());
      responses.put("unanswered", repo.responses().unanswered());
      repo.responses()
          .medianTimeToFirstReply()
          .ifPresentOrElse(
              median -> responses.put("medianHoursToFirstReply", median.toMinutes() / 60.0),
              () -> responses.putNull("medianHoursToFirstReply"));
    }

    ArrayNode issues = root.putArray("issues");
    for (Candidate candidate : report.candidates()) {
      var issue = candidate.issue();
      var claim = candidate.claim();
      ObjectNode node = issues.addObject();
      node.put("repo", issue.repo());
      node.put("number", issue.number());
      node.put("title", issue.title());
      node.put("url", issue.url());
      node.put("author", issue.author());
      node.put("authorAssociation", issue.association().name());
      node.put("createdAt", issue.createdAt().toString());
      node.put("comments", issue.comments());
      ArrayNode labels = node.putArray("labels");
      issue.labels().forEach(labels::add);
      node.put("repoScore", candidate.health().score());
      node.put("status", claim.status().name());
      ArrayNode reasons = node.putArray("reasons");
      claim.reasons().forEach(reasons::add);
      ArrayNode notes = node.putArray("notes");
      claim.notes().forEach(notes::add);
      node.put("abandonedPullRequests", claim.abandonedPullRequests());
      claim
          .lastMaintainerComment()
          .ifPresentOrElse(
              comment -> {
                ObjectNode last = node.putObject("lastMaintainerComment");
                last.put("author", comment.author());
                last.put("createdAt", comment.createdAt().toString());
                last.put("body", comment.body());
              },
              () -> node.putNull("lastMaintainerComment"));
    }

    ArrayNode errors = root.putArray("errors");
    report.errors().forEach(errors::add);
    ObjectNode rateLimit = root.putObject("rateLimit");
    rateLimit.put("limit", report.rateLimit().limit());
    rateLimit.put("remaining", report.rateLimit().remaining());
    try {
      return MAPPER.writeValueAsString(root) + System.lineSeparator();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
