package io.github.stag7824.mergeradar.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads JSON recorded from the real GitHub API (trimmed to the fields merge-radar reads). */
public final class Fixtures {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Fixtures() {}

  public static String text(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("No fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static JsonNode json(String name) {
    try {
      return MAPPER.readTree(text(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
