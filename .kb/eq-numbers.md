# `eq` is `eql`: numbers and characters compare by type and value

**Invariant (all four backends):** `eq` and `eql` are one predicate. Aggregates (cons,
instance, allocated string, closure, array, hash table) compare by reference; symbols by
identity (interned offset on wasm); a number or a character by type and value. `-0.0` and
`0.0` stay distinct, a NaN is `eq` to itself, `(eq 3.0 3)` is NIL. Pinned by `ci-spec.yaml`
`eq-on-numbers-is-eql` and `scheme-spec.yaml` `eq-on-numbers-is-eqv` (Scheme `eq?`, `memq`,
`assq` lower to `eq`).

## Host objects (interpreter and JVM; wasm has none)

**`eq`/`eql` on a `java:` host object is identity; `equal`/`equalp` ask its `equals`.**
Clojure's `identical?` (`==`) and SBCL's eq/eql are the oracles; `=` (oracle `Util.equiv`)
asks `equals`, so `equal` keeps doing it (a host collection included).

- Interpreter: `LispEquality.eql` compares two `LispJavaObject`s by `ref() ==` (the wrapper is
  fresh per Java call, its record `equals` is `ref.equals` and stays `equal`'s answer);
  `LispHashTable.Key.placementHash` hashes one by `identityHashCode(ref)` in an eq/eql table.
- JVM, in a `java:` program only (`hostTest` = `JvmJavaDirectSites.host()`, `_jhost`; a
  program without `java:` emits the old bodies): `_eqv`'s final `equals` arm sends a host to
  identity, `_equal` asks a host's `equals` before delegating to `_eqv`, `_hash` uses a host's
  own `hashCode` (a host `ArrayList`/`Map` was identity-hashed and identity-compared under
  `equal`: two empty host lists were `equal` T interpreted, NIL compiled), and
  `JvmHashRuntimeBuilder.emitKeyHash` identity-hashes a host in an eq/eql table.
- `equalp`'s last arm (`LispPreludeLibrary`, all four backends) is `equal`, not `eql`: same
  answer for every Lisp value reaching it, `equals` for a host -- what an equalp table
  (fold leaves a host as is, then `equal`) already answered.
- `%remf-tail` compared with the key's own `equals` on both backends (interpreter: two fresh
  `"s"` one key; JVM: a nil key `NullPointerException`, a character never found, a host by
  `equals`). Both now go through eq (`Environment.isEqStrict`, the JVM `_pEql` helper via
  `JvmEqGeneralCompiler.emitCall`). Pins: `RemfIndicatorFixture` (interpreter, JVM, wasm).
- Before (measured 2026-10-04): `(eq (java:new "java.io.File" "x") (java:new "java.io.File"
  "x"))` T on both; on the JVM the LEFT operand's `equals` decided, so a reify/proxy whose
  `equals` answers true was `eq` to `t` and `1` and printed as `true` (the printer's first
  arm is `(eq x t)`); a mutated host key was lost in an eq table on both.
- What relied on `equals` (measured 2026-10-04, an interpreter probe logging every eql of two
  distinct `equals`-equal hosts): none of the 89 shcloj4 probes, `examples/jvm/*` or
  `examples/clojure/demo.clj`; no existing test failed. In-tree: `equalp`'s last arm and
  the two `%remf-tail` walks above.
- Cost (2026-10-04): programs without `java:` are byte-identical on the JVM and wasm except
  `equalp`'s method: JVM +13 B of code (+7..18 B per class: `str-demo`, `postmodern-*`,
  `httpbin-ningle`), wasm -56..64 B. A `java:` program grows by the host arms and `_jhost`
  (+28..470 B: `java-interop.lisp` +317, `swing.lisp` +210). Speed: 3M x 20 `eql` over
  symbols and fixnums, best of 10, JVM 201-219 ms before and after, with and without
  `java:`; the interpreter within noise.
- Pins: `JavaInteropPrograms.HOST_IDENTITY_PROGRAM` (`JavaInteropTest` /
  `JvmJavaInteropCompilerTest#eqAndEqlOnHostObjectsAreIdentity`),
  `ClojureInteropTest#identicalOnHostObjectsIsIdentity` (oracle-identical).

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
  complex; and, in a `--native` `objc:` program only, two Objective-C pointers by address --
  [objc.md](objc.md), "--native"). In a module with none of those boxes the fold makes the tail `i32.const 0` and
  `WasmPeephole` removes every call of it (`.kb/optimize-dead-code-elimination.md`).

## Cost (measured 2026-09-19)

Programs with no `eq`/`eql` site are byte-identical. WASM, raw bytes vs. before: `calc` -0.70%,
`huffman` -0.97%, `word-frequency` -0.71%, `sorting` -0.48%, `evaluator` -0.26%, `zlib` +0.07%,
`contact-book` +0.06%, `maze-rl` +0.07%. JVM jars shrink 33-75 B (one helper instead of two).

Speed, wasmtime, best of 10, 3M iterations of a 10-element list search: symbol `eq` misses
unchanged (374 vs 376 ms); fixnum `eql` misses 12-15% faster (223 -> 197; 218 -> 185 in a module
with floats) from the inline i31 exit; float `eql` misses 5-10% slower (632 -> 664) from the call
into `_eql_tail`. JVM unchanged within noise.
