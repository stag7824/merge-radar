package io.github.stag7824.mergeradar.report;

import io.github.stag7824.mergeradar.claims.ClaimStatus;
import io.github.stag7824.mergeradar.finder.Candidate;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.health.RepoHealth;
import java.util.Locale;

/** Plain-text tables for the terminal. */
final class TableRenderer implements Format.Renderer {

  @Override
  public String render(Report report) {
    StringBuilder out = new StringBuilder();
    if (!report.repos().isEmpty()) {
      int width = report.repos().stream().mapToInt(r -> r.repo().length()).max().orElse(4);
      width = Math.max(width, 4);
      out.append(
          String.format(
              Locale.ROOT,
              "%-" + width + "s  %5s  %6s  %6s  %6s  %6s%n",
              "REPO",
              "SCORE",
              "MERGED",
              "PEOPLE",
              "MEDIAN",
              "ACCEPT"));
      for (RepoHealth repo : report.repos()) {
        out.append(
            String.format(
                Locale.ROOT,
                "%-" + width + "s  %5d  %6s  %6d  %6s  %6s%n",
                repo.repo(),
                repo.score(),
                repo.outsiderMerged() + (repo.partial() ? "+" : ""),
                repo.outsiderAuthors(),
                Text.duration(repo.medianTimeToMerge()),
                Text.percent(repo.acceptanceRate())));
      }
      out.append(
          String.format(
              Locale.ROOT,
              "(outsider pull requests over the last %d days; + means at least)%n",
              report.repos().get(0).window().toDays()));
    }

    if (!report.candidates().isEmpty()) {
      out.append(System.lineSeparator());
      for (Candidate candidate : report.candidates()) {
        var issue = candidate.issue();
        out.append(
            String.format(
                Locale.ROOT,
                "[%3d] %s#%d  %s  %s%n",
                candidate.health().score(),
                issue.repo(),
                issue.number(),
                Text.age(issue.createdAt(), report.generatedAt()),
                Text.truncate(issue.title(), 80)));
        out.append("      ").append(issue.url()).append(System.lineSeparator());
        if (candidate.claim().status() != ClaimStatus.UNCLAIMED) {
          out.append("      ")
              .append(candidate.claim().status())
              .append(": ")
              .append(String.join("; ", candidate.claim().reasons()))
              .append(System.lineSeparator());
        }
        candidate
            .claim()
            .notes()
            .forEach(
                note -> out.append("      note: ").append(note).append(System.lineSeparator()));
        candidate
            .claim()
            .lastMaintainerComment()
            .ifPresent(comment ->
                out.append("      maintainer @")
                    .append(comment.author())
                    .append(": ")
                    .append(Text.truncate(comment.body(), 110))
                    .append(System.lineSeparator()));
      }
    }

    report
        .errors()
        .forEach(error -> out.append("error: ").append(error).append(System.lineSeparator()));
    return out.toString();
  }
}
