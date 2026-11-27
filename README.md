# merge-radar

[![CI](https://github.com/stag7824/merge-radar/actions/workflows/ci.yml/badge.svg)](https://github.com/stag7824/merge-radar/actions/workflows/ci.yml)

**Find open source issues you can actually fix and get merged.**

Most advice for new contributors is "look for a `good first issue` label". In large projects
those labels are often empty, years old, or already taken by someone who commented "can I work
on this?" and never came back. And a perfect issue is worthless if nobody will review your pull
request for two months.

merge-radar answers two questions with data from the GitHub API:

1. **Does this project merge pull requests from people like me, and how fast?**
2. **Which of its open issues is nobody already working on?**

## Example

Health of some Java projects, measured on pull requests from outsiders (people who are not
owners, members or collaborators, and not bots) over the last 30 days:

```console
$ merge-radar health --repos-file examples/java-repos.txt
REPO                            SCORE  MERGED  PEOPLE  MEDIAN  ACCEPT   REPLY  SILENT
hiero-ledger/hiero-sdk-java        99      13      10     22h     93%     21h    1/15
OpenAPITools/openapi-generator     98     129      59    2.5d     88%     10h    6/15
keycloak/keycloak                  97    164+      44    3.4d     83%     12h    1/15
apache/dolphinscheduler            97      18       9     26h     78%     14h    2/15
spring-projects/spring-boot        95      70      31     29h     64%     10h    3/15
checkstyle/checkstyle              95    179+      37     27h     67%     14h    0/15
apache/cloudstack                  72      42      26   56.6d     82%      1h    1/15
(outsider pull requests over the last 30 days; + means at least; REPLY is the median time to a first reply, SILENT the share never answered)
```

Apache CloudStack merges plenty of outside work, but the median pull request waits almost two
months. That matches my own experience there: worth it, but plan for a long review.

`REPLY` is the median time until someone other than the author (and not a bot) first comments on
or reviews a recent outsider pull request, and `SILENT` is how many of those never got a reply.
openapi-generator merges a lot, but 6 of its last 15 outside pull requests are still waiting
for anyone to answer. Replies are sampled (15 per repository by default, one API call each) and
are not part of the score.

Recent bug reports from outsiders that nobody has claimed:

```console
$ merge-radar issues apache/cloudstack checkstyle/checkstyle hiero-ledger/hiero-sdk-java \
    --recent 30d --outsider-reports
...
[ 95] checkstyle/checkstyle#23193  <1d  RegexpMultiline reports EOF matches on a nonexistent line without a trailing ne…
      https://github.com/checkstyle/checkstyle/issues/23193
[ 72] apache/cloudstack#14874  <1d  KVM: GetVolumeStatsCommand looks up the storage pool once per volume and discar…
      https://github.com/apache/cloudstack/issues/14874
```

Each issue also shows earlier pull requests that were closed without merging, stale claims, and
the last thing a maintainer said, so you can judge it before opening the thread.

Output is also available as Markdown (`-f markdown`) or JSON (`-f json`).

## Install

Requires Java 21 or newer. Download `merge-radar.jar` from the
[latest release](https://github.com/stag7824/merge-radar/releases/latest) and run
`java -jar merge-radar.jar --help`, or use the container image:

```sh
docker run --rm -e GITHUB_TOKEN ghcr.io/stag7824/merge-radar:latest health apache/cloudstack
```

To build from source:

```sh
git clone https://github.com/stag7824/merge-radar.git
cd merge-radar
mvn package -DskipTests
java -jar cli/target/merge-radar.jar --help
```

It reads the token from `--token`, then `GITHUB_TOKEN`, then `gh auth token` if the GitHub CLI
is logged in. It works without a token too, but then GitHub allows only 60 requests an hour.

## Usage

```text
merge-radar health OWNER/REPO... [--window 30d]
merge-radar cache info [--cache FILE]
merge-radar cache clear [--cache FILE]
merge-radar issues OWNER/REPO... [--label LABEL]... [--exclude-label LABEL]... [--recent 30d]
                                 [--outsider-reports]
                                 [--include-claimed] [--per-repo 30]

common: --repos-file FILE   -f table|markdown|json|html   -o FILE
        --reply-sample 15   --cache FILE | --no-cache   --max-wait 2m   --concurrency 8
        --api-url URL   (GitHub Enterprise Server: https://HOST/api/v3)
```

- `--label` can be repeated; an issue with any of the labels matches. Without it, all open
  issues are considered.
- `cache info` shows the absolute cache path, entry count and database file size in bytes.
  `cache clear` deletes every cached response; SQLite may retain the allocated file space.
  Both use `~/.cache/merge-radar/cache.db` by default, accept `--cache FILE`, and work
  without GitHub credentials. A missing cache is treated as empty and is not created.
- `--exclude-label` skips issues the project reserves for itself. Spring Boot, for example,
  labels many bugs `status: team-only` and declines outside pull requests for them.
- `--outsider-reports` keeps issues opened by users rather than maintainers. These are often
  real bugs with a clear reproduction, and better first targets than labelled issues.
- `--include-claimed` lists claimed and probably-fixed issues too, with the reason.

## GitHub Action

Run it on a schedule and get a digest in the job summary, or open it as an issue. See
[`examples/weekly-digest.yml`](examples/weekly-digest.yml):

```yaml
- uses: actions/cache@v4            # keeps runs cheap, see "Caching" below
  with:
    path: .merge-radar-cache.db
    key: merge-radar-${{ github.run_id }}
    restore-keys: merge-radar-
- uses: stag7824/merge-radar@v0.1.0
  with:
    repos: |
      apache/cloudstack
      hiero-ledger/hiero-sdk-java
    recent: 30d
    outsider_reports: true
```

Inputs: `command`, `repos`, `repos_file`, `labels`, `recent`, `outsider_reports`, `per_repo`,
`exclude_labels`, `reply_sample`, `window`, `format`, `output`, `cache_file`, `token`. See [`action.yml`](action.yml).

## How it decides

### Repository score (0 to 100)

From outsider pull requests merged or closed in the window:

| Part | Points | Full marks at |
|---|--:|---|
| Activity: outsider PRs merged | 40 | 10 or more |
| Breadth: different outsiders merged | 20 | 5 or more |
| Speed: median time from opening to merge | 25 | 3 days or less; 0 at 30 days |
| Acceptance: merged / (merged + closed unmerged) | 15 | 100% |

Every part is capped, so a huge project cannot win on volume alone. Breadth is there because ten
merges from one regular contributor say less about openness than five merges from five people.

### Is an issue claimed?

| Evidence | Verdict |
|---|---|
| A merged pull request references it | probably fixed |
| An open pull request references it | claimed |
| Someone is assigned | claimed |
| Someone asked to work on it in the last 30 days ("can I take this?", `/assign`, ...) | claimed |
| Someone asked earlier than that | unclaimed, shown as a stale claim |
| Pull requests that were closed without merging | unclaimed, shown as earlier attempts |

The claim phrases are matched narrowly, and quoted lines are ignored. Wrongly hiding a good issue
costs more than showing one that turns out to be taken: you will see that in the thread within
seconds.

## Design

```
core/   GitHub client, cache, health scoring, claim detection, finder, renderers. No framework.
cli/    picocli commands, packaged as one runnable jar.
action.yml, Dockerfile, action/entrypoint.sh   the GitHub Action.
```

Decisions worth knowing about:

- **Scoring and claim detection are pure functions.** They take records and a timestamp and do
  no I/O, so most tests are plain unit tests. HTTP behaviour is tested against WireMock and
  against JSON recorded from the real API (for example `apache/cloudstack#14709`); tests never
  touch the network.
- **Caching.** Every response is stored with its ETag in SQLite. The next run sends
  `If-None-Match`, and GitHub answers `304 Not Modified` without counting it against the rate
  limit. A second run over seven repositories used none of the hourly quota.
- **Rate limits.** When the quota runs out, the client waits for the reset and retries, but only
  up to `--max-wait` (2 minutes by default). Beyond that it fails with a clear message instead of
  silently hanging for an hour. `Retry-After` from secondary limits is honoured.
- **Concurrency.** Each repository and issue timeline is fetched on a virtual thread, with a
  semaphore capping requests in flight, because GitHub's secondary limits punish bursts. A
  failing repository is reported and the rest of the run continues.
- **Stable output.** The JSON document is built field by field instead of serialising the Java
  records, so a refactoring cannot silently change what scripts depend on.

## Limits

- **Hidden organisation membership.** GitHub reports people whose membership is private as
  `CONTRIBUTOR`, so in company-backed projects some employees count as outsiders. That inflates
  the score of projects like Keycloak. Read the score as an upper bound there.
- **Window coverage.** Health reads at most 300 recently updated closed pull requests. A `+`
  after the merged count means the limit was reached inside the window, so the counts are a
  lower bound.
- **Linked pull requests without a mention.** A pull request linked to an issue only through the
  "Development" sidebar shows up in the timeline without its number, so it is not detected unless
  it also mentions the issue (most do, with "Fixes #123").
- **Claim phrases are English only.**
- **Apache projects.** Apache committers get their rights from the ASF, not from GitHub, so
  GitHub labels most of them `CONTRIBUTOR` too. In CloudStack that means committers' own pull
  requests count as outsider ones, which is why its reply time looks fast: colleagues answer
  each other within the hour. For the same reason the reply metric counts a reply from anyone
  but the author, instead of trusting the label.
- **Bots that look like people.** Some bots are registered as ordinary users (CloudStack's
  `blueorangutan`, Kubernetes' `k8s-ci-robot`). merge-radar recognises login names with `bot`
  or `robot` as a word and a short list of known ones; others may slip through.

## Why I built this

I contribute to Apache CloudStack, Apache DataFusion Ballista, nanocoder and the Hiero Java SDK.
To find issues worth fixing, I used a few rough Python scripts that screened repositories and
issues. merge-radar is the tested, reusable version of that method. I wrote up the method itself
in [How to Tell If a Project Will Merge Your PR](https://blogs.gardazi.com/how-to-tell-if-a-project-will-merge-your-pr/).

## License

[Apache License 2.0](LICENSE)
