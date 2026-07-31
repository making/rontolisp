# Directory listing: `%list-directory` and the family above it

**The invariant**: there is exactly ONE directory-listing call per backend,
`%list-directory`, and every user-facing spelling is Lisp source over it
(`LispPreludeLibrary`). Nothing about pattern handling, path prefixing, entry
kind or ordering is decided per backend, so the four cannot drift. Adding a
spelling means adding a prelude entry, never touching a backend.

```
%list-directory  (per backend: interpreter / JVM / WASM P1 / WASM component)
  %dir-namestring        pathname -> DIRECTORY form (trailing /), the one rule
  %wild-match            glob over ONE component (* any run, ? one char)
  %pathname-typed-p      does the name carry a type (a dot past position 0)
  directory              ANSI pathname MATCHING, sorted, prefixed
    uiop:directory-files       (directory "d/*.*") minus the subdirectories
    uiop:subdirectories        (directory "d/*.*") minus the files
      uiop:collect-sub*directories   the recursive walk
  uiop:directory-exists-p  "is it a readable directory", + its namestring
```

`pathname-directory` and `constantly` landed with the family (both ANSI CL, both
prelude Lisp) because the walk's callers need them; `pathname-directory` is pure
namestring work and reads nothing. Everything user-facing here is an ANSI name
except the four `uiop:` ones, which are ASDF's.

## The primitive's contract

`(%list-directory "dir/")` answers

- `nil` when the path is not a readable directory (missing, a plain file, an
  unreadable one, or a host with no filesystem), and
- `(t . names)` otherwise, where each name is the BARE entry name with a
  trailing `/` when it is itself a directory.

**The leading `t` is load-bearing**: an empty directory and a missing one would
otherwise both be `nil`, and `uiop:directory-exists-p` has to tell them apart. A
bare list cannot carry that distinction, so the cons is the cheapest thing that
can.

**It never signals.** Same rule as `probe-file`, for the same reason: a WASM trap
is not catchable, so a library that walks an OPTIONAL tree (local-time's
timezone repository) would abort the whole program instead of falling back.

**The host's order is kept** and `directory` sorts with `string<`. Sorting in the
shared Lisp is what makes the same program print the same listing on every
backend; sorting per backend would only mean four chances to disagree.

**`.` and `..` never appear.** `Files.list` omits them and wasi:filesystem's
`read-directory` omits them by contract, but a preview1 `fd_readdir` YIELDS them
-- so the WASM P1 runtime drops them explicitly. Keeping them would be a
one-backend divergence AND would make `collect-sub*directories` walk its own
parent forever.

## Per backend

| backend | how |
| --- | --- |
| interpreter | `SourceLoader.listDirectory` (default `null`, `Files.list` in the filesystem loader). The default is why the browser playground answers rather than fails |
| JVM | `_listDirectory` (`JvmIoRuntimeBuilder`, `File.list()` + per-entry `isDirectory`), wired by `JvmListDirectoryCompiler`. **Gated**: emitted only for a program that calls the primitive, so every artifact compiled without one keeps its bytes |
| WASM Preview 1 | `_list_directory` (`WasmIoRuntimeBuilder`) over `path_open` + the `fd_readdir` import |
| WASM `--component` | the same core runtime; `adapter.wat`'s `$fd_readdir` implements the preview1 shape over `wasi:filesystem`'s `read-directory` |

### `fd_readdir` is a NINTH preview1 import

`IMPORT_FUNC_COUNT` went 8 -> 9 and `FUNC_START` with it, so **every emitted WASM
function index shifted** -- that is inherent to extending the import surface, not
a regression. The three consequences to keep in step:

- `--no-wasi` defines a ninth trap stub (same type index as the import).
- `adapter.wat` exports `fd_readdir` (component mode).
- `adapter-http-server-p1.wat` exports it too, as errno 76 -- the serve world has
  no filesystem, and `%list-directory` reads a nonzero errno as `nil`.

The type is appended AFTER the last fixed type (`TYPE_FD_READDIR`, with
`IARR_TYPE_LAST` re-based onto it) so the conditional `--simd` / async / instance
blocks follow it and no existing type index moves.

### The component adapter's traps for the unwary

