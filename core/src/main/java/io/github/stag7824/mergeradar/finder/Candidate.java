package io.github.stag7824.mergeradar.finder;

import io.github.stag7824.mergeradar.claims.ClaimAssessment;
import io.github.stag7824.mergeradar.health.RepoHealth;
import io.github.stag7824.mergeradar.model.Issue;

/** An issue, the health of its repository, and whether someone already took it. */
public record Candidate(Issue issue, RepoHealth health, ClaimAssessment claim) {}
