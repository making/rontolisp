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
| `uiop/package` | symbol and package surgery (the lookups, interning and `define-package` support real; the hot-upgrade movers signal) | 31 / 31 |
| `uiop/package-local-nicknames` | the package-local nickname API | 3 / 3 |
| `uiop/package*` | the three condition/type names `uiop/package` defines but does not export | 3 / 3 |
| [`uiop/utility`](uiop/utility.md) | the portable helpers (`strcat`, `split-string`, `if-let`, `not-implemented-error`) | 68 / 68 |
 | `uiop/version` | version comparison and the deprecation conditions | 15 / 15 |
| [`uiop/os`](uiop/os.md) | host identity, the environment, the working directory | 22 / 22 |
| [`uiop/pathname`](uiop/pathname.md) | the pathname algebra (`subpathname`, `parse-unix-namestring`, `enough-pathname`) | 50 / 50 |
| [`uiop/filesystem`](uiop/filesystem.md) | probe, walk and mutate the file system | 32 / 32 |
| `uiop/stream` | file contents, temporary files, encodings, the standard streams | 38 / 66 |
| [`uiop/image`](uiop/image.md) | exit, fatal conditions, the dump hooks, the command line | 30 / 30 |
| `uiop/launch-program` | asynchronous subprocesses | 0 / 19 |
| `uiop/run-program` | synchronous subprocesses | 0 / 7 |
| `uiop/lisp-build` | `compile-file*` and the deferred warnings | 1 / 44 |
| `uiop/configuration` | XDG paths and the configuration search | 0 / 38 |
 | `uiop/backward-driver` | the deprecated aliases (`coerce-pathname`, `version-compatible-p`) | 2 / 7 |

The full export list is checked in as
`src/main/resources/am/ik/rontolisp/uiop-exports.txt` (one row per export:
sub-package, symbol, and the definition form upstream gives it). It is the
target the counts above are measured against, so both move together.

## What is implemented

