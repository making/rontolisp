# jzon-driven CL additions

CL features made to work on ALL backends (interpreter + JVM + both WASM) so
`com.inuoe.jzon` v1.1.4 runs identically. See [adjustable-arrays.md](adjustable-arrays.md),
[error-handling.md](error-handling.md), [clos.md](clos.md),
[standard-output-redirect.md](standard-output-redirect.md).

## Landed
`shiftf`; `load-time-value` (lite); `(setf (values ...))`; setf through `(the T place)`;
literal-specifier `typep`; `(symbolp nil/t)`; `|...|` reader escape; `equalp` on arrays;
`streamp`/`'stream` accept `t`; defstruct `:constructor`/`:conc-name`/`:predicate`/`:copier`;
`(lambda ())` -> nil; `(aref a)` rank-0; `#'aref`; `array-dimension-limit`; `case`/`ecase`
keys match qualified and plain spelling; `open`/`with-open-file` `:external-format`/
`:if-exists`/`:if-does-not-exist` (native defaults only); `*standard-output*` = designator
`t` and `*error-output*` = stderr handle on compilers; non-literal `symbol-function` and
`uiop:` stubs = cold-path runtime signals.

Lite stubs, shared `LispMacroExpander` expansions: `mask-field`, `scale-float`, `char-name`,
`fdefinition`, `file-position`, `file-length`, `make-broadcast-stream`, `pathnamep`,
`input-stream-p`, `output-stream-p`, `stream-element-type`, `class-of`, `slot-boundp`,
`slot-makunbound`, `simple-condition-format-control`/`-arguments`, `write-string`
`:start`/`:end`, `replace` nil bounds. `slot-boundp`/`slot-makunbound` need a literal slot
name on the compilers.

## Traps
- Non-literal `subtypep` = ONE shared `%subtypep-runtime` defun from
  `expandTopLevelDefinitions`; per-site inlining overflowed the JVM 16-bit branch range.
- `#'format` first-class: compilers gate the runtime renderer on `REFERENCE_GATED_FUNCTIONS`.
- CLOS `list`/`cons`/`sequence` specializers EXCLUDE class instances.
- WASM `hash-table-p` must not match general arrays (both share `TYPE_CELL`).
- `%ieee754-*` = interpreter + JVM only (`JvmIeee754Compiler`).
- Fill-pointered/adjustable strings on the compilers = general array marked "character
  vector" (JVM length-4 header + `_strv`; WASM meta-offset-1 marker + `_charvec_to_str`).

## Tests
- ci-spec `runtime-type-dispatch-residue`; `JzonE2eTest` (4 backends).
