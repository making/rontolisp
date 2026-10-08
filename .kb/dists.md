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

- **Installs are atomic, because existence IS the "installed" mark** (`ensureProject`
  reuses any `software/<prefix>/` directory): a tarball extracts into a private
  `software/.staging-*` dir and the finished prefix dir is RENAMED into place;
  a failed or crashed extraction leaves no `<prefix>/` (a stale staging dir at worst,
  ignored), and losing the rename to another installer means using the winner's tree. The
  indexes are written temp + `ATOMIC_MOVE`, `releases.txt` before `systems.txt`. Before
  this, a truncated download or a second process quickloading at once (a concurrent
  `ClPostgresE2eTest` on a cold cache) saw a partial tree and kept it forever.
  Pinned by `DistClientTest.aFailedExtractionLeavesNoReleaseBehind...` /
  `aReleaseAnotherInstallerFinishedFirstIsUsedAsItIs`. A partial tree written by an older
  build is not detected; delete it by hand.

## The shared artifact layer (`am.ik.artifact`)
Language-independent (no rontolisp import, `PackageCycleTest`); DistClient is its first
consumer, the Maven resolver (`.kb/maven-resolver.md`) and the git fetcher build on it.
`ArtifactCache` (root = `RONTOLISP_DIST_HOME` / `~/.rontolisp`, read only in
`createDefault`; `area(name)` per consumer; `download(url, size, Checksum...)`),
`HttpDownloader` (a non-200 status is an `HttpStatusException`, `isNotFound()` for 404, so a
consumer searching several repositories tells "not here" from a failure), `Checksum`,
`Archives` (tar.gz incl. GNU `L` and PAX `path`; zip; `safeResolve`), `AtomicInstall`
(`installDirectory` via `.staging-*` + rename, `writeFile`). Layer messages name no
caller; DistClient prefixes `ql:quickload:` (`quickloadStep`).
- **Verification: size + `file-md5`, never the `sha1` column.** Measured 2026-10-08: on
  Quicklisp (alexandria, split-sequence) and Ultralisp (4 releases) the archive's size
  and MD5 match the index; the archive's SHA-1 never does. Quicklisp's `content-sha1` is
  the SHA-1 of every regular file's bytes concatenated in sorted path order; Ultralisp's
  matches neither that nor a name+content variant. Checked before extraction, so a
  refused download installs nothing (`DistClientTest.aTruncatedDownload...`,
  `anArchiveWhoseBytesDoNotMatchTheIndexedMd5...`, `...IndexedMd5IsMalformed...`).
- **Timeouts**: connect 30 s, IDLE 60 s (no byte, headers or body; a slow large body is
  fine) -- `sendAsync` + a counting body subscriber, cancelled on a silent slice
  (`HttpDownloaderTest`).
- **zip**: refused without an end-of-central-directory record -- `ZipInputStream` reads a
  zip cut at an entry boundary as a complete shorter one (`ArchivesTest`).

## Compile path and browser
- `LoadInliner.distDirective` matches a literal top-level `ql-dist:install-dist` /
  `ql:update-dist`, applies it to `ctx.dists()` WHILE SPLICING (dists must be configured
  before the `quickload` forms below them) and consumes the form. Computed argument =
  hard error; NESTED occurrences rejected by both compilers in the same `case` as
  `REQUIRE`/`PROVIDE`/`ASDF_DEFSYSTEM`.
- Web profile: `Target_HttpDownloader` substitutes `get(String, HttpAccess)` (the only path to
  `HttpClient` and `ProxyTunnel`)
  and refuses every download; the consumer's prefix makes it land on its own call site
  (`ql:quickload: downloading ... is not available in the browser playground`).
  `Target_Checksum` keeps the JCA `MessageDigest` lookup out of the image (why
  `Checksum` validates digest length from a table, not `MessageDigest`).

## Tests and docs
`DistClientTest` (fixtures write `{sums}`, filled by `DistTestSupport` with the served
tarball's size and MD5), `LispEvaluatorQuicklispTest`,
`LoadInlinerTest.installDistIsConsumedAtCompileTimeAndTheQuickloadBelowItUsesTheDist`,
`RontoLispCliTest.distSpecsReadTheOptionThenTheEnvironment`. **No automated E2E hits the
real network**; the four-backend Ultralisp check is manual. Docs:
`guides/asdf-systems.md`, `reference/functions/ql-dist-install-dist.md`,
`reference/functions/ql-update-dist.md`, `reference/packages.md`.
