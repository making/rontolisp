# `concatenate` — the three result families (one contract, four backends)

User-facing behavior: `doc/{en,ja}/reference/functions/concatenate.md`.

`compiler/ConcatenateForms` is the ONE home of the contract. Both halves live
there so the interpreter and the compilers cannot drift:

- `resultFamily(designator)` — the shared normalizer over an EVALUATED
  result-type designator (quote already stripped). A symbol or a compound spec's
  head maps to `STRING` (`string`/`simple-string`/`base-string`/
  `simple-base-string`), `LIST` (`list`/`cons`) or `VECTOR` (`vector`/
  `simple-vector`/`array`/`simple-array`/`bit-vector`/`simple-bit-vector`), with
  package-qualified spellings normalized through their member name. Element types
  are DROPPED, so `'(vector (unsigned-byte 8))` is just the vector family
  (rontolisp vectors are generic) — the same rule `expandCoerce` follows for
  `coerce`/`map`.
- `expand(cons, normalizeArguments)` — the compile-path lowering, called from the
  `CONCATENATE` case of `Jvm/WasmExprCompiler`. String family → the nested binary
  `%string-concat` chain (a lone argument concatenates with `""`, so the result is
  always fresh), each argument that is not a literal string wrapped in
  `(%seq-string arg)` when `normalizeArguments` is set (see below). List family →
  `(append (coerce a 'list) (coerce b 'list) ... nil)`; the trailing `nil` is what
  makes `append` copy the LAST argument too. Vector family → that same list,
  wrapped in `(coerce ... 'vector)`. Nothing new is emitted per backend: the
  lowering is entirely over primitives both compilers already have.
- `literalResultFamily(typeForm)` — `expand`'s entry (and `--no-gc`'s check):
  normalizes the type argument AS WRITTEN, i.e. only a literal `(quote ...)`.

The interpreter keeps its Java builtin (`Environment`, `LispNames.CONCATENATE`)
and resolves the family through the same `resultFamily`, so it also accepts a
COMPUTED result type — the one deliberate interpreter-only extra (a compiler has
to resolve the family statically; `coerce` has the same split and the reference
pages say so).

## The string family takes any character sequence (`%seq-string`, todo-202)

`(concatenate 'string "a" '(#\b #\c) #(#\d) nil "e")` is `"abcde"` on every
backend, and `nil` — the empty list — is the case real code leans on: s-sql's
`expand-table-name` builds `"CREATE TABLE person"` as
`(concatenate 'string (unless tableset "TABLE ") (to-sql-name name))`. An element
that is not a character is an error, not a silent `princ`.

The string family used to take STRINGS only, because it lowers straight to
`%string-concat` (whose operands are strings; a marked mutable character vector
normalizes first, a cons list does not). Widening it the obvious way — an inline
`(coerce arg 'string)` per argument — was rejected then and is still rejected:
it plants two loops per argument at every `concatenate 'string` site, and
`.kb/wasm-function-body-size.md` is why one emitted body must not grow without
bound. What landed instead is the alternative that file named: ONE cheap helper,
called once per argument.

- **`%seq-string`** (`LispNames.SEQ_STRING`, a `cl` internal) is
  `(lambda (x) (if (stringp x) x (coerce x 'string)))` — a
  `BuiltinFunctionWrappers` entry, i.e. an ordinary injected defun, so NO backend
  needed a new primitive: the `coerce` loop is emitted once, inside it. The fast
  path is a single `stringp` test. The interpreter has the equivalent Java
  builtin, and its `concatenate` walks elements directly.
- **The injection is gated** on `ConcatenateForms.needsSeqString(program)`: true
  when the PROGRAM ITSELF writes a `(concatenate 'string ...)` with an argument
  that is not a literal string. The flag rides on `Ctx.usesSeqString` in both
  compilers (and must be copied by `WasmAsyncEmit.freshCtx`, which also builds
  the synchronous top level), and `expand` only wraps when it is set. That is not
  an optimization but a correctness constraint on the gate: `LispMacroExpander`
  emits `(concatenate 'string ...)` of its own during CODEGEN — `format`,
  `with-output-to-string`, the string-stream builders — long after the scan, and
  those operands are strings the expansion just built. Wrapping them would call a
  helper the gate did not inject; not wrapping them keeps every program that
  never wrote a widening `concatenate` byte-identical.
- `#'concatenate`'s wrapper spells the same contract inline
  (`(if (stringp x) x (coerce x 'string))` inside its fold) rather than calling
  `%seq-string`, because its own injection is gated separately.
- `--no-gc` is unchanged: `NoGcWasmCompiler.compileConcatenate` builds strings in
  linear memory and never goes through `expand`.

## `#'concatenate` as a first-class value

`BuiltinFunctionWrappers.concatenateWrapper` is a
`(lambda (type &rest seqs) ...)` in `REFERENCE_GATED_FUNCTIONS`, so it is
injected only when the program takes `(function concatenate)` — ordinary programs
stay byte-identical. Its result type is a RUNTIME value, so the family dispatch
is re-done with `member` over the designator (its `car` for a compound spec) and
mirrors `expand` arm for arm: `%string-concat` fold for strings,
`(coerce x 'list)` accumulation for list/vector, an `error` for anything else.
This is what makes ironclad's HKDF
`(apply #'concatenate '(vector (unsigned-byte 8)) blocks)` work
(`.kb/asdf.md`).

## `coerce` re-uses the same runtime dispatch

`coerce`'s own result type may be computed too (`(coerce seq type)` with `type`
in a variable — `alexandria:copy-sequence`/`coercef`/`median`). It was FLOAT-ONLY
until `.todo/219`; `LispMacroExpander.expandComputedCoerce` now dispatches on the
designator's head over the same families this file describes, with each arm being
the SAME body the literal path emits (`coerceToListBody`/`coerceToVectorBody`/
`coerceToStringBody`, extracted for exactly that reason), plus `t` as the
identity. So a computed result type can never mean something a literal one does
not. The shape is `concatenateWrapper`'s above, not a second design.

## `--no-gc`

`NoGcWasmCompiler.compileConcatenate` builds strings in linear memory and that
backend has neither cons cells nor a general array type, so a non-string family
is now a compile error naming the offending designator instead of silently
producing a string (`.kb/no-gc-scalar-wasm.md`).

## Pinning

`ci-spec.yaml` `concatenate-result-families` (all four backends, including the
mixed-sequence string family),
`LispEvaluatorTest#evalConcatenate*`,
`JvmLispCompilerTest#compileAndRunConcatenate*` +
`compileConcatenateWithComputedResultTypeFails`,
`WasmLispCompilerIntegrationTest#concatenateBuildsListAndVectorResultTypes`, and
`IroncladE2eTest` (the HKDF vector, end to end on four backends).
