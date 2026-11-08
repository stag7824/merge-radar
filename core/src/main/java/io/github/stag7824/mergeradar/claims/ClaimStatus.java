package io.github.stag7824.mergeradar.claims;

/** Whether someone is already working on an issue. */
public enum ClaimStatus {
  /** Nobody appears to be working on it. */
  UNCLAIMED,
  /** Someone is assigned, has an open pull request, or recently asked to work on it. */
  CLAIMED,
  /** A merged pull request references it, so it is probably already fixed. */
  PROBABLY_FIXED
}
