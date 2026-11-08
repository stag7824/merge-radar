package io.github.stag7824.mergeradar.model;

import java.time.Instant;
import java.util.List;

/** The parts of an open issue merge-radar needs. */
public record Issue(
    String repo,
    int number,
    String title,
    String url,
    String author,
    AuthorAssociation association,
    Instant createdAt,
    Instant updatedAt,
    int comments,
    List<String> labels,
    List<String> assignees) {

  public Issue {
    labels = List.copyOf(labels);
    assignees = List.copyOf(assignees);
  }
}
