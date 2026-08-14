# `uiop/lisp-build`: `compile-file*`, the muffled conditions and the deferred warnings

Difficulty: Medium

Depends on `.todo/353`, `.todo/354`, `.todo/357`, `.todo/359`. The last of the
twelve, and the one whose subject rontolisp mostly does not have.

48 portable externals (`sb-grovel-unknown-constant-condition` is `#+sbcl` and
excluded), **none** present:

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

## Gate

`UiopCoverageTest` reports `uiop/lisp-build 48/48` -- and with it, **434/434
overall**, which is the point of the whole series. `LispEvaluatorTest` pins the
`reify-simple-sexp` round trip and `load-from-string`; the
`not-implemented-error` group is pinned by one test asserting the condition type
and that the report names `compile-file`.
