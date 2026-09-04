# jzon-driven CL additions

CL features working on ALL backends (interpreter + JVM + both WASM) so
`com.inuoe.jzon` v1.1.4 runs identically. See [adjustable-arrays.md](adjustable-arrays.md),
[error-handling.md](error-handling.md), [clos.md](clos.md).

## All-backend, no mechanics needed

`shiftf`; `load-time-value` (lite: re-evaluates); `(setf (values ...))`; setf through
`(the T place)`; `typep` on literal specifiers (shared static type-test builder);
`(symbolp nil)`/`(symbolp t)` = t; `|...|` reader escape (verbatim, whitespace included);
`equalp` array element-wise (prelude); `streamp`/`'stream` accept the `t` designator;
defstruct `:constructor`/`:conc-name`/`:predicate`/`:copier`; `(lambda ())` -> nil;
`(aref a)` rank-0 read; `#'aref` wrapper (variadic row-major fold);
`array-dimension-limit` reader-substituted like `most-positive-fixnum`; `case`/`ecase`
keys match package-qualified and plain spelling; `open`/`with-open-file` accept
`:external-format`/`:if-exists`/`:if-does-not-exist` with native default values only;
`*standard-output*` reads as designator `t` on compilers, `*error-output*` as the
standard-error handle (`.kb/standard-output-redirect.md`); non-literal
`symbol-function` and `uiop:` stubs compile to cold-path runtime signals.

Lite introspection stubs (compilers: shared `LispMacroExpander` expansions):
`mask-field`, `scale-float`, `char-name` (prelude defun), `fdefinition`,
`file-position`, `file-length`, `make-broadcast-stream`, `pathnamep`,
`input-stream-p`, `output-stream-p`, `stream-element-type`, `class-of`, `slot-boundp`,
`slot-makunbound`, `simple-condition-format-control`/`-arguments`; `write-string`
`:start`/`:end`; `replace` nil bounds. `slot-boundp`/`slot-makunbound` need a literal
slot name on the compilers.

## With mechanics / traps

- `subtypep`: built-in lattice + class registry + struct `:include` ancestry
  (`structure-object` = every struct's supertype) + user deftype either side + `(or ...)`;
  single value; shared `LispMacroExpander.subtypep`; compilers fold literals via
  `expandSubtypep`. Non-literal form = ONE shared `%subtypep-runtime` defun injected by
  `expandTopLevelDefinitions` -- per-call-site inlining overflowed the JVM 16-bit branch range.
- `#'format` first-class: interpreter rebuilds the literal call; compilers wrap the
  runtime control renderer via `REFERENCE_GATED_FUNCTIONS`, injected only when referenced.
- CLOS `list`/`cons`/`sequence` specializers EXCLUDE class instances (tag-union guard);
  `standard-object`-or-cons-shaped TYPE specializers trigger dispatcher regeneration on `defclass`.
- Runtime slot names for `slot-value`/`slot-boundp` + compiled `%class-slot-defs` ([clos.md](clos.md)).
- Runtime SYMBOL datum to `error` WITH initargs re-dispatches as a condition type on the
  compilers (registry `member` cond; keyword literals stay unbound in the temps,
  datum-only calls keep the object path) -- [error-handling.md](error-handling.md).
- WASM `hash-table-p` no longer matches general arrays (both share `TYPE_CELL`; the
  header car tells them apart).
- `%ieee754-*` = interpreter + JVM only (`JvmIeee754Compiler`); WASM lacks the 64-bit
  unsigned model.
- Fill-pointered/adjustable STRINGS: interpreter = mutable `LispString`; compilers = the
  general array marked "character vector" (JVM length-4 header + `_strv` normalizer;
  WASM meta-offset-1 marker + always-emitted `_charvec_to_str`).
  `replace`/`(setf (schar ...))` mutate in place via the shared runtime-`%arrayp`-branch
  expansions; character `:initial-contents` copies to a fresh string.

## Tests

- ci-spec `runtime-type-dispatch-residue`, `JzonE2eTest` (4 backends).
