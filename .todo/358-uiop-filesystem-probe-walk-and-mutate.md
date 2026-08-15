# `uiop/filesystem`: probe, walk and mutate the tree

Difficulty: High

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **7 / 32 (`file-exists-p`, `native-namestring`, `directory-exists-p`, `directory-files`, `subdirectories`, `collect-sub*directories`, `delete-file-if-exists`)**.

Depends on `.todo/353`, `.todo/354` and `.todo/357` (most of this sub-package is
pathname algebra wrapped around one syscall).

32 externals; seven present (`file-exists-p`, `directory-exists-p`,
`directory-files`, `subdirectories`, `collect-sub*directories`,
`delete-file-if-exists`, `native-namestring`). The **25** missing:

```
CALL-WITH-CURRENT-DIRECTORY DELETE-DIRECTORY-TREE DELETE-EMPTY-DIRECTORY
DIRECTORY* ENSURE-ALL-DIRECTORIES-EXIST FILTER-LOGICAL-DIRECTORY-RESULTS
GETENV-ABSOLUTE-DIRECTORIES GETENV-ABSOLUTE-DIRECTORY GETENV-PATHNAME
GETENV-PATHNAMES GET-PATHNAME-DEFAULTS INTER-DIRECTORY-SEPARATOR
LISP-IMPLEMENTATION-DIRECTORY LISP-IMPLEMENTATION-PATHNAME-P
PARSE-NATIVE-NAMESTRING PROBE-FILE* RENAME-FILE-OVERWRITING-TARGET
RESOLVE-SYMLINKS RESOLVE-SYMLINKS* *RESOLVE-SYMLINKS* SAFE-FILE-WRITE-DATE
SPLIT-NATIVE-PATHNAMES-STRING TRUENAME* TRUENAMIZE WITH-CURRENT-DIRECTORY
```

`get-pathname-defaults` landed with `.todo/357` (2026-08-15): it is Lisp source
in `uiop-filesystem.lisp` reading `*default-pathname-defaults*`, and both the
old `""` Java built-in and the compile-path fold are gone. Strike it from the
list above when working this item.

## What the backends can and cannot do

Read-side (`probe-file*`, `truename*`, `directory*`, `safe-file-write-date`,
`filter-logical-directory-results`, the `getenv-*` family,
`parse-native-namestring`, `split-native-pathnames-string`) is reachable
everywhere: it sits on `probe-file` and the one `%list-directory` primitive
`.kb/directory-listing.md` describes. `safe-file-write-date` needs a
file-write-date primitive if there is none -- check before assuming, and if it
must be added, add it on all four rather than one.

Write-side is **blocked on `.todo/257`**: `ensure-all-directories-exist`,
`delete-empty-directory`, `delete-directory-tree` and
`rename-file-overwriting-target` all bottom out in `%make-directories` /
`%delete-file`, which are call-time errors on both WASM backends because the
WASI import set carries no mkdir and no unlink. Two honest options, and the item
must pick one rather than leave it implicit:

1. take `.todo/257` as part of this item (it is two preview1 imports plus the
   two component adapters, following the `fd_readdir` precedent), which makes
   the whole sub-package real on all four; or
2. land the read side now and let the write side signal the SAME
   `not-implemented-error` the underlying primitive already signals, with
   `.kb/uiop.md` naming `.todo/257` as the re-evaluation trigger.

Option 1 is the better long-term choice and the one to attempt first --
option 2 leaves a second sub-package waiting on the same two imports.

**`with-current-directory` / `call-with-current-directory`** inherit
`.todo/356`'s `chdir` decision (WASI has no cwd). Do not invent a second answer
here; if `chdir` is `not-implemented-error` on WASM, so is this, for the same
recorded reason.

**Symlinks** (`resolve-symlinks`, `resolve-symlinks*`, `*resolve-symlinks*`,
`truenamize`): if no backend resolves them, `*resolve-symlinks*` defaults to nil
and the functions are identity -- which is what upstream does on
implementations without the API. That is coverage, not a stub, and belongs in
`.kb/uiop.md` as such.

## Gate

`UiopCoverageTest` reports `uiop/filesystem 32/32`. `ci-spec.yaml`'s existing
`directory-listing-and-uiop-walkers` case grows `probe-file*`, `truename*`,
`directory*` and `ensure-all-directories-exist` so the four backends are
compared on the write side too.
