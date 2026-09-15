# `uiop/lisp-build`: `compile-file*`, the muffled conditions and the deferred warnings

Difficulty: Medium

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **1 / 44 rows in the inventory (this file's "48 portable externals" was a
miscount)** -- `compile-file-type`, which answers nil (there is no
`compile-file` here, so there is no compiled-file type) and landed with
`.todo/375` because rove's `resolve-file` asks it on every `deftest`.

Depends on `.todo/353`, `.todo/354`, `.todo/357`, `.todo/359`. The last of the
twelve, and the one whose subject rontolisp mostly does not have.

48 portable externals (`sb-grovel-unknown-constant-condition` is `#+sbcl` and
excluded), one present (`compile-file-type`):

```
COMPILE-FILE* COMPILE-FILE-PATHNAME* COMPILE-FILE-TYPE LISPIZE-PATHNAME
LOAD* LOAD-FROM-STRING CURRENT-LISP-FILE-PATHNAME LOAD-PATHNAME
*BASE-BUILD-DIRECTORY* COMBINE-FASLS
*COMPILE-FILE-FAILURE-BEHAVIOUR* *COMPILE-FILE-WARNINGS-BEHAVIOUR* *COMPILE-CHECK*
COMPILE-CONDITION COMPILE-FILE-ERROR COMPILE-FAILED-ERROR COMPILE-FAILED-WARNING
COMPILE-WARNED-ERROR COMPILE-WARNED-WARNING
CHECK-LISP-COMPILE-RESULTS CHECK-LISP-COMPILE-WARNINGS
CALL-WITH-MUFFLED-COMPILER-CONDITIONS WITH-MUFFLED-COMPILER-CONDITIONS
CALL-WITH-MUFFLED-LOADER-CONDITIONS WITH-MUFFLED-LOADER-CONDITIONS
*UNINTERESTING-CONDITIONS* *UNINTERESTING-COMPILER-CONDITIONS*
*UNINTERESTING-LOADER-CONDITIONS* *USUAL-UNINTERESTING-CONDITIONS*
GET-OPTIMIZATION-SETTINGS PROCLAIM-OPTIMIZATION-SETTINGS
WITH-OPTIMIZATION-SETTINGS *OPTIMIZATION-SETTINGS*
*PREVIOUS-OPTIMIZATION-SETTINGS* CALL-AROUND-HOOK
CHECK-DEFERRED-WARNINGS RESET-DEFERRED-WARNINGS SAVE-DEFERRED-WARNINGS
ENABLE-DEFERRED-WARNINGS-CHECK DISABLE-DEFERRED-WARNINGS-CHECK
REIFY-DEFERRED-WARNINGS UNREIFY-DEFERRED-WARNINGS WITH-SAVED-DEFERRED-WARNINGS
REIFY-SIMPLE-SEXP UNREIFY-SIMPLE-SEXP
WARNINGS-FILE-P WARNINGS-FILE-TYPE *WARNINGS-FILE-TYPE*
```

## What is real here

More than it looks. Do not blanket-stub this sub-package:

- **`reify-simple-sexp` / `unreify-simple-sexp`** are a pure sexp <-> printable
  encoding. Portable, useful, and used by anything that wants to write a form to
  a file safely.
