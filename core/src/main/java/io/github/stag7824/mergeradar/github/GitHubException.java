package io.github.stag7824.mergeradar.github;

/** A GitHub API call failed. */
public class GitHubException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  public GitHubException(int status, String message) {
    super(message);
    this.status = status;
  }

  public GitHubException(String message, Throwable cause) {
    super(message, cause);
    this.status = -1;
  }

  /** The HTTP status, or {@code -1} when no response was received. */
  public int status() {
    return status;
  }

  /** The resource does not exist, or the token cannot see it. */
  public static final class NotFound extends GitHubException {
    private static final long serialVersionUID = 1L;

    public NotFound(String message) {
      super(404, message);
    }
  }

  /** The rate limit is exhausted and resets later than the caller is willing to wait. */
  public static final class RateLimited extends GitHubException {
    private static final long serialVersionUID = 1L;

    public RateLimited(int status, String message) {
      super(status, message);
    }
  }
}
