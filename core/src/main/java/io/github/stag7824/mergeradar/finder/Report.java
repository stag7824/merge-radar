package io.github.stag7824.mergeradar.finder;

import io.github.stag7824.mergeradar.github.RateLimit;
import io.github.stag7824.mergeradar.health.RepoHealth;
import java.time.Instant;
import java.util.List;

/**
 * The result of a run.
 *
 * @param repos health of every repository that could be read, best first
 * @param candidates issues, ranked
 * @param errors repositories or issues that could not be read, and why
 * @param rateLimit the rate limit after the run
 */
public record Report(
    Instant generatedAt,
    List<RepoHealth> repos,
    List<Candidate> candidates,
    List<String> errors,
    RateLimit rateLimit) {

  public Report {
    repos = List.copyOf(repos);
    candidates = List.copyOf(candidates);
    errors = List.copyOf(errors);
  }
}
