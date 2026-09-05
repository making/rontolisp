# Directory listing: `%list-directory` and the family above it

**Invariant: exactly ONE directory-listing call per backend, `%list-directory`; every user-facing
spelling is Lisp source over it (`LispPreludeLibrary`).** Adding a spelling means adding a prelude
entry, never touching a backend.

## The family
`%dir-namestring` (pathname -> DIRECTORY form), `%wild-match` (`*` any run, `?` one char, `**/` any
depth), `%pathname-typed-p`, `%directory-subdirs`, `%wild-dirs`, `%directory-in` -> `directory` ->
`uiop:directory-files`, `uiop:subdirectories`, `uiop:collect-sub*directories`,
`uiop:directory-exists-p`. `pathname-directory` and `constantly` landed with the family.

## Primitive contract
`(%list-directory "dir/")` -> `nil` when the path is not a readable directory, else `(t . names)`,
each name BARE with a trailing `/` when itself a directory.
- The leading `t` is load-bearing: `uiop:directory-exists-p` must tell empty from missing.
- It never signals (as `probe-file`): a WASM trap is not catchable.
- Host order is kept; `directory` sorts with `string<` in shared Lisp.
- `.` and `..` never appear -- preview1 `fd_readdir` YIELDS them and the WASM P1 runtime drops
  them, else `collect-sub*directories` walks its own parent forever.
- An unreadable directory reads as absent, matching the WASM backends.

| backend | how |
| --- | --- |
| interpreter | `SourceLoader.listDirectory` (default `null` -- why the playground answers rather than fails) |
| JVM | `_listDirectory` (`JvmIoRuntimeBuilder`), wired by `JvmListDirectoryCompiler`, gated on use |
| WASM Preview 1 | `_list_directory` (`WasmIoRuntimeBuilder`) over `path_open` + the `fd_readdir` import |
| WASM `--component` | same core runtime; `adapter.wat`'s `$fd_readdir` over wasi:filesystem `read-directory` |

## Traps
- **Adding a preview1 import** (the count is now 12, `.kb/read-load-streams.md`):
  `IMPORT_FUNC_COUNT` and `FUNC_START` rise so every emitted function index shifts; `--no-wasi`
  needs a matching trap stub; `adapter.wat` must export the name and `adapter-http-server-p1.wat`
  export it as errno 76 (`%list-directory` reads nonzero errno as `nil`); append the type AFTER the
  last fixed type (`TYPE_FD_READDIR`, `IARR_TYPE_LAST` re-based) so no type index moves.
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
  everything (`%pathname-typed-p`). A non-wild pathspec designates ITSELF.
- DIRECTORY components ARE wild: the pathspec splits at its last `/`, a wildcard prefix goes through
  `%wild-dirs`, and `%directory-in` runs in each, so wild and non-wild share IDENTICAL name matching.
  A `**` component contributes the base BEFORE descending (`:wild-inferiors` matches zero levels).
- `uiop:directory-files`' optional PATTERN is a wildcard NAMESTRING, not a pathname object; omitted
  it is `*wild-file-for-directory*`; a pattern with a DIRECTORY component is an error, as in UIOP.
- Limit: `translate-pathname` substitutes captures POSITIONALLY, so an asymmetric wildcard pair
  diverges from SBCL (`.kb/pathnames.md`).

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
`#wildDirectoryComponentsDriveTheRecursiveWalk`; ci-spec `directory-listing-and-uiop-walkers`,
`wild-pathnames`. Trees are built with `mkdir` in the CONTAINER -- neither WASM backend can create
a directory, which is why `wild-pathnames`' walk half is limited to the zero-level branch.
