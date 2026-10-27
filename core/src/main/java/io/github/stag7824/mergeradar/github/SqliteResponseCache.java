package io.github.stag7824.mergeradar.github;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * A cache stored in a single SQLite file, so it survives between runs (for example in {@code
 * ~/.cache/merge-radar/cache.db}, or in a GitHub Actions cache).
 *
 * <p>One connection is shared and every call is synchronized: SQLite allows one writer at a time
 * anyway, and the cost is tiny next to a network round trip.
 */
public final class SqliteResponseCache implements ResponseCache, AutoCloseable {

  private final Connection connection;

  public SqliteResponseCache(Path file) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE IF NOT EXISTS responses ("
                + "url TEXT PRIMARY KEY, etag TEXT NOT NULL, body TEXT NOT NULL, next_url TEXT)");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create cache directory for " + file, e);
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot open cache " + file, e);
    }
  }

  @Override
  public synchronized Optional<CachedResponse> get(String url) {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT etag, body, next_url FROM responses WHERE url = ?")) {
      query.setString(1, url);
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new CachedResponse(row.getString("etag"), row.getString("body"), row.getString(3)));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read cache entry for " + url, e);
    }
  }

  @Override
  public synchronized void put(String url, CachedResponse response) {
    try (PreparedStatement upsert =
        connection.prepareStatement(
            "INSERT INTO responses (url, etag, body, next_url) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(url) DO UPDATE SET etag = excluded.etag, body = excluded.body, "
                + "next_url = excluded.next_url")) {
      upsert.setString(1, url);
      upsert.setString(2, response.etag());
      upsert.setString(3, response.body());
      upsert.setString(4, response.nextUrl());
      upsert.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot write cache entry for " + url, e);
    }
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot close cache", e);
    }
  }
}
