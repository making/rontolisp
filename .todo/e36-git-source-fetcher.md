# e36. Git source fetcher (a repository at a pinned commit into the cache)

Difficulty: Medium

Needed by `deps.edn` `:git/url` coordinates (`e39`). Builds on `am.ik.artifact`
(`ArtifactCache` root, `AtomicInstall`; `.kb/dists.md`). Nothing in `src/main/java` starts a subprocess today (no `ProcessBuilder`).

## Scope

- Input: URL, full sha (required, as tools.deps), optional tag checked to point at that sha,
  optional sub-directory (`:deps/root`). Output: a read-only tree in the cache keyed by
  URL + sha; existence is the installed mark, written atomically like a dist release.
- Transport: decide between the `git` CLI (tools.deps' own choice since 0.10; works for any
  host, auth via the user's git config) and host archive endpoints (`/archive/<sha>.tar.gz`,
  GitHub/GitLab only, no git needed). Record the decision and the reason in `.kb`.
- Inferred URLs: `io.github.user/repo` -> `https://github.com/user/repo.git`,
  `io.gitlab...` likewise (the tools.deps rule).
- Refusals: browser (web profile), no `git` on PATH (if the CLI is chosen), a short sha, a
  tag/sha mismatch.

A CL consumer is not required; do not invent a CL surface for it here.

## Plan

1. Read `.kb/dists.md`, `.kb/architecture.md`, `.kb/native-output.md` (subprocess in the
   native CLI).
2. Tests against a local bare repository created in the test (no network).
3. `.kb` file for the fetcher + README row.
