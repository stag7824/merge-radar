package io.github.stag7824.mergeradar.health;

import java.time.Duration;
import java.util.Optional;

/**
 * How quickly outsider pull requests get a first reply.
 *
 * @param sampled pull requests examined
 * @param unanswered of those, how many never got a reply
 * @param medianTimeToFirstReply median over the ones that did
 */
public record ResponseStats(
    int sampled, int unanswered, Optional<Duration> medianTimeToFirstReply) {

  public static final ResponseStats NOT_MEASURED = new ResponseStats(0, 0, Optional.empty());

  public boolean measured() {
    return sampled > 0;
  }
}
