package io.github.stag7824.mergeradar.report;

import io.github.stag7824.mergeradar.claims.ClaimStatus;
import io.github.stag7824.mergeradar.finder.Candidate;
import io.github.stag7824.mergeradar.finder.Report;
import io.github.stag7824.mergeradar.health.RepoHealth;
import java.util.ArrayList;
import java.util.List;

/**
 * A single self-contained HTML page: no scripts, no external styles or fonts, so it can be opened
 * from disk, attached to a workflow run or published with GitHub Pages as is. Every value from
 * GitHub is escaped, because issue titles and comments are written by strangers.
 */
final class HtmlRenderer implements Format.Renderer {

  private static final String STYLE =
      """
      :root{--bg:#fff;--fg:#1f2328;--muted:#59636e;--line:#d1d9e0;--card:#f6f8fa;--accent:#d6115e;
        --good:#1a7f37;--mid:#9a6700;--bad:#cf222e}
      @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#9198a1;
        --line:#30363d;--card:#161b22;--accent:#ff6b9d;--good:#3fb950;--mid:#d29922;--bad:#f85149}}
      *{box-sizing:border-box}
      body{margin:0;background:var(--bg);color:var(--fg);
        font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif}
      main{max-width:1000px;margin:0 auto;padding:24px 16px 48px}
      h1{margin:0 0 4px;font-size:26px}h1 span{color:var(--accent)}
      h2{margin:32px 0 12px;font-size:19px;border-bottom:1px solid var(--line);padding-bottom:6px}
      .muted{color:var(--muted);font-size:13px}
      .scroll{overflow-x:auto}
      table{border-collapse:collapse;width:100%;font-size:14px}
      th,td{padding:7px 10px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}
      th:first-child,td:first-child{text-align:left}
      th{color:var(--muted);font-weight:600;white-space:normal;vertical-align:bottom}
      a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
      .score{display:inline-block;min-width:34px;padding:1px 8px;border-radius:999px;color:#fff;
        font-weight:700;text-align:center}
      .s-good{background:var(--good)}.s-mid{background:var(--mid)}.s-bad{background:var(--bad)}
      .issue{background:var(--card);border:1px solid var(--line);border-radius:10px;
        padding:12px 14px;margin:10px 0}
      .issue h3{margin:0 0 4px;font-size:15px}
      .meta{color:var(--muted);font-size:13px}
      .label{display:inline-block;border:1px solid var(--line);border-radius:999px;
        padding:0 8px;margin-right:4px;font-size:12px}
      .status{font-weight:700;color:var(--mid)}
      ul{margin:6px 0 0;padding-left:18px;font-size:13px}
      blockquote{margin:6px 0 0;padding-left:10px;border-left:3px solid var(--line);
        color:var(--muted);font-size:13px}
      """;

  @Override
  public String render(Report report) {
    StringBuilder out = new StringBuilder();
    out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
        .append("<title>merge-radar report</title>\n<style>")
        .append(STYLE)
        .append("</style>\n</head>\n<body>\n<main>\n")
        .append("<h1>merge<span>-radar</span></h1>\n<p class=\"muted\">Generated ")
        .append(escape(report.generatedAt().toString()))
        .append(". Open source issues you can actually fix and get merged.</p>\n");

    if (!report.repos().isEmpty()) {
      out.append("<h2>Repositories</h2>\n<div class=\"scroll\"><table>\n<thead><tr>")
          .append("<th>Repository</th><th>Score</th><th>Outsider PRs merged</th><th>People</th>")
          .append("<th>Median time to merge</th><th>Acceptance</th><th>First reply</th>")
          .append("<th>Never answered</th></tr></thead>\n<tbody>\n");
      for (RepoHealth repo : report.repos()) {
        out.append("<tr><td>")
            .append(link("https://github.com/" + repo.repo(), repo.repo()))
            .append("</td><td>")
            .append(score(repo.score()))
            .append("</td><td>")
            .append(repo.outsiderMerged())
            .append(repo.partial() ? "+" : "")
            .append("</td><td>")
            .append(repo.outsiderAuthors())
            .append("</td><td>")
            .append(Text.duration(repo.medianTimeToMerge()))
            .append("</td><td>")
            .append(Text.percent(repo.acceptanceRate()))
            .append("</td><td>")
            .append(Text.duration(repo.responses().medianTimeToFirstReply()))
            .append("</td><td>")
            .append(Text.silent(repo.responses()))
            .append("</td></tr>\n");
      }
      out.append("</tbody></table></div>\n<p class=\"muted\">Pull requests from outsiders over the")
          .append(" last ")
          .append(report.repos().get(0).window().toDays())
          .append(" days. + means at least.</p>\n");
    }

    if (!report.candidates().isEmpty()) {
      out.append("<h2>Issues</h2>\n");
      for (Candidate candidate : report.candidates()) {
        var issue = candidate.issue();
        out.append("<article class=\"issue\">\n<h3>")
            .append(score(candidate.health().score()))
            .append(" ")
            .append(link(issue.url(), issue.repo() + "#" + issue.number()))
            .append(" ")
            .append(escape(issue.title()))
            .append("</h3>\n<div class=\"meta\">opened ")
            .append(Text.age(issue.createdAt(), report.generatedAt()))
            .append(" ago by ")
            .append(escape(issue.author()))
            .append(", ")
            .append(issue.comments())
            .append(" comments ");
        issue
            .labels()
            .forEach(l -> out.append("<span class=\"label\">").append(escape(l)).append("</span>"));
        out.append("</div>\n");
        List<String> details = new ArrayList<>();
        if (candidate.claim().status() != ClaimStatus.UNCLAIMED) {
          details.add(
              "<span class=\"status\">"
                  + candidate.claim().status()
                  + "</span>: "
                  + escape(String.join("; ", candidate.claim().reasons())));
        }
        candidate.claim().notes().forEach(note -> details.add(escape(note)));
        if (!details.isEmpty()) {
          out.append("<ul>");
          details.forEach(detail -> out.append("<li>").append(detail).append("</li>"));
          out.append("</ul>\n");
        }
        candidate
            .claim()
            .lastMaintainerComment()
            .ifPresent(comment ->
                out.append("<blockquote>")
                    .append(escape(comment.author()))
                    .append(": ")
                    .append(escape(Text.truncate(comment.body(), 240)))
                    .append("</blockquote>\n"));
        out.append("</article>\n");
      }
    } else if (!report.repos().isEmpty()) {
      out.append("<p>No matching issues.</p>\n");
    }

    if (!report.errors().isEmpty()) {
      out.append("<h2>Errors</h2>\n<ul>");
      report.errors().forEach(e -> out.append("<li>").append(escape(e)).append("</li>"));
      out.append("</ul>\n");
    }
    out.append("<p class=\"muted\">Made with ")
        .append(link("https://github.com/stag7824/merge-radar", "merge-radar"))
        .append(".</p>\n</main>\n</body>\n</html>\n");
    return out.toString();
  }

  private static String score(int score) {
    String level = score >= 80 ? "good" : score >= 50 ? "mid" : "bad";
    return "<span class=\"score s-" + level + "\">" + score + "</span>";
  }

  private static String link(String url, String text) {
    return "<a href=\"" + escape(url) + "\">" + escape(text) + "</a>";
  }

  static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (char c : text.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
