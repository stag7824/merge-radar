#!/usr/bin/env bash
# Entry point for the GitHub Action: turns the action's inputs (INPUT_* variables) into
# merge-radar arguments, writes the report to a file and to the job summary.
set -euo pipefail

args=("${INPUT_COMMAND:-issues}")

# Repositories: separated by spaces, commas or newlines.
for repo in $(printf '%s' "${INPUT_REPOS:-}" | tr ',' ' '); do
  args+=("$repo")
done
if [[ -n "${INPUT_REPOS_FILE:-}" ]]; then
  args+=(--repos-file "$INPUT_REPOS_FILE")
fi

# Labels may contain spaces ("good first issue"), so they are separated by commas or newlines.
if [[ "${args[0]}" == "issues" && -n "${INPUT_LABELS:-}" ]]; then
  while IFS= read -r label; do
    label="$(printf '%s' "$label" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [[ -n "$label" ]] && args+=(--label "$label")
  done < <(printf '%s' "$INPUT_LABELS" | tr ',' '\n')
fi

if [[ "${args[0]}" == "issues" && -n "${INPUT_EXCLUDE_LABELS:-}" ]]; then
  while IFS= read -r label; do
    label="$(printf '%s' "$label" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [[ -n "$label" ]] && args+=(--exclude-label "$label")
  done < <(printf '%s' "$INPUT_EXCLUDE_LABELS" | tr ',' '\n')
fi

if [[ "${args[0]}" == "issues" ]]; then
  [[ -n "${INPUT_RECENT:-}" ]] && args+=(--recent "$INPUT_RECENT")
  [[ "${INPUT_OUTSIDER_REPORTS:-false}" == "true" ]] && args+=(--outsider-reports)
  [[ -n "${INPUT_PER_REPO:-}" ]] && args+=(--per-repo "$INPUT_PER_REPO")
  [[ -n "${INPUT_MIN_SCORE:-}" ]] && args+=(--min-score "$INPUT_MIN_SCORE")
fi
[[ -n "${INPUT_WINDOW:-}" ]] && args+=(--window "$INPUT_WINDOW")
[[ -n "${INPUT_REPLY_SAMPLE:-}" ]] && args+=(--reply-sample "$INPUT_REPLY_SAMPLE")

output="${INPUT_OUTPUT:-merge-radar.md}"
args+=(--format "${INPUT_FORMAT:-markdown}" --output "$output")
args+=(--cache "${INPUT_CACHE_FILE:-.merge-radar-cache.db}")

export GITHUB_TOKEN="${INPUT_TOKEN:-${GITHUB_TOKEN:-}}"

status=0
java -jar /opt/merge-radar/merge-radar.jar "${args[@]}" || status=$?

if [[ -f "$output" && -n "${GITHUB_STEP_SUMMARY:-}" && "${INPUT_FORMAT:-markdown}" == "markdown" ]]; then
  cat "$output" >> "$GITHUB_STEP_SUMMARY"
fi
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "report=$output" >> "$GITHUB_OUTPUT"
fi
exit "$status"
