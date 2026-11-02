package io.github.stag7824.mergeradar.model;

import java.util.Locale;

/**
 * GitHub's {@code author_association}: how the author of an issue, pull request or comment is
 * related to the repository.
 */
public enum AuthorAssociation {
  OWNER,
  MEMBER,
  COLLABORATOR,
  CONTRIBUTOR,
  FIRST_TIME_CONTRIBUTOR,
  FIRST_TIMER,
  MANNEQUIN,
  NONE;

  /** Parses GitHub's value; anything unknown or missing counts as {@link #NONE}. */
  public static AuthorAssociation parse(String value) {
    if (value == null) {
      return NONE;
    }
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return NONE;
    }
  }

  /** People who can merge or triage: owners, organisation members and collaborators. */
  public boolean isMaintainer() {
    return this == OWNER || this == MEMBER || this == COLLABORATOR;
  }

  /** Everyone else, the people merge-radar is for. Mannequins (imported users) are neither. */
  public boolean isOutsider() {
    return !isMaintainer() && this != MANNEQUIN;
  }
}
