# Hash tables

A table separates PLACEMENT (a hash agreeing with the test) from COMPARISON (the
test's predicate within the bucket) on every backend. Nothing prints the key —
keying on the key's `prin1` TEXT cost the whole printed graph per lookup and never
terminated on a cyclic key.

## The four tests

`LispHashTable.TEST_*` codes (0 equal, 1 equalp, 2 eql, 3 eq) are what every
backend agrees on; `LispMacroExpander.hashTableTestCode` reads a literal `:test`
(`'eq` or `#'eq`, and the same for the other three) off a `make-hash-table` form,
and `programMakesIdentityHashTable` gates the identity machinery beside
`programMakesEqualpHashTable`'s fold gate. A computed `:test` places as `equal`,
like an unwritten one.

- `equal`: the structural hash plus real `equal` in the bucket.
- `equalp`: the `equalp` key fold below, then the same pair.
- `eql`/`eq`: the `eql`/`eq` predicate (`LispEquality.eql`/`eq`) in the bucket.
  Aggregates (conses, instances) hash by identity (`System.identityHashCode` on
  the interpreter/JVM, the shared bucket 0 on WASM), so a key mutated after
  insertion keeps its bucket; every other value hashes structurally exactly as an
  `equal` table hashes it, which the value-compared `eql`/`eq` on
  numbers/symbols/strings agrees with. Only the performance characteristic differs
  on WASM (one shared bucket for aggregates, i.e. a `ref.eq` scan); the semantics
  are identical on all four backends.

## `equalp` is a KEY FOLD, on all four backends
`equalp` on two values is `equal` on their folds, so one structural table carries both tests.
Fold: string/character to UPPER CASE code point by code point; a float whose value is an INTEGER to
that integer (`1`, `1.0`, `2/2` are one key, read out of `mantissa * 2^exponent`); a cons
element-wise; everything else is its own key. Two deliberate ANSI deviations, both a MISS and never
a false match: an ARRAY does not fold (`equal` on a vector is identity); a float with a FRACTION
does not fold to the ratio it equals (WASM `TYPE_RATIO` holds two **i32** components).
**The fold is also what is STORED**, so `maphash` hands back the representative.

- interpreter `LispHashTable` via `LispEquality.equalpKey`.
- JVM `runtime/RontoHashTable.equalpKey`, so that class TRAVELS beside a compiled program making an
  `equalp` table (`.kb/jvm-export.md`). Marker = reserved String key `#equalp` beside `#order`
  (`#eql`/`#eq` for identity tables); `_hashKey` folds and `_hashGet`/`_hashPut`/`_hashRem`
  run every key through it, then compare and hash by `_hashTest`. **Trap**:
  `_hashClr` must read the markers before the clear and hang them back.
- WASM `_equalp_key` (`WasmEqualpKeyRuntimeBuilder`, `FUNC_EQUALP_KEY`, appended after the last
  fixed helper so no index shifts). The tag rides in the LOW TWO BITS of the header count, stored as
  `entries * 4 + test` (0 equal, 1 equalp, 2 eql, 3 eq), so the header car stays an i31; every
  count read shifts past it. An eql/eq aggregate key hashes to the shared bucket 0 and is
  compared with the eql/eq comparison inlined at the bucket scan (no new `FUNC_*`
  index); `_hash_resize` reads the tag off the header it already takes.

**Gate: `LispMacroExpander.programMakesEqualpHashTable`**, one scan shared by both compiled
backends. `:test` must be written LITERALLY (`'equalp`/`#'equalp`) — the compile paths read it from
the source; the interpreter evaluates it. **Every count in a WASM module must agree about whether
the tag is there**, so the gate is carried into each top-level CHUNK context
(`WasmAsyncEmit.freshCtx`). The identity half rides beside it:
`programMakesIdentityHashTable` (`'eq`/`'eql`, same literal rule), and a tagged
WASM count carries the two-bit code (`entries * 4 + test`) while an untagged one
stays the plain entry count. Pinned by the
`*EqualpHashTable*` tests in `LispEvaluatorTest`/`JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest`, ci-spec `equalp-hash-table-key-fold` and
`hash-table-identity-test`, and
`RontoHashTableEqualpKeyTest`.

## The two caps
**Depth: `LispEquality.HASH_DEPTH_CAP` (64)**, folding a constant below it — what makes a cyclic
key hashable. **Work: `LispEquality.HASH_WORK_CAP` (4096) NODE VISITS across the whole traversal** —
a depth cap bounds HEIGHT, not SIZE, and paths through a shared graph are exponential in height (an
`equal` table keyed by a DAG of n shared conses cost 2^n; one `gethash` two links down a
parent-linked chain did not return in 55 s). 4096 is 32x the at-most `2 * 64` nodes a LINEAR key
can reach. A cap may only ever be by depth/count, never anything order- or address-dependent, or
`equal` keys would stop hashing equal. The budget is REFILLED at the start of every top-level hash
and spent across the WHOLE traversal, never handed down per branch.

- interpreter: a one-cell `int[]` threaded by `LispEquality.hash`.
- JVM: `_hash(key, depth, gas)` third `[I` parameter, allocated per placement site by
  `JvmHashRuntimeBuilder.emitKeyHash`.
- WASM: a second `(mut i32)` global after the depth one (`hashGasGlobalIndex`) — `_hash`'s
  signature is fixed at `((ref null eq)) -> i32`. The outermost entry refills it; unlike the depth
  counter it is NOT restored on the way out.

**The `equalp` fold carries the same budget**, more sharply: it BUILDS the structure it walks (an
unbudgeted `equalp` `gethash` of a 26-cons DAG was an `OutOfMemoryError`). Same number, same refill
rule, in `LispEquality.equalpKey`, `RontoHashTable.FOLD_WORK_CAP` (pinned equal to
`HASH_WORK_CAP`) and a fourth WASM global.

**`equal` answers on IDENTITY before it recurses** (`LispEquality.equal`, JVM `_equal`'s leading
`if_acmpne`, WASM `_equal`'s leading `ref.eq`), so store-and-retrieve under the SAME object
terminates. Two DISTINCT cyclic structures compared with `equal` may still not terminate (ANSI
leaves it undefined; so does this).

Pinned by ci-spec `cyclic-hash-key` and `shared-graph-hash-key`,
`LispEvaluatorTest.hashTableSharedGraphKeysArePlacedInBoundedWork` (same name with a `compile`
prefix on both compilers), `RontoHashTableEqualpKeyTest.theWorkBudgetStopsBothFoldsOnASharedGraphKey`.
A REGRESSION IS A HANG, not a flaky number.

## Representation
Interpreter/JVM use a real `LispHashTable`/`LinkedHashMap`; WASM is a true open-chaining table
(`WasmHashTableCompiler`), not an alist: a `TYPE_CELL` box (so `consp` is nil) holding a header
`cons (count . buckets)`, `count` an i31 of live entries, `buckets` a `TYPE_HASH_BUCKETS` array
(`array (mut (ref null eq))`, index 33, bare comptype after `TYPE_CHAR`); slot =
`(_hash(key) & 0x7fffffff) % capacity`, each slot a `(key . value)` alist or nil. General arrays
share the box, so `hash-table-p` is `ref.test TYPE_CELL` PLUS the header-car test.

- `LispEquality.hash` / `.equal` sit in the ROOT package next to each other because they must
  agree; conses and instances are folded by `LispEquality` itself, not their own `hashCode`.
- JVM: the `LinkedHashMap` is a BUCKET INDEX (boxed `Integer` hash -> `ArrayList` of `Object[2]`)
  plus an insertion-order `ArrayList` under `#order`; re-storing mutates the pair in place. The
  shape is declared ONCE in `runtime/RontoHashTable` and read by `JvmHashRuntimeBuilder` and the
  hand-written runtimes (`RontoHttpClack`'s `:headers`) — a plain `HashMap` fails at the first
  `gethash`. Buckets are `new ArrayList<>(1)`, not the default ten.
- WASM `FUNC_HASH` must agree with `_equal` (equal keys hash equal); signature
  `((ref null eq)) -> i32` = `TYPE_RAT_GET`, always emitted. Strings/symbols fold content bytes
  (`h = h*31 + byte`) because `_equal` compares via `_string_eq`, not interned offsets
  (`.kb/json.md`); constant-0 fallback for identity-compared values. The depth global is emitted
  only for a hash-using program (+31 bytes; other modules stay BYTE-IDENTICAL).
- An INSTANCE key folds layout + slot hashes (layout TAG / interned layout array identity / layout
  address), so two separately built instances with equal slots find each other in an
  `equal` table (ci-spec `instance-print-syntax-and-identity`) -- and a BACK-REFERENCE
  makes it the WORST CASE for the work budget. In an `eql`/`eq` table the same key
  hashes by identity instead, so the two instances are two keys. A GENERAL ARRAY key
  is the opposite: `equal` is identity, hash is an identity hash. On the JVM a general
  vector is an `ArrayList`, whose own `hashCode` walks the elements: `_hash` asks
  `System.identityHashCode` for it explicitly. Before that a vector holding itself
  overflowed the stack as a key and a vector grown after it was stored lost its entry
  (`JvmLispCompilerTest#compileAndRunAVectorKeyHashesByIdentity`).
- `puthash` doubles (`FUNC_HASH_RESIZE`) past load factor 0.75; both funcs sit just before
  `FUNC_USER_BASE` in Preview 1 and `--component`. `maphash` order is unspecified: interpreter and
  JVM walk insertion order (JVM through `#order`, which is why the bucket index may reorder
  freely), WASM bucket order.

## Printing
`#<HASH-TABLE :TEST EQUAL :COUNT n>` (`EQUALP`/`EQL`/`EQ` for those tables) on all four backends through
`print`/`princ`/`prin1`/`princ-to-string`/`format ~A`/`~S`, nested included (ci-spec
`hash-table-print-syntax`, `hash-table-identity-test`). No entry content; SBCL's trailing identity hash is deliberately absent
(`.kb/emitted-output-determinism.md`).

- `:TEST` is the test LOOKUP IMPLEMENTS, from the same place per backend as `hash-table-test`:
  `LispHashTable.testCode()`, `_hashTest`, the header count's low two bits. One whole constant
  per test, not one assembled at run time.
- `:COUNT` is the O(1) live count (`_hashSize` on the JVM — the map's own `size()` counts BUCKETS).
- JVM: the printer arm is keyed on `JvmHashRuntimeBuilder.MAP_CLASS` = `java.util.LinkedHashMap`,
  deliberately NOT the plain `HashMap` a `java:` call can hand back — that class is the
  discriminator `_hashP` and the printer share, so a host map stays a host object.
- WASM: the non-array arm of the shared `TYPE_CELL` branch (`WasmRuntimeBuilder.emitPrintArray`).
  **That branch must RETURN for every cell** — a cell falling out lands in the cons tail, prints
  `" . "` and re-enters the printer on the SAME value: unbounded recursion, an unrecoverable
  `call stack exhausted` trap that also loses buffered stdout. Hence it answers for any cell, and
  the count sits behind a `ref.test i31` so a non-table cell prints `0` instead of trapping.

## `with-hash-table-iterator`
A MACRO here, like `with-package-iterator`: the name is bound by `flet` to a local FUNCTION, not
CL's `macrolet`, so the iterator can also be passed as a value.
`LispMacroExpander.expandWithHashTableIterator` lowers to a `let` over a SNAPSHOT alist (the same
`maphash` accumulation `loop`'s `being the hash-keys` uses) plus an `flet` answering
`(values t key value)`, or `(values nil nil nil)` when exhausted. Snapshotting avoids a per-backend
cursor: one dispatch line each in `LispEvaluator.evalCons`, `Jvm`/`WasmExprCompiler.compileCons`,
`PureBuiltinFolder`'s name-headed-spec arm. An entry added or removed DURING the walk is not seen.
Internal variables are named after the ITERATOR (`__whti_<name>`, `_acc`, `_k`, `_v`, `_e`), not
gensyms, so nesting shadows as the iterator names do and the emitted form is identical across
backends and runs.

Tests: `LispEvaluatorTest.evalWithHashTableIterator`, `JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest`'s `*SharpLAndCommaDotAndWithHashTableIterator`, ci-spec
`sharp-l-comma-dot-and-hash-table-iterator`.
