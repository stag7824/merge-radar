package io.github.stag7824.mergeradar.cli;

import java.time.Duration;
import java.util.Locale;
import picocli.CommandLine;

/** Parses short durations such as {@code 30d}, {@code 12h} or {@code 90m}. */
final class DurationConverter implements CommandLine.ITypeConverter<Duration> {

  @Override
  public Duration convert(String value) {
    String text = value.trim().toLowerCase(Locale.ROOT);
    if (!text.matches("\\d+[dhm]")) {
      throw new CommandLine.TypeConversionException(
          "Expected a duration like 30d, 12h or 90m, got '" + value + "'");
    }
    long amount = Long.parseLong(text.substring(0, text.length() - 1));
    return switch (text.charAt(text.length() - 1)) {
      case 'd' -> Duration.ofDays(amount);
      case 'h' -> Duration.ofHours(amount);
      default -> Duration.ofMinutes(amount);
    };
  }
}
