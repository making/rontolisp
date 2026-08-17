# Quicklisp-format dists (`ql:quickload`'s download half)

**The invariant**: `eval/DistClient` is the ONLY thing that downloads a system, and it
downloads from a LIST of Quicklisp-format distributions rather than from Quicklisp. Both
consumers -- the interpreter (`LispEvaluator.quickload`, over the per-evaluator
`distClient`) and the compile path (`LoadInliner.downloadQuicklisp`, over `Ctx.dists`) --
call `ensureAvailable(system)` and get back `.asd` directories to put on the ASDF search
path; everything after that is `.kb/asdf.md`'s business. A dist is a *format*, not a
vendor: distinfo (`name:`, `system-index-url:`, `release-index-url:`) -> `systems.txt`
(`project system-file system-name dep...`) + `releases.txt`
(`project url size md5 sha1 prefix file...`), which Quicklisp and Ultralisp both speak
byte for byte -- the client parses one shape and cares only about which URLs it reads it
from.

**Quicklisp only, by default; Ultralisp is opt-in** (2026-08-17). Three
channels, in the order a program should reach for them:

- `(ql-dist:install-dist NAME-OR-URL)` -- upstream's own spelling, so a portable script
  that installs Ultralisp runs here unchanged. Accepts a known name (`quicklisp`,
  `ultralisp`) or the URL of a distinfo, and `:prompt nil` / any other keyword option is
  accepted and ignored (nothing here prompts, `AsdfSystems.checkIgnoredLoadOptions`).
  Answers the dist NAME as a string; installing twice is a no-op that keeps the first
  position.
- `--dist ultralisp` (repeatable, comma-separated: `RontoLispCli.distSpecs`, and `--dist`
  is in `CliOptions.repeatableKeys` beside `-e`) and `RONTOLISP_DISTS`. These exist for the
  invocation with nowhere to put a form -- `rontolisp test SYSTEM` generates its own
  program, and a build must not have to edit the sources it compiles. Comma-separated
  rather than `File.pathSeparator`-joined like `--system-path`, because a URL contains
  the separator.
- `ql:update-dist NAME` -- deletes that dist's cached `systems.txt`/`releases.txt` (and
  clears the parsed copies) so the next lookup re-reads the distribution. The extracted
  releases are kept: a release directory is named after its version, so a newer one
  extracts beside the old.

**Search order is installation order, resolved PER SYSTEM.** `locate(name)` walks the
installed dists and the first whose `systems.txt` lists the name provides it;
`collectProjects` resolves every dependency the same way, independently, so a dependency
the requested system's own dist lacks comes from a later one. Quicklisp is installed
first UNLESS the spec list names it (`distNameOrNull` over each spec in the constructor),
which is the whole ordering API: `--dist ultralisp` = quicklisp then ultralisp,
`--dist ultralisp,quicklisp` = the other way. There is no `ql-dist:preference`; the reason
is that the list is short and an explicit order is auditable where a numeric preference
per dist is not.

**Index loading is lazy per dist, and that is what makes the default free.** `ensureIndex`
runs on the first lookup that REACHES a dist, so a second installed dist costs nothing
until the first comes up short -- and a program whose systems are all in Quicklisp never
fetches the Ultralisp distinfo at all (pinned:
`DistClientTest.theFirstDistListingASystemProvidesIt` asserts the ultralisp distinfo URL
was never requested).

**Identity and cache layout.** A dist's NAME is its cache directory, so name resolution
must not need the network: `distNameOrNull` answers a known name as written, maps a known
HOST (`dist.ultralisp.org`, `beta.quicklisp.org`, ...) to that dist's name, and otherwise
slugs host+path. `distinfoUrl` then canonicalizes a known dist to ONE URL, so
`http://dist.ultralisp.org/` (the front page's spelling, which serves the distinfo
directly) and `ultralisp` are one dist sharing one cache rather than two. Cache root:
`<base>/<dist>/` with `<base>` = `RONTOLISP_DIST_HOME` or `~/.rontolisp`, and
`RONTOLISP_QUICKLISP_HOME` still overrides the quicklisp dist's directory alone --
`~/.rontolisp/quicklisp/` is unchanged, so no existing cache is invalidated by the
multi-dist layout. **Both environment variables are read in `createDefault` and passed in
as overrides, never inside `homeFor`**: a client built with an explicit base (every test)
must not pick up the developer's cache.

**Compile path**: `LoadInliner.distDirective` matches a literal top-level
`ql-dist:install-dist` / `ql:update-dist` (qualified-name match through
`PackageRegistry.splitQualified`, like the `ql:quickload` matcher beside it), applies it to
`ctx.dists()` WHILE SPLICING -- the dists have to be configured before the `quickload`
forms below them download -- and consumes the form, leaving the dist name as a string
constant (the top-level-statement pass then drops it, `.kb/toplevel-statement-values.md`).
A computed argument is a hard error, and a NESTED occurrence is rejected by both compilers
in the same `case` as `REQUIRE`/`PROVIDE`/`ASDF_DEFSYSTEM` (`Jvm/WasmExprCompiler`): a
compiled program downloads nothing at run time, so a run-time install could only be a lie.

**Browser playground**: `Target_DistClient` (web profile, `src/web/java`) substitutes
`createDefault` so the JDK `HttpClient` is unreachable in the Web Image and every download
raises "not available in the browser playground". `install-dist` itself still succeeds
there -- it performs no I/O -- and the failure lands on the `quickload` that needs the
network, which is the same message it had before dists existed.

**Coverage**: `DistClientTest` (index parsing, extraction, caching, transitive deps, the
secondary-`NAME/SUB` fallback, plus the multi-dist group: second-dist-only system, first
dist wins + no index fetch behind it, explicit reordering, URL-vs-name identity, unknown
spec, `update-dist` refetch and its not-installed error), `LispEvaluatorQuicklispTest`
(the interpreter's `install-dist` + `quickload` and the "installed dists (quicklisp)"
error), `LoadInlinerTest.installDistIsConsumedAtCompileTimeAndTheQuickloadBelowItUsesTheDist`,
`RontoLispCliTest.distSpecsReadTheOptionThenTheEnvironment`. **No automated E2E hits the
real network** -- verified manually on all four backends (2026-08-17): `--dist
ultralisp,quicklisp` + `(ql:quickload "split-sequence")` takes the Ultralisp release
(`sharplispers-split-sequence-*`), and an in-program
`(ql-dist:install-dist "http://dist.ultralisp.org/" :prompt nil)` +
`(ql:quickload "circular-buffer")` -- a system ONLY Ultralisp has -- prints the same
answer on the interpreter, the JVM, Preview 1 and a component.

Docs: `guides/asdf-systems.md` ("Adding a dist (Ultralisp)"),
`reference/functions/ql-dist-install-dist.md`, `reference/functions/ql-update-dist.md`,
`reference/packages.md` (the `ql-dist` package).