- **The condition classes** (`compile-condition` and its five subclasses,
  `invalid-configuration`'s cousin here) are just `define-condition` -- real, and
  a handler that mentions them must not fail to find the class.
- **`lispize-pathname`, `compile-file-type`, `compile-file-pathname*`,
  `*base-build-directory*`, `load-pathname`, `current-lisp-file-pathname`,
  `warnings-file-p` / `warnings-file-type` / `*warnings-file-type*`** are
  pathname and variable plumbing. Real. `compile-file-type` answers what
  rontolisp actually produces -- and that is a genuine question with a good
  answer: `.class` or `.wasm` depending on the backend, or nil for the
  interpreter. Decide it once and record it.
- **`load*` and `load-from-string`** are real: `load` exists
  (`.kb/read-load-streams.md`, `.kb/load-inliner.md`) and `load-from-string` is
  `load` over a string stream.
- **The muffled-conditions family and the optimization settings** are real over
  `handler-bind` and `proclaim`/`declaim` (`.kb/declarations-type-checks.md`) --
  `*optimization-settings*` should reflect what `--optimize` means here rather
  than invent a scale.

## What is not

**`compile-file*` and the deferred-warnings machinery.** `compile-file*` is
upstream's portability wrapper around `cl:compile-file` producing a fasl, and
the deferred-warnings family (`save-deferred-warnings`, `reify-deferred-warnings`,
`check-deferred-warnings`, `with-saved-deferred-warnings`, `combine-fasls`) exists
to carry SBCL's undefined-function warnings between compilation units in a fasl
build. rontolisp has no `compile-file`, no fasl and no compilation-unit
protocol; its compilers write a `.class` or a `.wasm` from the CLI.

So: `not-implemented-error` for that group, with the reason named -- and
`enable-deferred-warnings-check` / `disable-deferred-warnings-check` /
`reset-deferred-warnings` as no-ops rather than errors, since a library calls
them defensively and an error there converts a no-op into a failure.

The re-evaluation trigger for `.kb/uiop.md`: if rontolisp ever grows a real
`cl:compile-file`, `compile-file*` is the first thing that should stop signalling.

## Done

The inventory is 44 rows, not 48 (this file's "48 portable externals" miscounted;
`UiopCoverageTest.printCoverage` is the authority). All 44 land in
`src/main/resources/am/ik/rontolisp/eval/uiop-lisp-build.lisp`.

Real: the six condition classes (`compile-condition` and the five subclasses, real
`define-condition`s); the muffled-compiler/loader-conditions family (`call-with-`
over `call-with-muffled-conditions` of the uninteresting lists; the two `with-`
macros are Java expansions in `LispMacroExpander`, in `UIOP_MACRO_EXPANSIONS`, with
the two new `MACRO_EXPANSION_CALLEES` rows); `load*` (the muffled loader around
`cl:load` for a pathname/string and `uiop/stream:eval-input` for a stream --
rontolisp's `load` cannot load from a string-input-stream, so the stream arm is
upstream's for the implementations that cannot either); `load-from-string`;
`reify-simple-sexp`/`unreify-simple-sexp` (the terminating NIL of a proper list
passes through as nil, because `reify-symbol` signals);
`check-lisp-compile-warnings`/`check-lisp-compile-results`; `call-around-hook`;
`lispize-pathname`; `compile-file-pathname*` (no compiled-file TYPE to derive, so
the honest answer is the explicit `output-file` merged against the input, or nil);
`warnings-file-type` (nil for the one implementation here -- no `:rontolisp`
clause)/`warnings-file-p`/`*warnings-file-type*`; the variables (`*base-build-directory*`,
`*compile-check*`, the behaviour flags at upstream's `:warn` defaults, the
uninteresting lists seeded empty). `compile-file-type` stays a home-redirect row:
its home is UIOP/PATHNAME (`.kb/uiop.md`), already defined there, so it is NOT
redefined in this resource.

Not implemented (resolve + signal `not-implemented-error` naming the operation):
`compile-file*`, `save-deferred-warnings`, `reify-deferred-warnings`,
`unreify-deferred-warnings`, `check-deferred-warnings`, `with-saved-deferred-warnings`
(a defun stub a call form lowers past via `expandUnimplementedUiopMacro`, dropping
its body), `combine-fasls`. The three defensive checks --
`enable-deferred-warnings-check`/`disable-deferred-warnings-check`/
`reset-deferred-warnings` -- are no-ops, not errors, so a library calling them
defensively does not turn a no-op into a failure.

Craft notes: resources may not use `if-let` (bare name not expanded) --
`warnings-file-p` is written with `let`+`when` instead. The muffled `with-` macros
require an empty spec list (`()`, `LispNil`), wrapping the body in a zero-arg
lambda. A dynamic `let` binding of a uiop var does not take effect inside a
lazy-loaded defun (pre-existing behaviour, not introduced here), so the
`check-lisp-compile-warnings` probe is exercised via setf/at top level, not via a
`let` around the defun.

## Gate

`UiopCoverageTest` reports `uiop/lisp-build 44/44`. The overall is **338/435**
(`printCoverage`, the authority): the remaining gaps are the separate unimplemented
sub-packages (`launch-program`, `run-program`, `configuration`, and stream's 28) --
not part of this item. `LispEvaluatorTest` pins the
`reify-simple-sexp` round trip (expected `((1 "two" (3 4)) "x" "y" 42)` -- the
`list` wraps the round-trip result) and `load-from-string`; the
`not-implemented-error` group is pinned by one test asserting the condition type
and that the report names `compile-file`. The portable half is pinned on the
compile paths too: `JvmLispCompilerTest#compileAndRunUiopLispBuildPortableHalf`
and its WASM twin `WasmLispCompilerIntegrationTest#uiopLispBuildPortableHalfCompileAndRun`
(`:C :L 5 (1 "two" (3 4)) "sbcl-warnings" :NIE`).

Docs: `doc/en|ja/reference/uiop.md` coverage row and the "What is implemented"
paragraph (lisp-build is the sixth complete sub-package); `.kb/uiop.md` gained the
two `MACRO_EXPANSION_CALLEES` rows and a `uiop/lisp-build` verdict section.
