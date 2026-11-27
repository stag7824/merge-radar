package io.github.stag7824.mergeradar.report;

import io.github.stag7824.mergeradar.finder.Report;
import java.util.Locale;

/** Output formats. */
public enum Format {
  TABLE(new TableRenderer()),
  MARKDOWN(new MarkdownRenderer()),
  JSON(new JsonRenderer()),
  HTML(new HtmlRenderer());

  private final Renderer renderer;

  Format(Renderer renderer) {
    this.renderer = renderer;
  }

  public String render(Report report) {
    return renderer.render(report);
  }

  public static Format parse(String value) {
    return valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  /** Turns a report into text. */
  interface Renderer {
    String render(Report report);
  }
}
