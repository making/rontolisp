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

With these, every cl-utilities component file **loads** (top-level forms
compile/eval) EXCEPT `once-only.lisp`.

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
- **`string`** -- MISSING (surfaced by `once-only.lisp`, which does
  `(gensym (string name))`). Need the CL `string` designator coercion
  (symbol -> name, string -> itself, char -> 1-char string). Small, all four
  backends; a `symbol-name`-like builtin.
- **`macrolet` was NOT actually needed by cl-utilities** -- its only occurrence
  (`compose.lisp`) is under `#+nil` (dead code). We implemented it anyway
  because it is a real, reusable feature that unblocks other libraries.

Even once every file LOADS, exercising the functions hits pervasive stdlib
gaps (runtime, well beyond this task's language features). Observed on the
interpreter:

- variadic `nconc` (ours is 2-arg) -- cl-utilities' own `split-sequence`.
- `reduce` with `:from-end` -- `compose`, `with-collectors`.
- `integer-length`, `logbitp` -- `expt-mod` (non-SBCL branch).
- `make-array` with `:adjustable`/`:fill-pointer`/`:displaced-to`,
  `array-displacement`, `adjustable-array-p`, `array-has-fill-pointer-p`,
  `fill-pointer` -- `copy-array`.
- `byte`/`byte-size`/`ldb`/`dpb` -- `rotate-byte`.
- `multiple-value-setq`, `rotatef`, `with-slots`, `warn` -- `read-delimited`,
  `with-gensyms`, `extremum` conditions.
- interpreter macro-expansion ordering for `flet`/`labels` local `collect`
  inside `collecting`/`with-collectors` (the documented interpreter caveat).

`with-unique-names`/`with-gensyms` (definition) and simple `compose`/
`collecting` shapes work on the interpreter, but there is no clean, whole-system
runnable subset to anchor a `ClUtilitiesE2eTest` yet -- so vendoring +
`ClUtilitiesE2eTest` is deferred until enough of the stdlib residue above lands
(nested backquote is now done). Do NOT vendor cl-utilities for a
non-functional E2E.

## Plan (remaining)

1. Nested backquote in the reader -- DONE (unblocked `once-only`'s expansion;
   `string` builtin still needed before the file fully expands).
2. Pick off the stdlib residue above (variadic `nconc`, `reduce :from-end`,
   `integer-length`/`logbitp`, `multiple-value-setq`, `rotatef`) so a useful
   subset RUNS; leave `make-array`-displacement / byte-ops (`copy-array`,
   `rotate-byte`) as the last, heaviest items.
3. Then vendor + `ClUtilitiesE2eTest` + ci-spec + docs (asdf guide "what can I
   actually load" + `examples/asdf`).

`anaphora` needs `symbol-macrolet` + `define-setf-expander` (harder than
macrolet: a walker that substitutes VARIABLE references); the macrolet walker
machinery now in place is a starting point.
