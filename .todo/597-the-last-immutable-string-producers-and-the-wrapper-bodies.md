# The last string producers still answer immutable values, and the first-class wrapper bodies do too

Difficulty: High

Split out of `.todo/596` when its round landed (2026-08-31). 596 flipped
`concatenate 'string`, the case family, literal-`nil` `format`, the
string-stream capture and `read-line` to answer MUTABLE character vectors on
the compile backends (`.kb/string-write-runtime.md`, "The remaining producers
are flipped" -- the wrap pattern `_toMutStr` / `_to_mut_str`, the shared
`MutableStringProducers` gate, the quote-frame discriminator that keeps a
SYMBOL out of the wrap, and the boundary seams that round fixed). What is
still immutable, each deliberately, with the reason measured or argued:

- **`princ-to-string` / `prin1-to-string` / `write-to-string`.** The static
  `format` lowering emits its `~a`/`~s` pieces through the SAME compiler case
  (`.kb/format.md`, `opsToPieces`), so wrapping the case would put a
  convert-and-render round trip on every piece of every literal-control
  format. Flipping these needs a way to distinguish a program-written call
  from an expansion piece -- an internal piece alias with the same
  print-object dispatch, or wrapping in the expander only for source-written
  calls. Measure the piece tax before choosing.
- **A COMPUTED `format` destination that is nil at run time.** Only the
  literal-`nil` spelling wraps; gating on any non-`t` destination would pull
  the JVM array runtime into every `(format stream ...)` program. If flipped,
  the gate and the wrap must move together on both backends
  (`MutableStringProducers.isFormatToString`).
- **The first-class `#'format` / `#'concatenate` wrapper bodies**
  (`BuiltinFunctionWrappers.formatWrapper` / `concatenateWrapper`): they build
  through the renderer / a `%string-concat` reduce, not through the wrapped
  compiler cases, so `(funcall #'concatenate 'string a b)` answers an
  immutable value where the call-position spelling answers a mutable one. The
  cheap fix is `(copy-seq ...)` around each wrapper's string arm -- but the
  gate must then see the reference (`referencesFunctionValue`) so the JVM
  array runtime is present; without that the JVM stays immutable while WASM
  flips, which is the cross-backend divergence the shared gate exists to
  prevent.
- **`string-trim` family, `map 'string`, `coerce 'string`, `reverse`,
  `remove` / `substitute` string results.** Same per-site wrap pattern; add
  each producer's name to `MutableStringProducers.PRODUCER_NAMES` and wrap its
  case in BOTH `Jvm`/`WasmExprCompiler`. Measure `map 'string` against
  `.todo/595`'s accumulator rows first -- it is the hottest of these.
- **getenv / fetch / socket / gray-stream read results** (including
  `%io-read-line`'s socket arm -- only the `%read-line-raw` fallback wraps).
- **`symbol-name` / `gensym` / `make-symbol` names: keep immutable
  deliberately** (CLHS leaves symbol-name mutation undefined; SBCL shares the
  name object). Do not flip; record only.
- **json-parse's multi-fragment string values** are immutable
  (`%json-concat`'s merge is the unwrapped `%string-concat`, `json.lisp`);
  single-fragment values are subseq slices and mutable. If value identity
  ever matters, wrap in `%json-string`'s return, not in the merge -- the
  merge re-conversion was a json-stringify 112 -> 245 ms regression before
  596 moved it to `%string-concat` (111 ms after, flat against baseline).
- **A STRING eof-value** handed back by `(read-line s nil "eof")` at EOF is
  `equal` but not `eq` to the argument (the wrap copies it). Fix would be
  wrapping inside the read compilers' line arm only.

## Definition of done

Per producer flipped: the 596 alias/callee/replace/fill shapes match SBCL on
all four backends, `string-identity-cross-backend` extended, and the corpus
rows re-measured (each row its own defun, untouched control,
`.kb/string-write-runtime.md`'s table format). Landing a subset with numbers
is fine -- 559 and 596 both did. The performance residue of the ALREADY
flipped producers (the accumulator idiom, the charvec read lanes in
`position`'s per-call `coerce 'list`) belongs to `.todo/343`, not here.