**`read-directory` is a `stream<directory-entry>`, not a byte stream.** It is a
structurally distinct stream type, so it needs its OWN `stream.read` /
`stream.drop-readable` built-ins, and the read carries the `realloc` option the
byte-stream read does not (each element owns a `string` name). The lowered
element is 24 bytes: the `descriptor-type` variant at 0, the name pointer at 16,
its length at 20.

**The cookie is an ENTRY INDEX, not a resume token.** WASI 0.3 hands back a
stream positioned at the start of the directory every time, so the adapter skips
`cookie` entries and stamps each emitted dirent's `d_next` with its 1-based
index.

**A short round does NOT mean the directory is exhausted.** A preview1 host fills
the caller's buffer and truncates the last entry, so `used < buflen` reads as
"that was the end" there. The adapter cannot truncate -- it stops at the last
record that fits WHOLE -- so that rule silently truncated the listing on the
component backend alone (1000 files came back as 221). Only an EMPTY round ends
the walk; a round that decoded no complete entry bails out so a pathological host
cannot spin the loop forever.

**The listing buffer must sit BELOW `HEAP_PTR`.** `_list_directory` advances
`HEAP_PTR` over its 8 KiB buffer + 512-byte name scratch for the duration of the
walk and pops back at the end. Under `--component` this is not tidiness: every
entry name the canonical ABI lifts is allocated through `cabi_realloc`, which
bumps that very cell (`.kb/wasi-component.md`), so an un-advanced buffer would be
overwritten by the names being read into it.

**A directory is opened READ, not write.** `adapter.wat`'s `$path_open` derived
its descriptor-flags from `(i32.eqz oflags)`, which made every non-zero `oflags`
-- including the `directory` bit -- ask for write access. Harmless until
something opened a directory; now it tests the create/truncate bits instead.

## `directory` IS ANSI's `directory`

`.todo/221` listed globbing as a non-goal and the first cut took it literally --
`(directory "src/")` listed the directory, which is not what CL means. That was
reverted: a non-goal is the todo author's scope, not a licence to diverge from
the standard, and matching is what the operator IS. The wild-component matcher is
~15 lines of prelude Lisp, and the eleven-case expectation table in
`LispEvaluatorTest#directoryMatchesPathnamesTheWayAnsiDoes` was checked
**against SBCL on the same tree** -- all eleven agree, including the two that
only fall out of the type rule:

- `"d/*"` is a wild NAME with NO type, so it matches `sub` and `README` but not
  `a.txt`; `"d/*.*"` is the one that matches everything (`%pathname-typed-p`).
- `"d/a*"` therefore matches nothing when the only `a` entry is `a.txt`.
- A non-wild pathspec designates ITSELF: `"d"` and `"d/"` both answer `("d/")`,
  a file answers itself, a missing name answers nil.

Two limits remain, and both are consequences of "a rontolisp pathname IS its
namestring", not of this operator:

**DIRECTORY components are never wild** (`"src/*/f.lisp"` matches nothing) --
there is no structured directory list to walk.

**`uiop:directory-files` offers only the 1-argument shape.** Real UIOP's second
argument is a wildcard pathname object.

**An unreadable directory reads as absent.** `Files.list` needs read permission
where `Files.isDirectory` does not, so a directory you may stat but not read
answers `nil`. Accepted: it is the same answer the WASM backends give for it, and
the alternative is a second primitive whose only job is the difference.

## Coverage

- `LispEvaluatorTest#directoryMatchesPathnamesTheWayAnsiDoes` (the SBCL-checked
  table), `#uiopDirectoryWalkersRunOverTheSamePrimitive`,
  `#directoryGoesThroughTheInstalledSourceLoader`
- `JvmLispCompilerTest#directoryMatchesPathnamesAndDrivesTheUiopWalkers`
- `WasmLispCompilerIntegrationTest#directoryListsEntriesOverFdReaddir`,
  `#directoryListingResumesPastOneReaddirRound` (400 files = several rounds),
  `#componentDirectoryListing`,
  `#componentDirectoryListingWithoutAPreopenAnswersNil`
- the `directory-listing-and-uiop-walkers` ci-spec case (all four backends)
- the driver that asked for it: `(ql:quickload "local-time")` +
  `reread-timezone-repository` + `find-timezone-by-location-name`, verified by
  hand on all four backends (`.kb/asdf.md`)
