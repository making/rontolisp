# Quicklisp-format dists (`ql:quickload`'s download half)

**Invariant: `eval/DistClient` is the ONLY thing that downloads a system, and it downloads
from a LIST of Quicklisp-format distributions rather than from Quicklisp.** Consumers
`LispEvaluator.quickload` (per-evaluator `distClient`) and `LoadInliner.downloadQuicklisp`
(`Ctx.dists`) call `ensureAvailable(system)` and get `.asd` directories for the ASDF search
path; the rest is `.kb/asdf.md`.

Format, not vendor: distinfo (`name:`, `system-index-url:`, `release-index-url:`) ->
`systems.txt` (`project system-file system-name dep...`) + `releases.txt`
(`project url size md5 sha1 prefix file...`). Quicklisp and Ultralisp speak it identically.

## Installing (Quicklisp by default; Ultralisp opt-in)

- `(ql-dist:install-dist NAME-OR-URL)` -- known name (`quicklisp`, `ultralisp`) or distinfo
  URL. `:prompt nil` and any keyword option accepted and ignored
  (`AsdfSystems.checkIgnoredLoadOptions`). Answers the NAME; a second install is a no-op
  keeping the first position.
- `--dist ultralisp` / `RONTOLISP_DISTS` -- repeatable, COMMA-separated (a URL contains
  `File.pathSeparator`); `RontoLispCli.distSpecs`, in `CliOptions.repeatableKeys`.
- `ql:update-dist NAME` -- drops that dist's cached and parsed `systems.txt`/`releases.txt`.
  Extracted releases are kept (directory named after the version).

## Search order = installation order, resolved PER SYSTEM

`locate(name)`: first installed dist whose `systems.txt` lists it wins; `collectProjects`
resolves each dependency independently. Quicklisp installs first UNLESS the spec list names
it (`distNameOrNull` per spec in the constructor) -- the whole ordering API. No
`ql-dist:preference`.

## What a release contributes

- **The `.asd` files its dist index NAMES**, not every `.asd` in the tarball:
  `releases.txt`'s trailing `file...` column, whose directories `collectAsdDirs` adds. The
  whole-tarball walk instead let a release's VENDORED snapshot of another library
  (`iterate-release-*/ext/alexandria/alexandria.asd`, `cffi-*/uffi-compat/uffi.asd`) compete
  for that system name, making the answer depend on quickload order.
- Per DIRECTORY, not per file (`AsdfSystems.locate` asks each dir for `NAME.asd`), so an
  unindexed extra `.asd` beside a named one stays reachable.
- FALLBACK: a release whose index names no existing `.asd` gets the whole-release walk.
- **Directories are SORTED within a release** (`addAsdDirs`, path order); projects in
  dependency order. Unsorted, `foo.asd` beside `test/foo.asd` gave two developers two
  different compiled programs from one lockfile. See
  `.kb/emitted-output-determinism.md`.

## Laziness, identity, cache

- `ensureIndex` runs on the first lookup that REACHES a dist, so a second dist costs
  nothing until the first comes up short.
- Name resolution must not need the network: `distNameOrNull` takes a known name as
  written, maps a known HOST (`dist.ultralisp.org`, `beta.quicklisp.org`, ...) to its name,
  else slugs host+path. `distinfoUrl` canonicalizes a known dist to ONE URL, so
  `http://dist.ultralisp.org/` and `ultralisp` share one cache.
- Root `<base>/<dist>/`; `<base>` = `RONTOLISP_DIST_HOME` or `~/.rontolisp`.
  `RONTOLISP_QUICKLISP_HOME` still overrides the quicklisp directory alone.
- **Both env vars are read in `createDefault` and passed in as overrides, never inside
  `homeFor`** -- a client built with an explicit base (every test) must not pick up the
  developer's cache.

## Compile path and browser

- `LoadInliner.distDirective` matches a literal top-level `ql-dist:install-dist` /
  `ql:update-dist` (via `PackageRegistry.splitQualified`), applies it to `ctx.dists()` WHILE
  SPLICING (dists must be configured before the `quickload` forms below them) and consumes
  the form, leaving the name as a string constant (dropped later,
  `.kb/toplevel-statement-values.md`). Computed argument = hard error; NESTED occurrences
  are rejected by both compilers in the same `case` as `REQUIRE`/`PROVIDE`/`ASDF_DEFSYSTEM`.
- `Target_DistClient` (web profile, `src/web/java`) substitutes `createDefault`; downloads
  raise "not available in the browser playground". `install-dist` still succeeds (no I/O),
  so the failure lands on the `quickload`.

## Coverage

`DistClientTest` -- parsing, extraction, caching, transitive deps, the secondary-`NAME/SUB`
fallback, the multi-dist group, and:
`theFirstDistListingASystemProvidesIt` (asserts the ultralisp distinfo URL was never
requested), `theAsdDirectoriesOfAReleaseAreSortedWhateverOrderTheHostWalkedThemIn`,
`aReleaseDefiningOneSystemTwiceResolvesToItsTopLevelAsd`,
`aVendoredExtCopyOfAnotherLibraryNeverReachesTheSearchPath`,
`aVendoredCopyDoesNotShadowThatLibrarysOwnReleaseInEitherQuickloadOrder`,
`aReleaseWhoseIndexNamesNoSystemFileFallsBackToTheWholeReleaseWalk`.
Also `LispEvaluatorQuicklispTest`,
`LoadInlinerTest.installDistIsConsumedAtCompileTimeAndTheQuickloadBelowItUsesTheDist`,
`RontoLispCliTest.distSpecsReadTheOptionThenTheEnvironment`. **No automated E2E hits the
real network**; the four-backend Ultralisp check is manual.

Docs: `guides/asdf-systems.md`, `reference/functions/ql-dist-install-dist.md`,
`reference/functions/ql-update-dist.md`, `reference/packages.md`.
