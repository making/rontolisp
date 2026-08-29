# Hash tables

A table separates PLACEMENT from COMPARISON on every backend, which is what a hash table is: a key is placed by a structural hash and decided against the other keys in its bucket by the real `equal`. Nothing prints the key. Keying on the key's `prin1` TEXT (what the interpreter and the JVM did until `.todo/438`) cost the size of the key's whole printed graph on every lookup, and never terminated at all on a cyclic key.

**`equalp` is a KEY FOLD, and all four backends apply it (2026-08-28).** `equalp` on two values is `equal` on their folds, so one structural table carries both tests and no backend needs a second hash/compare pair -- which would have to be written four times, kept in step with the first, and tree-shaken separately on WASM. The fold, in every model: a string and a character fold to UPPER CASE one code point at a time, a float whose value is an INTEGER folds to that integer (`1`, `1.0` and `2/2` are one key, exact at any magnitude because the integer is read out of the float's `mantissa * 2^exponent` bits), a cons folds element-wise, everything else is its own key. It is capped at the same `HASH_DEPTH_CAP` levels the hash is, and by the same `HASH_WORK_CAP` node budget, for the same two reasons: a cyclic key must terminate and a SHARED one must not cost the exponentially many paths through it.

Two things deliberately do NOT fold, and both are real deviations from ANSI `equalp` tables:

- an ARRAY, because `equal` on a vector is identity on every backend and a folded copy would never find itself;
- a float with a FRACTION, which does not fold to the ratio it equals (`0.5` and `1/2` are two keys), because the WASM `TYPE_RATIO` holds two **i32** components and cannot represent the power-of-two denominator a float's exact value has -- folding it on the other three would split the backends rather than join them.

Both are a MISS, never a false match: the fold only ever declines to merge two keys `equalp` would call the same.

**The fold is also what is STORED**, so `maphash` (and `loop being the hash-keys`) hands back the representative -- `"CS"` for an entry written under `"cs"` -- on all four backends. That is forced by the design rather than chosen: a bucket decides by `equal` against the keys already in it, so the fold has to be the key that is there. Keeping the original as well would cost a second slot in every entry of every table (or a fold per comparison, plus one inside `FUNC_HASH_RESIZE`), for a difference from SBCL that only an enumeration can see.

Per backend, the fold and the flag that switches it on:

- **interpreter**: `LispHashTable` runs a key through `LispEquality.equalpKey` before it reaches the map when the table was made `:test 'equalp`.
- **JVM**: the fold is plain Java over the JVM value model in `runtime/RontoHashTable.equalpKey` -- a bytecode transcription would only be harder to keep in step with the interpreter's -- so that class TRAVELS beside a compiled program that makes an `equalp` table (`.kb/jvm-export.md`, "What travels"). The flag is a second reserved String key (`#equalp`) beside `#order`, so it collides with no `Integer` bucket key; `_hashKey(key, table)` folds when it is present, and `_hashGet`/`_hashPut`/`_hashRem` run every key through it. `_hashClr` reads the marker before the clear and hangs it back, or an emptied table would stop folding.
- **WASM**: `_equalp_key` (`WasmEqualpKeyRuntimeBuilder`, `FUNC_EQUALP_KEY`, appended after the last fixed helper so no index above shifts; an identity stub in a module that folds nothing). It calls `_string_upcase` / `_char_upcase` -- both always emitted, both dropped by the shaker in a module that does not reach them -- and builds the integer through `_int_new` / `_big_ash`. The flag rides in the LOW BIT of the header's count, which is stored as `entries * 2 + fold`: the header car stays an i31, so `hash-table-p`'s discrimination (an i31 count here, a dims array for the general array sharing the `TYPE_CELL` box) is untouched, and every count read shifts past the flag.

**The gate is `LispMacroExpander.programMakesEqualpHashTable`**, one scan shared by both compiled backends: a program that writes no `(make-hash-table :test 'equalp)` is emitted exactly as it was before the fold existed -- no fold call, no tagged count, no travelling class, no `(mut i32)` depth global, and the fixed `_equalp_key` slot holds an identity stub the shaker drops under the default `--optimize`. That is also why the compile paths read the test from the SOURCE: `make-hash-table`'s arguments are never evaluated there, so `:test` must be written literally (`'equalp` or `#'equalp`) to be seen; a test computed at run time leaves the table placing structurally. The interpreter evaluates the argument as usual. **Every count in a WASM module has to agree about whether the flag is there**, so the gate is one program-wide answer that also has to be carried into each top-level CHUNK context (`WasmAsyncEmit.freshCtx`) -- a chunk built without it counted in units of one while the printer read units of two.

This is the WIDENING half of the `:test` story; `.todo/012` is the NARROWING half (an `eql` table still matching structurally, which is why `hash-table-test` answers `EQUAL` and not `EQL` for one). Pinned by `LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`'s `*EqualpHashTable*` tests, the ci-spec case `equalp-hash-table-key-fold`, and `RontoHashTableEqualpKeyTest`, which folds one value in BOTH Java models and asserts the two representatives are the same value -- the two folds are written over different value models and cannot be one function.

**The hash is capped at `LispEquality.HASH_DEPTH_CAP` (64) levels of DEPTH** and folds a constant below it. That is free CORRECTNESS -- a hash need not be injective -- and it is what makes a cyclic key hashable. The cap may only ever be by depth/count, never by anything order- or address-dependent, or `equal` keys would stop hashing equal: two `equal` keys have the same shape, so a depth cap folds them identically (pinned by the ci-spec case `cyclic-hash-key`, whose 200- and 199-element list keys are both past the cap and still one key / two keys respectively).

**It is not free COST, and that is the second cap: `LispEquality.HASH_WORK_CAP` (4096) NODE VISITS across the whole traversal (2026-08-29).** A depth cap bounds the walk's HEIGHT and says nothing about its SIZE. The number of distinct root-to-leaf PATHS through a graph with SHARING is exponential in its height, so 64 levels is an astronomical amount of work the moment a key's substructure is shared or cyclic -- which a scene graph, a doubly-linked list, a parse tree with parent pointers and any ORM entity all are. Measured before the budget landed (Apple M4 Max, `java -jar`, reproduced on a compiled JVM class and on WASM, so it was never an interpreter defect): an `equal` table keyed by a DAG of n shared conses cost 2^n and the doubling was exact -- 3 ms at n = 20, 33 ms at n = 24, 130 ms at n = 26 -- and one `gethash` on an EMPTY table keyed by a node two links down a parent-linked chain was 61 ms at depth 1 and did not return in 55 s at depth 2. With the budget every one of those is 0 ms, at n = 60 and at depth 6.

The number was picked the way the depth cap was. The depth cap alone admits at most `2 * 64` = 128 nodes for a LINEAR key (a list, a string, a chain of instances), so 4096 is 32x what any key that is a list, a small tree, or an instance and its slots can cost -- none of them is truncated by it -- while it bounds one placement at a few thousand field reads, the same order as the ONE `equal` comparison the bucket scan then runs against the key it finds.

**The soundness argument is the depth cap's, and it is what dictates the budget's shape.** Two `equal` keys have the same shape, so a DETERMINISTIC traversal visits them in the same order, exhausts the budget in the same place, and they still hash equal. What the budget may NEVER be is order-of-insertion or address dependent, so it is REFILLED at the start of every top-level hash rather than carried between placements: what a key hashes to is a function of that key alone. It is also spent across the WHOLE traversal rather than handed down per branch -- a per-branch count bounds nothing when the branches share their substructure, which is the entire defect. Per backend:

- **interpreter**: `LispEquality.hash` threads a one-cell `int[]` the public `hash(v)` entry allocates.
- **JVM**: `_hash(key, depth, gas)` takes that cell as a third `[I` parameter, allocated at each placement site by `JvmHashRuntimeBuilder.emitKeyHash` (so `_hashGet`/`_hashPut`/`_hashRem` each get a fresh one).
- **WASM**: a second `(mut i32)` global immediately after the depth one and under the same gate (`hashGasGlobalIndex`) -- `_hash`'s signature is fixed at `((ref null eq)) -> i32`, so a budget cannot be passed in. The OUTERMOST entry, the one that finds the depth counter at zero, refills it; unlike the depth counter it is NOT restored on the way out.

**The budget costs nothing measurable.** `bench-report/programs/hash.lisp` (3.2M `gethash`/`puthash` on integer keys, best of six runs, Apple M4 Max): interpreter 1100 -> 1095 ms, compiled JVM class 104 -> 77 ms, WASM Preview 1 127 -> 111 ms. The extra work is one array allocation per placement on the JVM family -- next to the `Integer.valueOf(hash)` and the `Key` record each lookup already allocates -- and two global reads per node on WASM.

**The `equalp` key FOLD carries the same budget, for a sharper reason**: it BUILDS the structure it walks, so an unbudgeted fold of a shared key does not merely take exponential time, it allocates exponential space (a `:test 'equalp` `gethash` of a 26-cons DAG was an `OutOfMemoryError`). Same number, same refill rule, same three places: `LispEquality.equalpKey`, `RontoHashTable.FOLD_WORK_CAP` (pinned equal to `HASH_WORK_CAP` by `RontoHashTableEqualpKeyTest`), and a fourth WASM global beside the fold's depth one. What a wide key loses is exactly what a deep one loses -- the case- and number-insensitivity below the cut -- never a false match.

Pinned by `LispEvaluatorTest.hashTableSharedGraphKeysArePlacedInBoundedWork`, the same name with a `compile` prefix in `JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`, `RontoHashTableEqualpKeyTest.theWorkBudgetStopsBothFoldsOnASharedGraphKey`, and the ci-spec case `shared-graph-hash-key`. Every one of them asserts on a key whose un-budgeted hash could not finish inside the suite's lifetime, so a REGRESSION IS A HANG rather than a flaky number -- which is the only way to pin "terminates soon" without a wall-clock assertion.

**`equal` answers on IDENTITY before it recurses** (interpreter `LispEquality.equal`, JVM `_equal`'s leading `if_acmpne`, WASM `_equal`'s leading `ref.eq`). That is the other half of what makes a cyclic key usable: storing and retrieving under the SAME object terminates. Two DISTINCT cyclic structures compared with `equal` may still not terminate -- ANSI leaves that undefined and so does this implementation.

Interpreter/JVM use a real `LispHashTable`/`LinkedHashMap` (O(1)); WASM is a true open-chaining hash table (`WasmHashTableCompiler`), not the old O(n) alist. A table is a `TYPE_CELL` box (so `consp` is nil) holding a header `cons (count . buckets)`: `count` is an i31 of live entries (O(1) `hash-table-count`), `buckets` is a `TYPE_HASH_BUCKETS` array (`array (mut (ref null eq))`, index 33, a bare array comptype after `TYPE_CHAR`; implicitly `<: eq` so it stores in a cons field). Each bucket slot is a `(key . value)` alist or nil; a key's slot is `(_hash(key) & 0x7fffffff) % capacity`. General arrays share that box, so `hash-table-p` is a `ref.test TYPE_CELL` PLUS the header-car test that separates the two (an i31 count here, a dims array there) -- the same discrimination the printer makes.

Per backend, the pair is:

- **interpreter**: `LispEquality.hash` / `LispEquality.equal` in the ROOT package, next to each other precisely because they must agree; `LispHashTable`'s `LinkedHashMap` key is a private record carrying the value and its precomputed hash. The evaluator's `equal` built-in delegates to the same predicate, so the table and the predicate cannot drift. A cons and an instance are folded by `LispEquality` itself rather than through their own `hashCode`, which recurses without a bound.
- **JVM**: `_hash(key, depth)` (the remaining depth is the second parameter) and the recursive `_equal`. The `LinkedHashMap` is a BUCKET INDEX -- boxed `Integer` hash -> `ArrayList` of `Object[2]` pairs -- plus an insertion-order `ArrayList` of the same pairs hanging off the `#order` key, which no `Integer` bucket key can collide with. Re-storing an existing key mutates the pair in place, so the order list needs no maintenance and the entry keeps its first position. That shape is declared ONCE, in `runtime/RontoHashTable`, and is read by both the emitter (`JvmHashRuntimeBuilder`) and the hand-written runtimes that build a table for emitted code (`RontoHttpClack`'s `:headers`) -- a plain `HashMap`, or a table built the old way, fails at the first `gethash`. A BUCKET is created with room for one entry (`new ArrayList<>(1)`, on both sides), not with `ArrayList`'s default ten: a bucket holds exactly one pair unless two structurally distinct keys hash alike, and 56 bytes of backing array per bucket against 24 is the allocation profile of a table with a million keys.
- **WASM**: `FUNC_HASH` + `_equal`, which already had this architecture; its share of `.todo/438` was the cap alone. `_hash` counts its own live recursion depth in a `(mut i32)` global (its signature is fixed at `((ref null eq)) -> i32`, so a budget cannot be passed in), incremented on entry and restored on exit. The global and the guard are emitted only for a program that uses a hash table, so every other module is BYTE-IDENTICAL to a pre-cap build (verified: a `(print (+ 1 2))` module is unchanged; a hash-using module grows 31 bytes). If the source scan under-predicts the gate, `_hash` simply keeps its uncapped recursion.

**Invariant**: `_hash` (`FUNC_HASH`, always emitted, signature `((ref null eq)) -> i32` = `TYPE_RAT_GET`) must agree with `_equal` — equal keys hash equal; it folds i31/char/string-content-bytes/float-bits/ratio and recurses on conses, with a constant-0 fallback for identity-compared values (e.g. closures). Strings/symbols fold their content bytes (`h = h*31 + byte`) because `_equal` compares string content via `_string_eq` (not interned offsets), so runtime-built strings work as keys equal to literals (see `.kb/json.md`). Keys are still compared with `_equal` within a bucket. An INSTANCE key follows the same
rule: `equal` on instances is structural (`.kb/instance-syntax.md`), so every backend
folds the layout plus the slot hashes -- the layout TAG on the interpreter, the interned
layout array's identity on the JVM (which is what `_equal` compares it by), the layout
address on WASM -- and two separately constructed instances with equal slots find each
other in an `equal` table on all four backends (pinned by the ci-spec case
`instance-print-syntax-and-identity`). Structural is also what makes an instance the
WORST CASE for the work budget above: an object that holds a BACK-REFERENCE -- a parent
pointer, an owner, a `previous` link -- reaches its whole graph from every slot, and each
level of the chain adds several cons levels of fan-out on top, so it goes exponential
faster than a bare cons DAG does. That is the shape `.todo/563`'s viewer hit. A GENERAL ARRAY key is the opposite case and agrees
just as widely: `equal` on a vector is identity, so the hash is an identity hash (0 on
WASM) and two distinct vectors with equal elements are two keys. `puthash` grows (doubles, `FUNC_HASH_RESIZE`) past load factor 0.75. `FUNC_HASH`/`FUNC_HASH_RESIZE` sit just before `FUNC_USER_BASE`; both are present in Preview 1 and `--component` (no import/`FUNC_START` index shift, so the component blobs are unaffected). `maphash` order is unspecified (README) -- interpreter and JVM both walk insertion order (the JVM through the `#order` list above, which is why the bucket index may reorder freely), WASM walks bucket order.

**Printing**: a table prints as SBCL's unreadable tag MINUS its trailing identity hash --
`#<HASH-TABLE :TEST EQUAL :COUNT n>` (`EQUALP` for a folding table) on all four backends, through
`print`/`princ`/`prin1`/`princ-to-string`/`format ~A`/`~S`, nested positions included
(pinned by the ci-spec case `hash-table-print-syntax` plus one test per backend). Still no
entry content. The two fields are exactly the two SBCL details that can be printed here
without lying or drifting:

- `:TEST` is the test LOOKUP IMPLEMENTS, which is `EQUALP` for a table whose keys are
  folded and `EQUAL` for every other one -- an `eql` table still places structurally
  (`.todo/012`), so printing the REQUESTED test would describe behavior that does not
  exist. It is the same answer `hash-table-test` gives, from the same place on each
  backend: `LispHashTable.equalpTest()`, `_hashEqp` (the JVM marker), the header count's
  low bit (WASM). The two tags are two whole constants
  (`LispHashTable.HASH_TABLE_PREFIX`, `HASH_TABLE_PREFIX_EQUALP`) rather than one
  assembled at run time, so a program with no `equalp` table interns only the one it can
  print and its printer keeps the branch-free shape it had.
- `:COUNT` is the live entry count, the same O(1) number `hash-table-count` reads, taken
  from the same place on each backend so the two cannot disagree.

SBCL's trailing `{1004F8E1C3}` is deliberately absent: an identity hash is text that
varies between runs of one program (`.kb/emitted-output-determinism.md`).

The prefix is one constant, `LispHashTable.HASH_TABLE_PREFIX`, which both compilers intern
rather than re-spell. Each backend reaches the tag its own way and each way was a defect
before `.todo/430`:

- interpreter: `LispHashTable.print()`, counting `map.size()`.
- JVM: the count comes from `_hashSize`, the same helper `hash-table-count` reads (the map's own `size()` counts BUCKETS, not entries). The arm is keyed on
  `JvmHashRuntimeBuilder.MAP_CLASS` = `java.util.LinkedHashMap`, deliberately NOT the
  plain `HashMap` a `java:` call can hand back. That class is the discriminator `_hashP`
  and the printer share, so a host map stays a host object (`hash-table-p` nil, printed
  `#<java java.util.HashMap>`, both matching the interpreter) instead of impersonating a
  Lisp table; being LINKED is the other half of the choice, and is why JVM `maphash`
  walks insertion order like the interpreter. Before the arm existed a table fell through
  to `Object.toString` and printed Java's own map syntax -- braces, the raw `Object[]`
  entry pair, and an identity hash.
- WASM: the non-array arm of the shared `TYPE_CELL` branch
  (`WasmRuntimeBuilder.emitPrintArray`, used by `_print_val` and `_princ_val` alike).
  **That branch must RETURN for every cell.** A cell that falls out of it lands in the
  cons tail, which prints `" . "` and re-enters the printer on the SAME value: unbounded
  recursion, i.e. an unrecoverable `call stack exhausted` trap that also loses the stdout
  buffered before it. That is why the arm answers for any cell, not only for one that
  passes the full `hash-table-p` test. The count follows the same rule: it is emitted only
  behind a `ref.test i31` on the header car, so a cell that is not a table prints the tag
  with a `0` count instead of trapping on the `i31.get_s`. `FUNC_PRINT_I32_NO_NL` writes
  the digits -- the function the same arm already calls for an array's rank, so no new
  function index and no shift in the component blobs.

## `with-hash-table-iterator`

The CLHS macro is a MACRO here in the same lite direction `with-package-iterator` is: the
name is bound by `flet` to a local FUNCTION, not by CL's `macrolet`, so the iterator can
also be passed as a value. `LispMacroExpander.expandWithHashTableIterator` lowers
`(with-hash-table-iterator (name table) body...)` to a `let` over a SNAPSHOT alist -- the
same `(let ((acc nil)) (maphash (lambda (k v) (setq acc (cons (cons k v) acc))) TABLE) acc)`
walk `loop`'s `being the hash-keys` clause uses -- plus an `flet` whose body pops one entry
per call and answers `(values t key value)`, or `(values nil nil nil)` once exhausted.
Snapshotting is what keeps the macro free of a per-backend hash cursor: the expansion is
ordinary `let`/`flet`/`maphash` all four backends already compile, so the evaluator, both
compilers and the fold pass only need the one-line dispatch each
(`LispEvaluator.evalCons`, `Jvm`/`WasmExprCompiler.compileCons`, `PureBuiltinFolder`'s
name-headed-spec arm). The cost is the lite part: an entry added or removed DURING the
walk is not seen, which CLHS leaves undefined anyway.

The internal variables are named after the ITERATOR (`__whti_<name>`, `..._acc`, `..._k`,
`..._v`, `..._e`) rather than gensyms. Two reasons, and both matter: nesting two iterators
then shadows exactly as the iterator names do, and the emitted form stays identical across
backends and across runs (`.kb/emitted-output-determinism.md`), which a global gensym
counter would not.

Added 2026-08-27 for iterate's `(for ... in-hashtable ...)` clause, which wraps the whole
loop body in it. Tests: `LispEvaluatorTest.evalWithHashTableIterator`,
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`'s
`*SharpLAndCommaDotAndWithHashTableIterator`, ci-spec
`sharp-l-comma-dot-and-hash-table-iterator`.
