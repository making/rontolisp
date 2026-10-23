# Directory listing: `%list-directory` and the family above it

**Invariant: exactly ONE directory-listing call per backend, `%list-directory`; every user-facing
spelling is Lisp source over it (`LispPreludeLibrary`).** Adding a spelling means adding a prelude
entry, never touching a backend.

## The family
`%dir-namestring` (pathname -> DIRECTORY form), `%wild-match` (`*` any run, `?` one char, `**/` any
depth), `%pathname-typed-p`, `%directory-subdirs`, `%wild-dirs`, `%directory-in` -> `directory` ->
`uiop:directory-files`, `uiop:subdirectories`, `uiop:collect-sub*directories`.
`uiop:directory-exists-p` left the family on 2026-10-23: it is `probe-file`'s answer in
directory form, a stat rather than a listing. `pathname-directory` and `constantly` landed
with the family.

## Primitive contract
`(%list-directory "dir/")` -> `nil` when the path is not a readable directory, else `(t . names)`,
each name BARE with a trailing `/` when itself a directory.
- The leading `t` tells an empty directory from a missing one (`%directory-in` resolves the
  directory only when it is there; the Clojure front end's `File.list` answers nil vs `[]`).
- It never signals (as `probe-file`): a WASM trap is not catchable.
- Host order is kept; `directory` sorts with `string<` in shared Lisp.
- `.` and `..` never appear -- preview1 `fd_readdir` YIELDS them and the WASM P1 runtime drops
  them, else `collect-sub*directories` walks its own parent forever.
- An unreadable directory reads as absent, matching the WASM backends.
- **A link to a directory is marked `/` on the interpreter and the JVM only** (`isDirectory`
  follows it); `fd_readdir` and `read-directory` report the LINK's type, so both WASM backends
  list it bare. The Lisp above never trusts the mark for a link: `%directory-subdirs` and
  `%directory-in` ask an unmarked entry `%read-link`, and a link `%probe-file`'s kind answer.
  Fixing the WASM primitive instead would need a stat per link entry against the LISTED
  descriptor, which the component adapter's `path_open` / `path_filestat_get` (preopen fds
  only) cannot take.

| backend | how |
| --- | --- |
| interpreter | `SourceLoader.listDirectory` (default `null` -- why the playground answers rather than fails) |
| JVM | `_listDirectory` (`JvmIoRuntimeBuilder`), wired by `JvmListDirectoryCompiler`, gated on use |
| WASM Preview 1 | `_list_directory` (`WasmIoRuntimeBuilder`) over `path_open` + the `fd_readdir` import |
| WASM `--component` | same core runtime; `adapter.wat`'s `$fd_readdir` over wasi:filesystem `read-directory` |

## Traps
- **Adding a preview1 import** (the count is now 15, `.kb/read-load-streams.md`):
  `IMPORT_FUNC_COUNT` and `FUNC_START` rise so every emitted function index shifts; `--no-wasi`
  needs a matching trap stub; `adapter.wat` must export the name and `adapter-http-server-p1.wat`
  export it as a nonzero errno (a reader maps it to nil, a writer to its call-site
  error); append the type AFTER the
  last fixed type (`TYPE_FD_READDIR`, `TYPE_PATH_RENAME`, `IARR_TYPE_LAST` re-based) so no type index moves.
- `read-directory` is a `stream<directory-entry>` with its own `stream.read` /
  `stream.drop-readable`; the read carries `realloc`. Lowered element is 24 bytes:
  `descriptor-type` at 0, name pointer at 16, length at 20.
- The cookie is an ENTRY INDEX, not a resume token: WASI 0.3 restarts the stream at the directory
  start, so the adapter skips `cookie` entries and stamps `d_next` with the 1-based index.
- A short round does NOT mean exhausted -- preview1's `used < buflen` rule truncated component
  listings (1000 files -> 221). Only an EMPTY round ends the walk.
- The listing buffer must sit BELOW `HEAP_PTR`, because every lifted name allocates through
  `cabi_realloc`, which bumps that cell (`.kb/wasi-component.md`).
- `adapter.wat`'s `$path_open` must test the create/truncate bits, not `(i32.eqz oflags)`, or any
  non-zero `oflags` asks for write access.

## `directory` IS ANSI's `directory`
- `"d/*"` is a wild NAME with NO type (matches `sub` and `README`, not `a.txt`); `"d/*.*"` matches
  everything (`%pathname-typed-p`). A non-wild pathspec designates ITSELF (`%probe-truename`).
