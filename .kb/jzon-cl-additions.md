# jzon-driven CL additions

The Common Lisp features made to work across all backends during the "jzon"
sweep (compile path largely closed 2026-07-18), so that the real
`com.inuoe.jzon` v1.1.4 loads and runs identically on the interpreter, JVM, and
both WASM backends. This file is the grep-findable catalog behind what used to be
one giant CLAUDE.md bullet. Related detail lives in
[adjustable-arrays.md](adjustable-arrays.md) (mutable strings),
[error-handling.md](error-handling.md) (error-with-initargs re-dispatch), and
[clos.md](clos.md) (`slot-value` runtime names / `%class-slot-defs`).

## All-backend additions

ALL-BACKEND now (interpreter + JVM + both WASM backends):

- `shiftf`.
- `load-time-value` (lite: re-evaluates).
- `(setf (values ...))`.
- setf through a `(the T place)` wrapper.
- `typep` -- literal type specifiers via the shared static type-test builder.
- `subtypep` -- built-in lattice + class registry, single value; shared
  `LispMacroExpander.subtypep`; the compilers FOLD literal specifiers at compile
  time via `expandSubtypep`.
- `(symbolp nil)` / `(symbolp t)` = t.
- `|...|` reader symbol escape (verbatim, whitespace included).
- `equalp` compares arrays element-wise (prelude).
- `streamp` / `'stream` type tests accept the `t` designator (the value of
  `*standard-output*`).
- `#'format` is a first-class function: interpreter = rebuild-literal-call;
  compilers = a `REFERENCE_GATED_FUNCTIONS` wrapper over the runtime control
  renderer, injected only when `#'format` is referenced.
- CLOS `list` / `cons` / `sequence` specializers EXCLUDE class instances
  (tag-union guard); `standard-object`-or-cons-shaped TYPE specializers also
  trigger dispatcher regeneration on `defclass`.
- defstruct options (`:constructor` / `:conc-name` / `:predicate` / `:copier`).
- The lite introspection stubs: `mask-field`, `scale-float`, `char-name`
  (prelude defun), `fdefinition`, `file-position`, `file-length`,
  `make-broadcast-stream`, `pathnamep`, `input-stream-p`, `output-stream-p`,
  `stream-element-type`, `class-of`, `slot-boundp`, `slot-makunbound`,
  `simple-condition-format-control`, `-arguments`; `write-string` `:start` /
  `:end`; `replace` nil bounds. On the compilers these are shared
  `LispMacroExpander` expansions; `slot-boundp` / `slot-makunbound` need a literal
  slot name there.

`%ieee754-*` = interpreter + JVM only (`JvmIeee754Compiler`); WASM lacks the
64-bit unsigned model.

Backend-free everywhere: `case` / `ecase` keys match both the package-qualified
and plain spelling.

## Mutable / adjustable strings (2026-07-18)

ALSO ALL-BACKEND: fill-pointered / adjustable STRINGS.

- Interpreter = a mutable `LispString`.
- Compilers = the general array marked "character vector" (JVM length-4 header +
  `_strv` normalizer; WASM meta-offset-1 marker + always-emitted
  `_charvec_to_str`).
- `replace` / `(setf (schar ...))` mutate it in place via the shared
  runtime-`%arrayp`-branch expansions.
- Character `:initial-contents` copies to a fresh string.

See [adjustable-arrays.md](adjustable-arrays.md) for the general array machinery.

## Runtime type dispatch sweep (2026-07-18, the jzon full-library sweep)

ALSO ALL-BACKEND:

- Runtime (non-literal) `subtypep`: one shared `%subtypep-runtime` dispatch defun
  injected per program by `expandTopLevelDefinitions` -- inlining it per call site
  overflowed the JVM 16-bit branch range.
- A runtime SYMBOL datum to `error` WITH initargs re-dispatches as a condition
  type on the compilers too (registry `member` cond; keyword literals stay unbound
  in the temps, datum-only calls keep the object path). See
  [error-handling.md](error-handling.md).
- Runtime slot names for `slot-value` / `slot-boundp` + compiled
  `%class-slot-defs`. See [clos.md](clos.md).
- `open` / `with-open-file` accept the CL keyword shape (`:external-format` /
  `:if-exists` / `:if-does-not-exist`, only with the native default values).
- `#'aref` wrapper (variadic row-major fold).
- `*standard-output*` reads as the designator `t` on the compilers,
  `*error-output*` as the standard-error handle
  (`.kb/standard-output-redirect.md`).
- `(lambda ())` returns nil.
- `array-dimension-limit` is reader-substituted like `most-positive-fixnum`.
- Non-literal `symbol-function` and `uiop:` stub calls compile into cold-path
  runtime signals.
- `(aref a)` reads a rank-0 array.
- WASM `hash-table-p` no longer matches general arrays (both share `TYPE_CELL`;
  the header car tells them apart).

## Pinning tests

Pinned by `runtime-type-dispatch-residue` + `JzonE2eTest` (4 backends).
