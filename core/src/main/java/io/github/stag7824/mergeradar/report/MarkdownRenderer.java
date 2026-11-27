package io.github.stag7824.mergeradar.report;

import io.github.stag7824.mergeradar.claims.ClaimStatus;
import io.github.stag7824.mergeradar.finder.Candidate;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.health.RepoHealth;
import java.util.ArrayList;
import java.util.List;

/** GitHub-flavoured Markdown, for issues, job summaries and files. */
final class MarkdownRenderer implements Format.Renderer {

  @Override
  public String render(Report report) {
    StringBuilder out = new StringBuilder("# merge-radar\n\n");
    out.append("Generated ").append(report.generatedAt()).append(".\n\n");

    if (!report.repos().isEmpty()) {
      out.append("## Repositories\n\n")
          .append("| Repository | Score | Outsider PRs merged | People | Median time to merge | ")
          .append("Acceptance | Median first reply | Never answered |\n")
          .append("|---|--:|--:|--:|--:|--:|--:|--:|\n");
      for (RepoHealth repo : report.repos()) {
        out.append("| [")
            .append(repo.repo())
            .append("](https://github.com/")
            .append(repo.repo())
            .append(") | ")
            .append(repo.score())
            .append(" | ")
            .append(repo.outsiderMerged())
            .append(repo.partial() ? "+" : "")
            .append(" | ")
            .append(repo.outsiderAuthors())
            .append(" | ")
            .append(Text.duration(repo.medianTimeToMerge()))
            .append(" | ")
            .append(Text.percent(repo.acceptanceRate()))
            .append(" | ")
            .append(Text.duration(repo.responses().medianTimeToFirstReply()))
            .append(" | ")
            .append(Text.silent(repo.responses()))
            .append(" |\n");
      }
      out.append("\nCounts cover pull requests from outsiders over the last ")
          .append(report.repos().get(0).window().toDays())
          .append(" days.\n\n");
    }

    if (!report.candidates().isEmpty()) {
      out.append("## Issues\n\n");
      for (Candidate candidate : report.candidates()) {
        var issue = candidate.issue();
        out.append("- **[")
            .append(issue.repo())
            .append("#")
            .append(issue.number())
            .append("](")
            .append(issue.url())
            .append(")** ")
            .append(escape(Text.truncate(issue.title(), 120)))
            .append(" (")
            .append(Text.age(issue.createdAt(), report.generatedAt()))
            .append(" old");
        if (!issue.labels().isEmpty()) {
          out.append(", ")
              .append(String.join(", ", issue.labels().stream().map(l -> "`" + l + "`").toList()));
        }
        out.append(")\n");
        List<String> details = new ArrayList<>();
        if (candidate.claim().status() != ClaimStatus.UNCLAIMED) {
          details.add(
              "**"
                  + candidate.claim().status()
                  + "**: "
                  + String.join("; ", candidate.claim().reasons()));
        }
        details.addAll(candidate.claim().notes());
        candidate
            .claim()
            .lastMaintainerComment()
            .ifPresent(comment ->
                details.add(
                    "maintainer @"
                        + comment.author()
                        + ": \""
                        + escape(Text.truncate(comment.body(), 160))
                        + "\""));
        details.forEach(detail -> out.append("  - ").append(detail).append("\n"));
      }
      out.append("\n");
    } else if (!report.repos().isEmpty()) {
      out.append("No matching issues.\n\n");
    }

    if (!report.errors().isEmpty()) {
      out.append("## Errors\n\n");
      report.errors().forEach(error -> out.append("- ").append(escape(error)).append("\n"));
    }
    return out.toString();
  }

  /** Keeps issue titles and comments from breaking the list or pinging people. */
  static String escape(String text) {
    return text.replace("|", "\\|").replace("@", "@​").replace("<", "&lt;");
  }
}