- **The pathspec is made absolute, then its `.` and `..` are taken out by SPELLING, before any
  listing** (2026-10-23): `(%real-path (%absolute-path spec) t)`, the walk with no link read. SBCL
  2.6.5 does the same in BOTH `:resolve-symlinks` modes -- `lk/../*.*` lists the directory holding
  `lk`, where `truename` of `lk/../x` follows `lk` first -- so the old `..`-as-spelled answer under
  nil and the link-following one under t were both wrong. A `..` after a wild component drops it
  too (`*/../a.txt` is `a.txt`, SBCL's answer when a subdirectory exists); SBCL's NIL for
  `**/../a.txt` and for `/../tmp/` is not copied.
- **The answers are TRUENAMES, deduplicated after the sort** (2026-10-23, SBCL's
  `:resolve-symlinks t`): an entry is MATCHED by its listed name, then the directory is
  resolved once (`%real-path`) and each entry asked `%read-link` -- a non-link is the resolved
  directory plus its name, a link goes through `%probe-truename` (a dangling one answers
  itself). So `./x` answers `<cwd>/x` (`x` where there is no working directory), and a link and
  its target come back once. One `%read-link`
  per entry is the cost (SBCL pays a realpath per entry). `:resolve-symlinks nil` keeps the
  matched spelling, a link asked `%probe-file` for its form, a dangling one DROPPED (SBCL
  2.6.5 drops it there and keeps it under `t`). The uiop walkers (`directory-files`,
  `subdirectories`, `directory*`) pass nil, as upstream's `directory*` does on SBCL.
- DIRECTORY components ARE wild: the pathspec splits at its last `/`, a wildcard prefix goes through
  `%wild-dirs`, and `%directory-in` runs in each, so wild and non-wild share IDENTICAL name matching.
  A `**` component contributes the base BEFORE descending (`:wild-inferiors` matches zero levels).
- `uiop:directory-files`' optional PATTERN is a wildcard NAMESTRING, not a pathname object; omitted
  it is `*wild-file-for-directory*`; a pattern with a DIRECTORY component is an error, as in UIOP.
- `translate-pathname` matches and substitutes COMPONENT-WISE as SBCL does; only the LITE
  edges there diverge (`.kb/pathnames.md`).

## `make-pathname` / `pathname-name` / `pathname-type`
`make-pathname` is prelude Lisp (no Java `Environment` entry), so all four backends run ONE
definition; `cli/CompileTimePathnameFolder` is the compile-time literal-shape half.
- `:defaults` defaults COMPONENT-WISE, not a merge: a supplied component REPLACES the defaults'
  one, an explicit `nil` means "no component". Trap: `merge-pathnames` silently drops the defaults'
  TYPE when only `:name` is supplied.
- The LAST dot separates the type; a dot at position 0 does not (`"d/.a"` = name `".a"`, no type).
  One rendering, `%pathname-split`, serves `pathname-name`, `pathname-type` AND `make-pathname`'s
  defaulting; `PathnameOps.components` is its Java twin. `pathname` is a DISTINCT type carrying its
  namestring (`.kb/pathnames.md`).

## Tests
`LispPreludeLibraryTest#thePrelude{MakePathname,PathnameSplit}AgreesWithPathnameOps`;
`LispEvaluatorTest#directoryMatchesPathnamesTheWayAnsiDoes` (an SBCL-checked table),
`#uiopDirectoryWalkersRunOverTheSamePrimitive`, `#directoryGoesThroughTheInstalledSourceLoader`,
`#wildPathnameComponentsBuildMatchTranslateAndWalk`;
`JvmLispCompilerTest#directoryMatchesPathnamesAndDrivesTheUiopWalkers`;
`WasmLispCompilerIntegrationTest#directoryListsEntriesOverFdReaddir`,
`#directoryListingResumesPastOneReaddirRound`, `#componentDirectoryListing[WithoutAPreopenAnswersNil]`,
`#wildDirectoryComponentsDriveTheRecursiveWalk`; links: `#truenameResolvesEverySymbolicLinkInThePath`
in the interpreter, JVM and WASM suites; ci-spec `directory-listing-and-uiop-walkers`,
`wild-pathnames`, `truename-resolves-symbolic-links`. Trees are built with `mkdir` in the CONTAINER -- kept harness-staged
even though both WASM backends can create directories since `.todo/257` (the walk pins
LISTING over a known tree, not creation). `wild-pathnames`' walk is the zero-level branch over a HARNESS-STAGED `./wpc-sub/`
(`testsupport/CorpusFixtures`, driven by `CiSpecE2eTest` and `JvmDeadMethodEliminationCorpusTest`, both through
`CorpusFixtures.stageAll` -- on 2026-10-09 a fixture staged by one driver only turned the other red): a corpus
walk is BOUNDED BY CONSTRUCTION, because a `**/` anchored at the run directory reads EVERYTHING
below the process CWD, and a filter fixes the assertion, not the work. The flat `./*.*` /
`uiop:directory-files "."` reads of the sibling case are ONE listing of the run directory and
deliberate. Both drivers now give the run directory to the run: `CiSpecE2eTest` runs every leg in
its `@TempDir` and `JvmDeadMethodEliminationCorpusTest` runs the program in a subprocess, one fresh directory
each. It used to run in process, so in the PROJECT ROOT, and the day something else deleted
`./wpc-sub/` between its two runs the walk answered `NIL` and the guard went red for a reason that
was nowhere near the shaker (`.kb/test-execution.md`, "A test that runs a program in the project
root"). That the answer is `NIL` rather than an error is this file's own contract: the primitive
is `File.list()` and a directory it cannot read is a directory that is not there.