Five sub-packages have their own page, and all five are complete: `uiop/utility` — the
68 portable helpers everything else in uiop is written in
([uiop/utility](uiop/utility.md)) — `uiop/pathname`, the 50-member pathname
algebra ([uiop/pathname](uiop/pathname.md)), and `uiop/os`, the 22 host-identity,
environment and working-directory members ([uiop/os](uiop/os.md), which is where
[`uiop:getenv`](functions/uiop-getenv.md) lives). The fourth is
[uiop/image](uiop/image.md), complete as well: [`uiop:quit`](uiop/image.md#exiting)
ends the process with a status code on all four backends,
[`uiop:command-line-arguments`](uiop/image.md#the-command-line) reads the
arguments the program was started with on all four, and the fatal-condition,
backtrace and image-hook families live there too. The fifth is
[`uiop/filesystem`](uiop/filesystem.md), complete as well: `probe-file*`,
`truename*` and `directory*` probe and walk on all four backends, the
`getenv-*` family reads pathnames out of the environment, symlinks are the
honest identity, and the four mutating operations run where their primitives do
(signalling the primitive's own call-time error on both WASM backends). The rest:

| Function | Example | Result |
|----------|---------|--------|
| `uiop:read-file-string` | `(uiop:read-file-string "db/up.sql")` | the whole file as one string. Runs on every backend that can open a file for input. Lite: real UIOP's `&rest` keys are accepted and ignored (`:external-format` has no rontolisp surface — every backend reads UTF-8) |
| `uiop:read-file-lines`, `uiop:read-file-line`, `uiop:read-file-forms`, `uiop:read-file-form` | `(uiop:read-file-lines "db/seed.sql")` | the file as lines, one line (`:at`), forms, one form (`:at`) — each over `call-with-input-file` and its `slurp-stream-*` reader |
| `uiop:safe-read-file-line`, `uiop:safe-read-file-form`, `uiop:safe-read-from-string` | `(uiop:safe-read-from-string "(+ 1 2)")` | the safe-syntax readers: the file or string read under `with-safe-io-syntax` (`*read-eval*` nil). `safe-read-from-string` answers the object only |
| `uiop:with-input-file`, `uiop:call-with-input-file`, `uiop:with-output-file`, `uiop:call-with-output-file` | `(uiop:with-output-file (out "x.txt") (write-line "hi" out))` | open the file and run the body/thunk with the stream. Lite: `:element-type` defaults to `'character`, `:external-format` to `:utf-8`, `:if-exists` to `:supersede` |
| `uiop:with-input`, `uiop:input-string`, `uiop:with-output`, `uiop:output-string` | `(uiop:with-output (o nil) (write-string "x" o))` | coerce a stream designator (`nil`, `t`, a stream, a string, a pathname) to a stream. A `nil` output collects to a string; writing into a string signals |
| `uiop:copy-file`, `uiop:concatenate-files`, `uiop:copy-stream-to-stream` | `(uiop:copy-file "a" "b")` | copy a file, concatenate files, copy a stream — binary both ways, truncating the target. The `:linewise` copy always ends lines with a newline |
| `uiop:eval-input`, `uiop:eval-thunk`, `uiop:standard-eval-thunk` | `(uiop:eval-input "(+ 1 2) (* 3 4)")` | read and evaluate forms from a stream designator or string; the last form's values win |
| `uiop:println`, `uiop:writeln`, `uiop:format!`, `uiop:safe-format!`, `uiop:finish-outputs` | `(uiop:println "hi")` | print with a trailing newline (`println` over `princ`, `writeln` over `write`), `format` flushed before and after, and the flush itself. `safe-format!` never signals |
| `uiop:file-stream-p`, `uiop:file-or-synonym-stream-p` | `(uiop:file-stream-p s)` | exact kind tests over the stream value, recursing through synonym streams |
| `uiop:compile-file-type` | `(uiop:compile-file-type)` | `nil` — the pathname type a compiled file carries. There is no `compile-file` here, so there is no such type, and a caller asking "is this path a fasl?" gets `no` for a source path |
| `uiop:default-temporary-directory` | `(uiop:default-temporary-directory)` | `$TMPDIR` in directory form, or `#P"/tmp/"` when the environment is empty (both WASM backends without `--env`) |
| `uiop:add-package-local-nickname` | `(uiop:add-package-local-nickname '#:j '#:com.example.pkg)` | register a package shorthand (lite: global, no per-package scoping). A literal top-level call is a compile-time directive, so it works on every backend |
| `uiop:symbol-call` | `(uiop:symbol-call :cl :+ 1 2)` | look the name up in the package at run time and apply it — UIOP's late-binding call into a system the caller does not depend on |
| `uiop:find-package*` | `(uiop:find-package* :cl)` | the package, or `uiop:no-such-package-error` (nil for a missing package with a nil second argument) |
| `uiop:find-symbol*` | `(uiop:find-symbol* "CAR" :cl)` | the symbol and its status, like `find-symbol` (nil, nil for a missing name with no error) |
| `uiop:intern*` | `(uiop:intern* "NAME" :my-pkg)` | intern the stringified name (nil for a missing package with no error) |
| `uiop:export*`, `uiop:import*` | `(uiop:export* "NAME" :my-pkg)` | intern-then-export, and import — real where the registry can mutate (the compiled backends answer what CL's own runtime operators answer: arguments for effect plus `t`) |
| `uiop:make-symbol*` | `(uiop:make-symbol* "X")` | an uninterned symbol from a string, a copy from a symbol |
| `uiop:home-package-p` | `(uiop:home-package-p s p)` | whether the symbol's home package is the package |
| `uiop:symbol-package-name` | `(uiop:symbol-package-name 'car)` | `"CL"` (nil for an uninterned symbol) |
| `uiop:standard-common-lisp-symbol-p` | `(uiop:standard-common-lisp-symbol-p 'car)` | whether the symbol is an exported `cl` symbol |
| `uiop:symbol-shadowing-p` | `(uiop:symbol-shadowing-p s p)` | always nil — runtime shadowing does not exist here |
| `uiop:package-names` | `(uiop:package-names :cl)` | the name plus the nicknames |
| `uiop:packages-from-names` | `(uiop:packages-from-names '(:a :b))` | the packages the names denote, deduplicated with missing ones dropped |
| `uiop:fresh-package-name` | `(uiop:fresh-package-name)` | a package name no package has |
| `uiop:rename-package-away` | `(uiop:rename-package-away p)` | rename the package to a fresh name, wherever `rename-package` works |
| `uiop:package-definition-form` | `(uiop:package-definition-form :my-pkg)` | the `defpackage` form reproducing the declared members (nil for a missing package with `:error nil`) |
| `uiop:parse-define-package-form` | `(uiop:parse-define-package-form pkg clauses)` | the `ensure-package` arguments a `define-package` header parses to |
| `uiop:package-designator` | `(typep x 'uiop:package-designator)` | the designator type, and the datum reader of `uiop:no-such-package-error` |
| `uiop:no-such-package-error` | `(handler-case ... (uiop:no-such-package-error (c) ...))` | the `type-error` condition `find-package*` signals for a missing package |
| `uiop:define-package-style-warning` | — | the `style-warning` a package redefinition would signal |
| `uiop:package-local-nicknames` | `(uiop:package-local-nicknames :my-pkg)` | every global nickname pointing at the package (lite: nicknames are global, no per-package scoping) |
| `uiop:remove-package-local-nickname` | `(uiop:remove-package-local-nickname '#:nick)` | unregister the nickname (`t`) or answer nil; a literal top-level call works on every backend |

Eight members outside the complete sub-packages are **macros**, expanded by the
compiler rather than called: `uiop:with-temporary-file`,
[`uiop:with-deprecation`](macros/uiop-with-deprecation.md) and
`uiop:define-package` (a literal top-level call is consumed like `defpackage`).
The five `uiop/stream` ones — `uiop:with-input-file`, `uiop:with-output-file`,
`uiop:with-input`, `uiop:with-output` and `uiop:with-safe-io-syntax` — expand
over their `call-with-*` functions.
`uiop/pathname`'s two macros — `uiop:with-pathname-defaults` and
`uiop:with-enough-pathname` — are [on its page](uiop/pathname.md#relative-to-a-base).
`uiop/filesystem`'s macro — `uiop:with-current-directory` — is
[on its page](uiop/filesystem.md#the-working-directory).
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
