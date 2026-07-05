# 65: Load cl-utilities via asdf:load-system

Follow-up of the real-library loading campaign (split-sequence and
parse-number load on all four backends, see `.kb/asdf.md` and
`examples/asdf/`). cl-utilities (public domain, the classic grab-bag:
`with-unique-names`, `once-only`, `compose`, `extremum`, `collecting`, its own
`split-sequence`, ...).

## Status (2026-07-05)

The three language features triaged as blockers are DONE (all four backends,
tests + ci-spec case `macrolet-compiler-macro-restart-case` + docs):

1. **`macrolet`** -- DONE. Local, lexically scoped macros. Interpreter:
   `LispEvaluator.evalMacrolet` installs the locals into the user-macro table
   for the dynamic extent of the body (save/restore), sharing the
   `makeUserMacro` builder with `defmacro`. Compile path:
   `UserMacroExpander.expandMacrolet` pushes the locals via
   `LispEvaluator.pushLocalMacro`/`popLocalMacro`, expands the body, and drops
   the wrapper (single body form returned unwrapped, else `progn`); the
   activation guard now fires on `usesMacrolet` so a macrolet-only program still
   runs the pass. No backend codegen (like `defmacro`). `symbol-macrolet` still
   unsupported. Interpreter scoping caveat (documented): the table is global for
   the extent, so a function *called* from the body that references a local
   macro name would also see it (same pre-existing limitation as `flet`).
2. **`restart-case`** -- DONE. Lite `LispMacroExpander.expandRestartCase` to the
   primary form only (restart clauses are dead without a condition system).
3. **`define-compiler-macro`** -- DONE. Parsed no-op returning nil
   (`expandDefineCompilerMacro`), like `declaim`/`deftype`.

With these (plus `string`, below), every cl-utilities component file **loads**
(top-level forms compile/eval), including `once-only.lisp`.

## Remaining blockers for a full `asdf:load-system :cl-utilities`

The 2026-07-05 re-triage (probing the real 1.2.4 tarball) found the original
triage was wrong on two points, and surfaced deeper gaps:

- **Nested backquote (`once-only.lisp`)** -- DONE (2026-07-05, commit
  "Support nested backquote in the reader"; details in
  `.kb/defmacro-backquote.md`). The reader now fully expands nested/multi-level
  backquote at read time (CLtL2/Steele port in `LispReader`); backends
  unaffected. `once-only.lisp` READS and the macro expands correctly (verified
  against SBCL). BUT it also calls `(string name)` -- see the `string` residue
  item below -- so the file still won't fully expand until `string` lands.
- **`string`** -- DONE (2026-07-05, all four backends + tests + ci-spec
  `symbol-runtime-api` + docs). The CL `string` designator coercion
  (symbol -> name, string -> itself, char -> 1-char string, `t`/`nil` ->
  `"t"`/`"nil"`). Interpreter type-checks; compiled backends reuse the
  `symbol-name`/`princ-to-string` machinery (lenient on non-designators). cl
  function count 221 -> 222. `once-only.lisp` now fully expands AND runs
  correctly on all four backends (verified: side-effecting arg evaluated once).
- **`macrolet` was NOT actually needed by cl-utilities** -- its only occurrence
  (`compose.lisp`) is under `#+nil` (dead code). We implemented it anyway
  because it is a real, reusable feature that unblocks other libraries.

Even once every file LOADS, exercising the functions hits pervasive stdlib
gaps (runtime, well beyond this task's language features). Observed on the
interpreter, now **split into single-session sub-todos** (each self-contained;
do the lighter ones first):

- variadic `nconc` (ours is 2-arg) -- cl-utilities' own `split-sequence`.
  -> **`.todo/66-variadic-nconc.md`**
- `reduce` with `:from-end` (+ `:key`) -- `compose`, `with-collectors`.
  -> DONE (2026-07-05): lowered in `LispMacroExpander.expandReduce` (reverse +
  arg-swap for `:from-end`, `mapcar` for `:key`), wired into the interpreter and
  both compilers ahead of the string-seq wrapper. `compose` runs on all four
  backends. (was `.todo/67-reduce-from-end-key.md`)
- `integer-length`, `logbitp` -- `expt-mod` (non-SBCL branch).
  -> **`.todo/68-integer-length-logbitp.md`**
- `multiple-value-setq`, `rotatef` -- `read-delimited`, `with-gensyms`,
  `extremum`. -> **`.todo/69-multiple-value-setq-rotatef.md`**
- `byte`/`byte-size`/`ldb`/`dpb` -- `rotate-byte`.
  -> **`.todo/70-byte-field-ops.md`**
- `make-array` with `:adjustable`/`:fill-pointer`/`:displaced-to`,
  `array-displacement`, `adjustable-array-p`, `array-has-fill-pointer-p`,
  `fill-pointer`, `adjust-array` -- `copy-array` -- DONE on all four backends
  (2026-07-06, todo 71 closed; see `.kb/adjustable-arrays.md`).
- `with-slots` -- belongs with CLOS/defstruct work, tracked in
  `.todo/40-clos-and-defstruct.md`.
- `warn` -- belongs with the condition system, tracked in
  `.todo/39-condition-system.md`.
- interpreter macro-expansion ordering for `flet`/`labels` local `collect`
  inside `collecting`/`with-collectors` (the documented interpreter caveat) --
  tracked in `.todo/34-local-function-definition.md`.

`with-unique-names`/`with-gensyms` (definition) and simple `compose`/
`collecting` shapes work on the interpreter, but there is no clean, whole-system
runnable subset to anchor a `ClUtilitiesE2eTest` yet -- so vendoring +
`ClUtilitiesE2eTest` is deferred until enough of the stdlib residue above lands
(nested backquote is now done). Do NOT vendor cl-utilities for a
non-functional E2E.

## Plan (remaining)

1. Nested backquote in the reader -- DONE. `string` builtin -- DONE
   (2026-07-05); `once-only.lisp` now fully expands and runs on all four
   backends.
2. Pick off the stdlib residue, now split into `.todo/66`-`71` (do the lighter
   ones -- 66/67/68/69 -- first; 70/71 are the heavy tail). Each is single-
   session sized and lands independently on all four backends.
3. Then vendor + `ClUtilitiesE2eTest` + ci-spec + docs (asdf guide "what can I
   actually load" + `examples/asdf`).

`anaphora` needs `symbol-macrolet` + `define-setf-expander` (harder than
macrolet: a walker that substitutes VARIABLE references); the macrolet walker
machinery now in place is a starting point.
