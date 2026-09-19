# `eq` is `eql`: numbers and characters compare by type and value

**Invariant (all four backends):** `eq` and `eql` are one predicate. Aggregates (cons,
instance, allocated string, closure, array, hash table) compare by reference; symbols by
identity (interned offset on wasm); a number or a character by type and value. `-0.0` and
`0.0` stay distinct, a NaN is `eq` to itself, `(eq 3.0 3)` is NIL. Pinned by `ci-spec.yaml`
`eq-on-numbers-is-eql` and `scheme-spec.yaml` `eq-on-numbers-is-eqv` (Scheme `eq?`, `memq`,
`assq` lower to `eq`).

## Why not box identity (SBCL's answer)

CLHS (`eq`, its notes and examples) leaves `eq` on numbers and characters implementation-dependent
(a number may be copied at any time). Before 2026-09-19 the backends split: the interpreter
and the JVM answered NIL for any two floats or ratios -- even `(eq x x)` -- and wasm answered
box identity (`(eq x x)` T, two equal computed floats NIL); complex was T on the interpreter
and the JVM and NIL on wasm; SBCL answers T for `(eq x x)` and NIL for two separately
computed doubles (a single-float is immediate there, so T).

Identity cannot be made to agree: the JVM keeps a declared `double-float` local unboxed and
re-boxes it at every use (`(same y y)` compiles to two `Double.valueOf`), wasm keeps one
struct, the wasm no-gc and int-fusion paths hold raw scalars. An identity answer would
depend on the optimizer. Value equality is what every backend can compute from the value
alone, and it is what the backends already did for bignums and characters.

## Mechanics

- Interpreter: `LispEquality.eq` delegates to `eql`; `LispHashTable`'s `TEST_EQ` compares with it
  and hashes a number by value, as for `eql`.
- JVM: `eq` and `eql` sites share the per-class `_pEql` helper over `_eqv`; the `_eq` numeric op is
  gone. `JvmHashRuntimeBuilder.emitTestCompare` sends test code >= 2 (eql, eq) to `_eqv`.
- WASM: `WasmEmitHelper.emitEqlComparison` is every site (`eq`, `eql`, the conditional fusion,
  `catch` tags, `remf`, the hash bucket scan): `ref.eq` inline, then inline the symbol/string
  offset compare and the i31 miss (an i31 is eql only to itself), then one call to `_eql_tail`
  (`FUNC_EQL_TAIL`, `WasmRuntimeBuilder.buildEqlTailBody`: float, char, bignum, bigint, ratio,
  complex). In a module with none of those boxes the fold makes the tail `i32.const 0` and
  `WasmPeephole` removes every call of it (`.kb/optimize-dead-code-elimination.md`).

## Cost (measured 2026-09-19)

Programs with no `eq`/`eql` site are byte-identical. WASM, raw bytes vs. before: `calc` -0.70%,
`huffman` -0.97%, `word-frequency` -0.71%, `sorting` -0.48%, `evaluator` -0.26%, `zlib` +0.07%,
`contact-book` +0.06%, `maze-rl` +0.07%. JVM jars shrink 33-75 B (one helper instead of two).

Speed, wasmtime, best of 10, 3M iterations of a 10-element list search: symbol `eq` misses
unchanged (374 vs 376 ms); fixnum `eql` misses 12-15% faster (223 -> 197; 218 -> 185 in a module
with floats) from the inline i31 exit; float `eql` misses 5-10% slower (632 -> 664) from the call
into `_eql_tail`. JVM unchanged within noise.
