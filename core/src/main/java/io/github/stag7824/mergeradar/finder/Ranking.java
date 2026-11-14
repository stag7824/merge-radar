package io.github.stag7824.mergeradar.finder;

import java.util.Comparator;

/**
 * Orders candidates for a newcomer.
 *
 * <ol>
 *   <li>healthier repositories first: a perfect issue is worthless if nobody reviews the PR;
 *   <li>then issues a maintainer has commented on, since that usually means the problem is
 *       confirmed and someone is paying attention;
 *   <li>then issues without earlier abandoned attempts, which are often harder than they look;
 *   <li>then newest first.
 * </ol>
 */
public final class Ranking {

  public static final Comparator<Candidate> DEFAULT =
      Comparator.<Candidate>comparingInt(c -> c.health().score())
          .reversed()
          .thenComparing(c -> c.claim().lastMaintainerComment().isEmpty())
          .thenComparing(c -> c.claim().abandonedPullRequests() > 0)
          .thenComparing(c -> c.issue().createdAt(), Comparator.reverseOrder());

  private Ranking() {}
}
