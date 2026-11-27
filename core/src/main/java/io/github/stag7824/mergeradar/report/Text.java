package io.github.stag7824.mergeradar.report;

import io.github.stag7824.mergeradar.health.ResponseStats;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** Small formatting helpers shared by the renderers. */
final class Text {

  private Text() {}

  static String duration(Optional<Duration> duration) {
    return duration.map(Text::duration).orElse("-");
  }

  static String duration(Duration duration) {
    double hours = duration.toMinutes() / 60.0;
    if (hours < 48) {
      return String.format(Locale.ROOT, "%.0fh", hours);
    }
    return String.format(Locale.ROOT, "%.1fd", hours / 24);
  }

  static String age(Instant from, Instant now) {
    long days = Duration.between(from, now).toDays();
    return days < 1 ? "<1d" : days + "d";
  }

  static String percent(Optional<Double> share) {
    return share.map(value -> Math.round(value * 100) + "%").orElse("-");
  }

  /** "2/15" for two unanswered out of fifteen sampled, or "-" when not measured. */
  static String silent(ResponseStats responses) {
    return responses.measured() ? responses.unanswered() + "/" + responses.sampled() : "-";
  }

  static String truncate(String text, int max) {
    String oneLine = text.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
  }
}
