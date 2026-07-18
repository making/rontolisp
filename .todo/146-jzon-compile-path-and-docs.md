# jzon: compile path CLOSED 2026-07-18 -- remaining = accepted-residue notes only

The real com.inuoe.jzon v1.1.4 loads and runs END-TO-END ON ALL FOUR BACKENDS
(`JzonE2eTest` extends `AsdfLibraryE2eSupport`; the combined basic + README
exercise is byte-identical across interpreter/JVM/WASM P1/component). The
final sweep (2026-07-18, after the phase-1/2/3 sweeps in git log):

- Numeric leaf-module shims: `ShimLibraries.leafModuleForms` substitutes
  jzon's `eisel-lemire.lisp`/`ratio-to-double.lisp`/`schubfach.lisp` with
  shims over native float arithmetic (both loaders consult it). Kills the
  `#.` power-of-ten table crash AND the u64/u128 arithmetic. Float text =
  rontolisp's cross-backend-identical shape (NOT shortest round trip);
  parse of `|exp10| <= 22` rounds once, extremes are a few ulps off.
  `.kb/asdf.md`.
- Residual compile-path gaps flushed by the full-library run (all shared
  expansions unless noted): runtime `subtypep` (`%subtypep-runtime` shared
  defun -- per-site inlining overflowed the JVM branch range), runtime
  condition-type `error` dispatch with initargs (datum-only stays on the
  object path -- the cl-base64 `#'error` wrapper regression),
  `%class-slot-defs` + runtime-slot-name `slot-value`/`slot-boundp`,
  `open`/`with-open-file` CL keyword shape (ignorable options only with
  native-default values), `#'aref` wrapper, `*standard-output*`/
  `*error-output*` as values, empty-body `(lambda ())`, rank-0 `(aref a)`
  on WASM, `array-dimension-limit` reader substitution, cold-path signals
  for non-literal `symbol-function` and `uiop:` stubs, WASM `hash-table-p`
  no longer matching general arrays, Gray-pass `format`-to-stream rewrite +
  walker element-wise recursion fix, `UserMacroExpander` macro-time
  registration through top-level `progn` (macrolet-generated defuns) with
  non-fatal defparameter value evaluation.
- E2E: ci-spec `runtime-type-dispatch-residue`; `JzonE2eTest` 4-backend +
  interpreter-only residue test; docs/kb swept (asdf-systems guide en+ja,
  examples/asdf, `.kb/asdf|error-handling|clos|gray-streams|reader-features`,
  CLAUDE.md).

## Accepted residue (documented, not planned work)

- WASM-side divergences kept out of the 4-backend exercise: large-float
  print shape (`.todo/46`), hash-table iteration order, non-ASCII `\u`
  escapes (`.todo/153` -- code-char beyond ASCII emits one raw byte).
- Compiled backends: a symbol-valued `:key-fn` errors at call time (runtime
  `symbol-function` has no name table; `--dynamic`'s job), `uiop:` pathname
  branches error at call time.
- Playground `#.`: the browser frontend has no macro-time evaluator wiring
  for markers; Compile buttons keep the clear read error (data-types.md).
- `*features*` binding on the compile path: substituted at read time;
  documented in data-types.md as accepted behavior.
