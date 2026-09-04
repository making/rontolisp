# Directory listing: `%list-directory` and the family above it

**Invariant: exactly ONE directory-listing call per backend, `%list-directory`; every user-facing spelling is Lisp source over it (`LispPreludeLibrary`).** Adding a spelling means adding a prelude entry, never touching a backend.

```
%list-directory  (per backend: interpreter / JVM / WASM P1 / WASM component)
  %dir-namestring        pathname -> DIRECTORY form (trailing /), the one rule
  %wild-match            glob (* any run, ? one char, **/ any depth)
  %pathname-typed-p      does the name carry a type (a dot past position 0)
  %directory-subdirs     the subdirectory entries of one prefix, sorted
    %wild-dirs           a wild directory prefix -> every directory it matches
  %directory-in          the entries of ONE prefix a name component matches
  directory              ANSI pathname MATCHING, sorted, prefixed
    uiop:directory-files       (directory "d/*.*") minus the subdirectories
    uiop:subdirectories        (directory "d/*.*") minus the files
      uiop:collect-sub*directories   the recursive walk
  uiop:directory-exists-p  "is it a readable directory", + its namestring
```

`pathname-directory` and `constantly` landed with the family. All names are ANSI except the four `uiop:` ones.

## Primitive contract
`(%list-directory "dir/")` -> `nil` when the path is not a readable directory (missing, a plain file, unreadable, no filesystem), else `(t . names)`, each name BARE with a trailing `/` when itself a directory.
- The leading `t` is load-bearing: `uiop:directory-exists-p` must tell empty from missing.
- It never signals (as `probe-file`): a WASM trap is not catchable, so walking an OPTIONAL tree would abort the program.
- Host order is kept; `directory` sorts with `string<` in shared Lisp, so all backends print the same listing.
- `.` and `..` never appear — preview1 `fd_readdir` YIELDS them, so the WASM P1 runtime drops them explicitly; otherwise `collect-sub*directories` walks its own parent forever.
- An unreadable directory reads as absent (`Files.list` needs read permission where `Files.isDirectory` does not), matching the WASM backends.

| backend | how |
| --- | --- |
| interpreter | `SourceLoader.listDirectory` (default `null`, `Files.list` in the filesystem loader) — the default is why the browser playground answers rather than fails |
| JVM | `_listDirectory` (`JvmIoRuntimeBuilder`), wired by `JvmListDirectoryCompiler`; gated to programs that call the primitive |
| WASM Preview 1 | `_list_directory` (`WasmIoRuntimeBuilder`) over `path_open` + the `fd_readdir` import |
| WASM `--component` | same core runtime; `adapter.wat`'s `$fd_readdir` over wasi:filesystem `read-directory` |

## Traps
- **Adding a preview1 import** (`fd_readdir` was the ninth; the count is 12 with `fd_prestat_get`/`fd_prestat_dir_name` and `fd_filestat_get` for `file-length`, `.kb/read-load-streams.md`): `IMPORT_FUNC_COUNT` and `FUNC_START` rise, so every emitted function index shifts; `--no-wasi` needs a matching trap stub; `adapter.wat` must export the name and `adapter-http-server-p1.wat` export it as errno 76 (no filesystem in the serve world, and `%list-directory` reads nonzero errno as `nil`); append the type AFTER the last fixed type (`TYPE_FD_READDIR`, `IARR_TYPE_LAST` re-based onto it) so no existing type index moves.
- `read-directory` is a `stream<directory-entry>`, structurally distinct from a byte stream: its own `stream.read` / `stream.drop-readable` built-ins, and the read carries the `realloc` option. Lowered element is 24 bytes: `descriptor-type` at 0, name pointer at 16, length at 20.
- The cookie is an ENTRY INDEX, not a resume token: WASI 0.3 restarts the stream at the directory start, so the adapter skips `cookie` entries and stamps `d_next` with the 1-based index.
- A short round does NOT mean exhausted. The adapter stops at the last WHOLE record, so preview1's `used < buflen` rule silently truncated component listings (1000 files -> 221). Only an EMPTY round ends the walk; a round decoding no complete entry bails.
- The listing buffer must sit BELOW `HEAP_PTR` (`_list_directory` advances it over its 8 KiB buffer + 512-byte name scratch and pops back), because every lifted name is allocated through `cabi_realloc`, which bumps that cell (`.kb/wasi-component.md`).
- `adapter.wat`'s `$path_open` must test the create/truncate bits, not `(i32.eqz oflags)`, or any non-zero `oflags` (the `directory` bit included) asks for write access.

