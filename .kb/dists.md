# Quicklisp-format dists (`ql:quickload`'s download half)

**Invariant: `eval/DistClient` is the ONLY thing that downloads a system, and it downloads
from a LIST of Quicklisp-format distributions rather than from Quicklisp.**
`LispEvaluator.quickload` and `LoadInliner.downloadQuicklisp` (`Ctx.dists`) call
`ensureAvailable(system)` for `.asd` directories; the rest is `.kb/asdf.md`.

Format, not vendor: distinfo (`name:`, `system-index-url:`, `release-index-url:`) ->
`systems.txt` (`project system-file system-name dep...`) + `releases.txt`
(`project url size md5 sha1 prefix file...`). Quicklisp and Ultralisp speak it identically.

## Installing and ordering
- `(ql-dist:install-dist NAME-OR-URL)` — known name (`quicklisp`, `ultralisp`) or distinfo
  URL; keyword options ignored (`AsdfSystems.checkIgnoredLoadOptions`); answers the NAME;
  a second install is a no-op keeping the first position.
- `--dist ultralisp` / `RONTOLISP_DISTS` — repeatable, COMMA-separated (a URL contains
  `File.pathSeparator`); `RontoLispCli.distSpecs`, in `CliOptions.repeatableKeys`.
- `ql:update-dist NAME` drops that dist's cached indexes; extracted releases are kept.
- **Search order = installation order, resolved PER SYSTEM** (`locate`, `collectProjects`).
  Quicklisp installs first UNLESS the spec list names it (`distNameOrNull`) — the whole
  ordering API. No `ql-dist:preference`.

## What a release contributes
- **Only the `.asd` files its dist index NAMES** (`releases.txt`'s trailing `file...`
  column, dirs added by `collectAsdDirs`) — a whole-tarball walk let a vendored snapshot
  (`iterate-release-*/ext/alexandria/alexandria.asd`) compete for that system name.
- Per DIRECTORY, not per file (`AsdfSystems.locate` asks each dir for `NAME.asd`).
- FALLBACK: a release whose index names no existing `.asd` gets the whole-release walk.
- **Directories SORTED within a release** (`addAsdDirs`), projects in dependency order —
  otherwise one lockfile gave two developers different programs
  (`.kb/emitted-output-determinism.md`).

## Identity and cache
- `ensureIndex` runs on the first lookup that REACHES a dist.
- Name resolution must not need the network: `distNameOrNull` takes a known name as
  written, maps a known HOST (`dist.ultralisp.org`, `beta.quicklisp.org`, ...) to its
  name, else slugs host+path; `distinfoUrl` canonicalizes a known dist to ONE URL.
- Root `<base>/<dist>/`, `<base>` = `RONTOLISP_DIST_HOME` or `~/.rontolisp`;
  `RONTOLISP_QUICKLISP_HOME` overrides the quicklisp directory alone. **Both env vars are
  read in `createDefault` and passed in as overrides, never inside `homeFor`** — a client
  built with an explicit base (every test) must not pick up the developer's cache.

## Compile path and browser
- `LoadInliner.distDirective` matches a literal top-level `ql-dist:install-dist` /
  `ql:update-dist`, applies it to `ctx.dists()` WHILE SPLICING (dists must be configured
  before the `quickload` forms below them) and consumes the form. Computed argument =
  hard error; NESTED occurrences rejected by both compilers in the same `case` as
  `REQUIRE`/`PROVIDE`/`ASDF_DEFSYSTEM`.
- `Target_DistClient` (web profile) substitutes `createDefault`; downloads raise "not
  available in the browser playground", so the failure lands on the `quickload`.

## Tests and docs
`DistClientTest`, `LispEvaluatorQuicklispTest`,
`LoadInlinerTest.installDistIsConsumedAtCompileTimeAndTheQuickloadBelowItUsesTheDist`,
`RontoLispCliTest.distSpecsReadTheOptionThenTheEnvironment`. **No automated E2E hits the
real network**; the four-backend Ultralisp check is manual. Docs:
`guides/asdf-systems.md`, `reference/functions/ql-dist-install-dist.md`,
`reference/functions/ql-update-dist.md`, `reference/packages.md`.
