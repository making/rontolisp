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
- `expand(cons)` — the compile-path lowering, called from the `CONCATENATE` case
  of `Jvm/WasmExprCompiler`. String family → the nested binary `%string-concat`
  chain (a lone argument concatenates with `""`, so the result is always fresh).
  List family → `(append (coerce a 'list) (coerce b 'list) ... nil)`; the
  trailing `nil` is what makes `append` copy the LAST argument too. Vector family
  → that same list, wrapped in `(coerce ... 'vector)`. Nothing new is emitted per
  backend: the lowering is entirely over primitives both compilers already have.
- `literalResultFamily(typeForm)` — `expand`'s entry (and `--no-gc`'s check):
  normalizes the type argument AS WRITTEN, i.e. only a literal `(quote ...)`.

The interpreter keeps its Java builtin (`Environment`, `LispNames.CONCATENATE`)
and resolves the family through the same `resultFamily`, so it also accepts a
COMPUTED result type — the one deliberate interpreter-only extra (a compiler has
to resolve the family statically; `coerce` has the same split and the reference
pages say so).

## Why the string family takes string arguments

`(concatenate 'string "a" '(#\b))` signals on every backend — the string family
lowers to `%string-concat`, whose operands are strings (a marked mutable
character vector normalizes first, a cons list does not). The list and vector
families walk elements and therefore accept any mix of sequences.

Re-evaluation trigger: widening the string family means wrapping every argument
in a coercion. Inline `(coerce arg 'string)` per argument was rejected because it
plants two loops per argument at every `concatenate 'string` site — json.lisp /
url.lisp alone have dozens, and `.kb/wasm-function-body-size.md` is why one
emitted body must not grow without bound. If a real library needs it, add a
single cheap per-backend "sequence → string" primitive and wrap arguments in
THAT, rather than inlining the coercion.

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

## `--no-gc`

`NoGcWasmCompiler.compileConcatenate` builds strings in linear memory and that
backend has neither cons cells nor a general array type, so a non-string family
is now a compile error naming the offending designator instead of silently
producing a string (`.kb/no-gc-scalar-wasm.md`).

## Pinning

`ci-spec.yaml` `concatenate-result-families` (all four backends),
`LispEvaluatorTest#evalConcatenate*`,
`JvmLispCompilerTest#compileAndRunConcatenate*` +
`compileConcatenateWithComputedResultTypeFails`,
`WasmLispCompilerIntegrationTest#concatenateBuildsListAndVectorResultTypes`, and
`IroncladE2eTest` (the HKDF vector, end to end on four backends).