## `directory` IS ANSI's `directory`
- `"d/*"` is a wild NAME with NO type: matches `sub` and `README`, not `a.txt`; `"d/*.*"` matches everything (`%pathname-typed-p`), and `"d/a*"` matches nothing when the only `a` entry is `a.txt`.
- A non-wild pathspec designates ITSELF: `"d"` and `"d/"` both answer `("d/")`.
- DIRECTORY components ARE wild: the pathspec splits at its last `/`, a wildcard prefix goes through `%wild-dirs`, and `%directory-in` runs in each so wild and non-wild share IDENTICAL name matching. A `**` component contributes the base itself BEFORE descending, so `:wild-inferiors` matches zero levels; any other wild component descends one level via `%wild-match` (no type rule — a directory has no type).
- `uiop:directory-files` takes UIOP's optional PATTERN as a wildcard NAMESTRING, not a pathname object: `(uiop:directory-files "db/" "*.up.sql")`; omitted it is `"*.*"` (`*wild-file-for-directory*`), and a pattern with a DIRECTORY component is an error as in real UIOP.
- Limit, from the pathname VALUE carrying a FLAT namestring (`.kb/pathnames.md`): `translate-pathname` substitutes captures POSITIONALLY, so an asymmetric wildcard pair diverges from SBCL.

## `make-pathname` / `pathname-name` / `pathname-type`
`make-pathname` is prelude Lisp (no Java `Environment` entry), so all four backends run ONE definition; `cli/CompileTimePathnameFolder` stays as the compile-time literal-shape half.
- `:defaults` defaults COMPONENT-WISE; it is not a merge. A supplied component REPLACES the defaults' one — `(make-pathname :directory '(:relative "m") :defaults "d/a.sql")` is `"m/b.sql"` — and an explicit `nil` means "no component". Trap: running name+type through `merge-pathnames` silently drops the defaults' TYPE when only `:name` is supplied.
- The LAST dot separates the type; a dot at position 0 does not. `"d/a.b.c"` = name `"a.b"` type `"c"`; `"d/.a"` = name `".a"`, no type. One rendering, `%pathname-split`, is read by `pathname-name`, `pathname-type` AND `make-pathname`'s defaulting; `PathnameOps.components` is its Java twin.
- `pathname` is a DISTINCT type carrying its namestring: `.kb/pathnames.md`.

## Tests
`LispPreludeLibraryTest#thePreludeMakePathnameAgreesWithPathnameOps`, `#thePreludePathnameSplitAgreesWithPathnameOps`; `LispEvaluatorTest#directoryMatchesPathnamesTheWayAnsiDoes` (an eleven-case SBCL-checked table), `#uiopDirectoryWalkersRunOverTheSamePrimitive`, `#directoryGoesThroughTheInstalledSourceLoader`, `#wildPathnameComponentsBuildMatchTranslateAndWalk` (seven shapes diffed against SBCL); `JvmLispCompilerTest#directoryMatchesPathnamesAndDrivesTheUiopWalkers`, `#wildPathnameComponentsBuildMatchTranslateAndWalk`; `WasmLispCompilerIntegrationTest#directoryListsEntriesOverFdReaddir`, `#directoryListingResumesPastOneReaddirRound`, `#componentDirectoryListing`, `#componentDirectoryListingWithoutAPreopenAnswersNil`, `#wildDirectoryComponentsDriveTheRecursiveWalk` + its component twin; ci-spec `directory-listing-and-uiop-walkers`, `wild-pathnames`. Trees are built with `mkdir` in the CONTAINER — neither WASM backend can create a directory, which is also why `wild-pathnames`' walk half is limited to the zero-level branch.
