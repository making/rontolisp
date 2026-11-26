# Git fetcher (`am.ik.artifact.GitFetcher`)

A repository at a pinned commit, checked out into the cache, and what a dependency resolver
asks of a repository. Consumer: `eval/ClojureDepsRepositories` (`deps.edn` `:git/url`,
`.kb/clojure-frontend.md` "deps.edn"); no CL surface.

## Input
`GitCoordinate(url, sha, tag, root)` (builder). Malformed input is an
`IllegalArgumentException` at construction, before any process starts:
- `sha`: FULL, 40 hex (or 64, SHA-256 repositories), lower-cased. A short sha is refused,
  as tools.deps refuses it.
- `url`/`tag` starting with `-` (git would read it as an option: `--upload-pack=...`), a
  tag with `..`, `~^:\` or whitespace.
- `root` (`:deps/root`): normalized (`./a//b/` -> `a/b`, `./` -> none); absolute or `..`
  refused. A root that is no directory of the checkout (or whose real path leaves it) is an
  `IOException` at fetch.

Inferring a URL from an `io.github.user/repo` lib name is the resolver's, not the fetcher's:
the oracle's regex table lives in `clojure/ClojureDepsProcurer`. A `GitFetcher.inferUrl` with
an older table (bare `github.`, no codeberg) was removed 2026-10-08: no consumer, and a second
table that disagreed with the oracle's.

## The resolver's questions (`e39`)
Each holds the repository lock and clones first when the cache has none; a revision or tag
starting with `-` or spelling revision syntax (`..`, `~^:\`) is refused before git runs.
- `resolve(url, rev)`: the full sha a tag, branch or (abbreviated) sha names --
  `rev-parse --verify --quiet <rev>^{commit}`, then once after `fetchRefs`; `null` for none
  (an abbreviation two commits share names none).
- `hasTag(url, tag)`: `refs/tags/<tag>` in the clone, else once after `fetchRefs`. The oracle
  fetches tags on every resolution; here only on a miss, so a second run needs no network.
- `descendant(url, x, y)`: both commits present (one fetch otherwise, `null` if still
  absent), then `merge-base --is-ancestor` both ways (exit 0 yes, 1 no, else `IOException`);
  `null` for unrelated commits.
- `checkout(url, sha)`: `fetch` without tag or root, `null` (nothing installed) when the
  repository has no such commit -- the resolver's `Commit not found` is its own refusal.

## Transport: the `git` CLI, not forge archive endpoints
Decided 2026-10-08. The git CLI (tools.deps' own choice) works for any host and transport,
private repositories through the user's credential helpers / ssh-agent, and answers both
questions the coordinate asks -- does this commit exist, does this tag name it -- locally.
`/archive/<sha>.tar.gz` would need no git but covers GitHub/GitLab only, has no digest
to verify the bytes against (the sha names a commit, not an archive), resolves a tag only
through a rate-limited forge API, and applies `export-ignore` (a checkout does not). The
cost: `git` must be on `PATH` for a fetch that installs or checks a tag.

## Layout and mechanics (`<root>/gitlibs/`)
- `repos/<key>/` -- a bare clone (`clone --bare`, installed through `AtomicInstall`, so a
  failed clone leaves none). `repos/<key>.lock` -- every clone/fetch/check/checkout of that
  repository holds it: a `FileLock` across processes plus one monitor per lock path in the
  JVM (a JVM may not hold two `FileLock`s on one file).
- `libs/<key>/<sha>/` -- the committed tree, no `.git`; existence is the installed mark
  (`AtomicInstall.installDirectory`). Written by `read-tree` + `checkout-index --all`
  through a private `GIT_INDEX_FILE` in the staging dir, with `core.autocrlf=false`, so
  the clone's own state is never touched and the bytes are the committed ones.
- Key = identity `host[:port]/path` (scheme, user, trailing `/` and `.git` dropped; host
  lower-cased) as one readable segment (`[A-Za-z0-9.-]`, else `_`, 80 chars) + `-` + 16 hex
  of its FNV-1a 64. One segment: nested `host/path` dirs would put a GitLab subgroup's
  project inside its parent's clone. `https://github.com/a/b.git` and `git@github.com:a/b`
  share a clone. FNV, not SHA-256: `MessageDigest` is what `Target_Checksum` keeps out of
  the web image.
- Order: no tag and `libs/<key>/<sha>` exists -> answered with NO git run (offline, no git
  needed). Otherwise, under the lock: clone if absent; commit absent (`cat-file -e
  <sha>^{commit}`) -> `fetch <url> +refs/heads/*:refs/heads/* +refs/tags/*:refs/tags/*`,
  then `fetch <url> <sha>:refs/rontolisp/<sha>` (a commit no ref names, where the server
  allows it; the ref keeps it from `gc`); a tag is checked EVERY time
  (`rev-parse refs/tags/<tag>^{commit}`), fetching only when the clone's tag is missing or
  names another commit (created or moved upstream). Fetches name the requested URL, never
  the clone's stored remote.

## Every git run (`GitCommand`, the package's one `ProcessBuilder`)
stdin closed; `GIT_TERMINAL_PROMPT=0` unless the user set it (tools.deps' gitlibs default:
a missing credential fails instead of waiting on an invisible prompt); `GIT_DIR`,
`GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_OBJECT_DIRECTORY`, ... removed (a run from inside
a git hook); `-c protocol.ext.allow=never`. No timeout of its own.

## Refusals
Short sha, option-like url/tag, escaping root (above); `the git command 'git' cannot be
run (is git installed and on PATH?): ...`; `commit <sha> not found in <url>` (nothing
installed); `tag <t> not found in <url>`; `tag <t> names commit <x>, not <sha>, in <url>`.
Browser: `Target_GitCommand` substitutes `exec` -> `running git is not available in the
browser playground ...`; the consumer prefixes its operation, as with `HttpDownloader`.

Not done: submodules (tools.deps' gitlibs does not either), git LFS beyond the user's own
smudge filter config.

## Tests
`GitFetcherTest`: repositories made on disk by the test, `file://` URLs, no network --
checkout without metadata, root, offline reuse with no git, later commit, missing commit
installs nothing, tag accepted / created later / moved / mismatched / unknown, no git, four
concurrent fetches, input refusals, keys; `resolve` (tags, abbreviations, after the clone),
`hasTag`, `descendant` (both orders, unrelated, unknown), refused revisions.
`ClojureDepsFetchCliTest` runs it under a `deps.edn` project.
