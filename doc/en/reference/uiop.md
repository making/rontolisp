# The uiop Package

`uiop` is ASDF's portability layer — the spelling implementation-independent
libraries already use for the operations Common Lisp never standardized: reading
an environment variable, probing a file, walking a directory, splitting a
string. It is **not part of Common Lisp**; reference its symbols with a
qualifier (`uiop:getenv`), never unqualified.

The coverage target is **uiop 3.3.7**, the release the built-in
[`ql:quickload`](../guides/asdf-systems.md#downloading-with-quickload) client
fetches. That release exports **429 symbols**, and rontolisp implements a subset
of them; the rest resolve and signal, so a library that merely *names* one in an
`(:import-from #:uiop)` clause still reads, compiles and runs.

## Sub-packages

Upstream's `uiop` is `uiop/driver`, a re-export of 15 sub-packages, and a
library may name either spelling — `lack-middleware-backtrace` writes
`(:import-from :uiop/image :print-condition-backtrace)`. rontolisp registers all
15, with each sub-package owning the members it defines and `uiop` importing
them, so **both spellings name the same symbol** rather than two functions with
one member name:

```lisp
(list (uiop:emptyp "") (uiop/utility:emptyp ""))   ; => (T T)
```

| Sub-package | What lives there | Implemented |
|-------------|------------------|-------------|
| `uiop/package` | symbol and package surgery (`find-symbol*`, `intern*`, `define-package`) | 4 / 31 |
| `uiop/package-local-nicknames` | the package-local nickname API | 1 / 3 |
| `uiop/package*` | the three condition/type names `uiop/package` defines but does not export | 0 / 3 |
| [`uiop/utility`](uiop/utility.md) | the portable helpers (`strcat`, `split-string`, `if-let`, `not-implemented-error`) | 68 / 68 |
| `uiop/version` | version comparison and the deprecation conditions | 1 / 15 |
| [`uiop/os`](uiop/os.md) | host identity, the environment, the working directory | 22 / 22 |
| [`uiop/pathname`](uiop/pathname.md) | the pathname algebra (`subpathname`, `parse-unix-namestring`, `enough-pathname`) | 50 / 50 |
| `uiop/filesystem` | probe, walk and mutate the file system | 8 / 32 |
| `uiop/stream` | file contents, temporary files, encodings, the standard streams | 3 / 66 |
| [`uiop/image`](uiop/image.md) | exit, fatal conditions, the dump hooks, the command line | 30 / 30 |
| `uiop/launch-program` | asynchronous subprocesses | 0 / 19 |
| `uiop/run-program` | synchronous subprocesses | 0 / 7 |
| `uiop/lisp-build` | `compile-file*` and the deferred warnings | 1 / 44 |
| `uiop/configuration` | XDG paths and the configuration search | 0 / 38 |
| `uiop/backward-driver` | the deprecated aliases | 0 / 7 |

The full export list is checked in as
`src/main/resources/am/ik/rontolisp/uiop-exports.txt` (one row per export:
sub-package, symbol, and the definition form upstream gives it). It is the
target the counts above are measured against, so both move together.

## What is implemented

Four sub-packages have their own page, and all four are complete: `uiop/utility` — the
68 portable helpers everything else in uiop is written in
([uiop/utility](uiop/utility.md)) — `uiop/pathname`, the 50-member pathname
algebra ([uiop/pathname](uiop/pathname.md)), and `uiop/os`, the 22 host-identity,
environment and working-directory members ([uiop/os](uiop/os.md), which is where
[`uiop:getenv`](functions/uiop-getenv.md) lives). The fourth is
[uiop/image](uiop/image.md), complete as well: [`uiop:quit`](uiop/image.md#exiting)
ends the process with a status code on all four backends,
[`uiop:command-line-arguments`](uiop/image.md#the-command-line) reads the
arguments the program was started with on all four, and the fatal-condition,
backtrace and image-hook families live there too. The rest:

| Function | Example | Result |
|----------|---------|--------|
| `uiop:file-exists-p` | `(uiop:file-exists-p "f.txt")` | the pathname when the file exists, `nil` otherwise — the same contract as `probe-file`, which it lowers onto on every backend |
| `uiop:directory-exists-p` | `(uiop:directory-exists-p "src/")` | the pathname (with a trailing `/`) when the DIRECTORY exists, `nil` otherwise — the directory twin of `file-exists-p`, and what tells an empty directory from a missing one |
| `uiop:directory-files` | `(uiop:directory-files "db/" "*.up.sql")` | the non-directory entries of a directory — `(directory "db/*.*")` with the subdirectories dropped. UIOP's optional second argument, the namestring of a name-and-type wildcard, filters them exactly as `directory` matches; omitting it lists everything, and a pattern carrying a directory component is an error |
| `uiop:subdirectories` | `(uiop:subdirectories "src/")` | the subdirectories of a directory, each with its trailing `/` |
| `uiop:collect-sub*directories` | `(uiop:collect-sub*directories "src/" (constantly t) (constantly t) #'print)` | walk a directory tree: `collectp` decides what reaches `collector`, `recursep` what is descended into. Every directory handed over is in directory form, root included |
| `uiop:read-file-string` | `(uiop:read-file-string "db/up.sql")` | the whole file as one string. Runs on every backend that can open a file for input. Lite: real UIOP's `&rest` keys are accepted and ignored (`:external-format` has no rontolisp surface — every backend reads UTF-8) |
| `uiop:compile-file-type` | `(uiop:compile-file-type)` | `nil` — the pathname type a compiled file carries. There is no `compile-file` here, so there is no such type, and a caller asking "is this path a fasl?" gets `no` for a source path |
| `uiop:default-temporary-directory` | `(uiop:default-temporary-directory)` | `$TMPDIR` in directory form, or `#P"/tmp/"` when the environment is empty (both WASM backends without `--env`) |
| `uiop:delete-file-if-exists` | `(uiop:delete-file-if-exists "scratch.txt")` | delete a file, answering `nil` instead of signalling when it is not there — the whole reason UIOP exports it |
| `uiop:get-pathname-defaults` | `(uiop:get-pathname-defaults)` | the defaults relative names resolve against — `*default-pathname-defaults*` (initially `#P""`, the pathname designating the host working directory) unless an absolute defaults argument is given |
| `uiop:native-namestring` | `(uiop:native-namestring #P"/tmp/x")` | `"/tmp/x"` — the host-OS spelling of a pathname, which here IS the namestring, so this is `namestring` |
| `uiop:add-package-local-nickname` | `(uiop:add-package-local-nickname '#:j '#:com.example.pkg)` | register a package shorthand (lite: global, no per-package scoping). A literal top-level call is a compile-time directive, so it works on every backend |
| `uiop:symbol-call` | `(uiop:symbol-call :cl :+ 1 2)` | look the name up in the package at run time and apply it — UIOP's late-binding call into a system the caller does not depend on |

Three members outside the complete sub-packages are **macros**, expanded by the
compiler rather than called: `uiop:with-temporary-file`,
[`uiop:with-deprecation`](macros/uiop-with-deprecation.md) and
`uiop:define-package` (a literal top-level call is consumed like `defpackage`).
`uiop/pathname`'s two macros — `uiop:with-pathname-defaults` and
`uiop:with-enough-pathname` — are [on its page](uiop/pathname.md#relative-to-a-base).
`uiop/utility`'s own macros — [`uiop:if-let`](macros/uiop-if-let.md),
`uiop:nest`, `uiop:while-collecting`, `uiop:with-upgradability` and the rest —
are [on its page](uiop/utility.md#macros).

## What is not

Every other export **resolves and signals `uiop:not-implemented-error`**, naming
the operation. That is the whole point of registering the inventory: a program
that reaches an unfilled corner of uiop gets one clear answer instead of an
`undefined function` from the middle of a library, and a handler can catch it:

```console
$ rontolisp -e '(uiop:run-program "ls")'
Unhandled condition: Not (currently) implemented on rontolisp: UIOP/RUN-PROGRAM:RUN-PROGRAM
```

```lisp
(handler-case (uiop:run-program "ls")
  (uiop:not-implemented-error () :cannot))   ; => :CANNOT
```

The behaviour is identical on all four backends — the interpreter, the JVM and
both WASM outputs signal the same condition with the same report.

## rontolisp extras

Two names live in `uiop` that upstream does not export there:

- `uiop:namestring` — upstream only *inherits* Common Lisp's; here it is
  exported and is the very [`namestring`](functions/namestring.md) function, so
  both spellings name one function.
- [`uiop:when-let`](macros/uiop-when-let.md) and
  [`uiop:when-let*`](macros/uiop-when-let-star.md) — alexandria's names, kept
  because programs already spell them. Real UIOP exports `if-let` only.
