# Hash tables

A table separates PLACEMENT (structural hash) from COMPARISON (real `equal` within the
bucket) on every backend. Nothing prints the key -- keying on the key's `prin1` TEXT cost
the whole printed graph per lookup and never terminated on a cyclic key.

## `equalp` is a KEY FOLD, on all four backends
`equalp` on two values is `equal` on their folds, so one structural table carries both
tests. The fold, in every model: string and character fold to UPPER CASE one code point at
a time; a float whose value is an INTEGER folds to that integer (`1`, `1.0`, `2/2` are one
key -- exact at any magnitude, read out of the float's `mantissa * 2^exponent` bits); a
cons folds element-wise; everything else is its own key. Capped by `HASH_DEPTH_CAP` and
the `HASH_WORK_CAP` node budget (below).

Two deliberate deviations from ANSI `equalp` tables, both a MISS and never a false match:
- an ARRAY does not fold -- `equal` on a vector is identity, so a folded copy would never
  find itself;
- a float with a FRACTION does not fold to the ratio it equals (`0.5` and `1/2` are two
  keys) -- the WASM `TYPE_RATIO` holds two **i32** components and cannot represent a
  float's power-of-two denominator, so folding it elsewhere would split the backends.

**The fold is also what is STORED**, so `maphash` (and `loop being the hash-keys`) hands
back the representative (`"CS"` for an entry written under `"cs"`) on all four backends.

- **interpreter**: `LispHashTable` runs a key through `LispEquality.equalpKey` for a
  `:test 'equalp` table.
- **JVM**: `runtime/RontoHashTable.equalpKey`, plain Java over the JVM value model, so that
  class TRAVELS beside a compiled program making an `equalp` table (`.kb/jvm-export.md`,
  "What travels"). The flag is a reserved String key `#equalp` beside `#order` (no
  `Integer` bucket key collides); `_hashKey(key, table)` folds when present, and
  `_hashGet`/`_hashPut`/`_hashRem` run every key through it. Trap: `_hashClr` must read
  the marker before the clear and hang it back, or an emptied table stops folding.
- **WASM**: `_equalp_key` (`WasmEqualpKeyRuntimeBuilder`, `FUNC_EQUALP_KEY`, appended after
  the last fixed helper so no index shifts; an identity stub where nothing folds). Calls
  `_string_upcase` / `_char_upcase`, builds the integer through `_int_new` / `_big_ash`.
  The flag rides in the LOW BIT of the header count, stored as `entries * 2 + fold`, so
  the header car stays an i31 and `hash-table-p`'s discrimination is untouched; every count
  read shifts past it.

**The gate is `LispMacroExpander.programMakesEqualpHashTable`**, one scan shared by both
compiled backends: a program with no `(make-hash-table :test 'equalp)` emits no fold call,
no tagged count, no travelling class, no `(mut i32)` depth global, and its identity
`_equalp_key` stub is shaken under `--optimize`. The compile paths read the test from the
SOURCE (arguments are never evaluated there), so `:test` must be written literally
(`'equalp`/`#'equalp`); a run-time test leaves the table placing structurally. The
interpreter evaluates the argument as usual. **Every count in a WASM module must agree
about whether the flag is there**, so the gate is carried into each top-level CHUNK context
(`WasmAsyncEmit.freshCtx`) -- a chunk built without it counted in units of one while the
printer read units of two.

This is the WIDENING half of the `:test` story; the NARROWING half is an `eql` table still
matching structurally, which is why `hash-table-test` answers `EQUAL`, not `EQL`. Pinned by
the `*EqualpHashTable*` tests in
`LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`, ci-spec
`equalp-hash-table-key-fold`, and `RontoHashTableEqualpKeyTest`, which folds one value in
BOTH Java models and asserts the representatives match.

## The two caps
**Depth: `LispEquality.HASH_DEPTH_CAP` (64) levels**, folding a constant below it -- free
correctness (a hash need not be injective) and what makes a cyclic key hashable. A cap may
only ever be by depth/count, never anything order- or address-dependent, or `equal` keys
would stop hashing equal. Pinned by ci-spec `cyclic-hash-key` (200- and 199-element list
keys, both past the cap, one key / two keys respectively).

**Work: `LispEquality.HASH_WORK_CAP` (4096) NODE VISITS across the whole traversal.** A
depth cap bounds HEIGHT, not SIZE; root-to-leaf paths through a shared graph are
exponential in height -- a scene graph, a doubly-linked list, a parse tree with parent
pointers, any ORM entity. Un-budgeted, an `equal` table keyed by a DAG of n shared conses
cost exactly 2^n, and one `gethash` on an EMPTY table keyed two links down a parent-linked
chain did not return in 55 s. 4096 is 32x the at-most `2 * 64` = 128 nodes a LINEAR key can
reach (so no list, string or instance chain is truncated) while bounding one placement at
the same order as the ONE `equal` comparison the bucket scan then runs.

The budget must NEVER be insertion-order or address dependent, so it is REFILLED at the
start of every top-level hash: what a key hashes to is a function of that key alone. It is
spent across the WHOLE traversal, never handed down per branch -- a per-branch count bounds
nothing when branches share substructure.

- **interpreter**: `LispEquality.hash` threads a one-cell `int[]` allocated by `hash(v)`.
- **JVM**: `_hash(key, depth, gas)` takes it as a third `[I` parameter, allocated per
  placement site by `JvmHashRuntimeBuilder.emitKeyHash`.
- **WASM**: a second `(mut i32)` global right after the depth one, same gate
  (`hashGasGlobalIndex`) -- `_hash`'s signature is fixed at `((ref null eq)) -> i32`, so a
  budget cannot be passed in. The OUTERMOST entry (depth counter at zero) refills it;
  unlike the depth counter it is NOT restored on the way out.

Cost is one array allocation per placement on the JVM family, two global reads per node on
WASM -- nothing measurable.

**The `equalp` fold carries the same budget**, and more sharply: it BUILDS the structure it
walks, so an unbudgeted fold of a shared key allocates exponential space (a `:test 'equalp`
`gethash` of a 26-cons DAG was an `OutOfMemoryError`). Same number, same refill rule, three
places: `LispEquality.equalpKey`, `RontoHashTable.FOLD_WORK_CAP` (pinned equal to
`HASH_WORK_CAP` by `RontoHashTableEqualpKeyTest`), and a fourth WASM global beside the
fold's depth one. A truncated key loses case- and number-insensitivity below the cut, never
gains a false match.

Pinned by `LispEvaluatorTest.hashTableSharedGraphKeysArePlacedInBoundedWork`, the same name
with a `compile` prefix in `JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`,
`RontoHashTableEqualpKeyTest.theWorkBudgetStopsBothFoldsOnASharedGraphKey`, ci-spec
`shared-graph-hash-key`. Each uses a key whose un-budgeted hash could not finish inside the
suite's lifetime, so a REGRESSION IS A HANG rather than a flaky number.

**`equal` answers on IDENTITY before it recurses** (interpreter `LispEquality.equal`, JVM
`_equal`'s leading `if_acmpne`, WASM `_equal`'s leading `ref.eq`), so storing and
retrieving under the SAME object terminates. Two DISTINCT cyclic structures compared with
`equal` may still not terminate (ANSI leaves it undefined; so does this).

## Representation
Interpreter/JVM use a real `LispHashTable`/`LinkedHashMap` (O(1)); WASM is a true
open-chaining table (`WasmHashTableCompiler`), not an alist. A WASM table is a `TYPE_CELL`
box (so `consp` is nil) holding a header `cons (count . buckets)`: `count` is an i31 of
live entries (O(1) `hash-table-count`), `buckets` a `TYPE_HASH_BUCKETS` array
(`array (mut (ref null eq))`, index 33, a bare array comptype after `TYPE_CHAR`, implicitly
`<: eq` so it stores in a cons field). Each bucket slot is a `(key . value)` alist or nil;
the slot is `(_hash(key) & 0x7fffffff) % capacity`. General arrays share that box, so
`hash-table-p` is a `ref.test TYPE_CELL` PLUS the header-car test (i31 count here, dims
array there) -- the same discrimination the printer makes.

- **interpreter**: `LispEquality.hash` / `LispEquality.equal` in the ROOT package, next to
  each other because they must agree; `LispHashTable`'s `LinkedHashMap` key is a private
  record carrying the value and its precomputed hash. The evaluator's `equal` built-in
  delegates to the same predicate. Conses and instances are folded by `LispEquality`
  itself, not their own `hashCode`, which recurses without a bound.
- **JVM**: `_hash(key, depth)` and the recursive `_equal`. The `LinkedHashMap` is a BUCKET
  INDEX -- boxed `Integer` hash -> `ArrayList` of `Object[2]` pairs -- plus an
  insertion-order `ArrayList` of the same pairs under the `#order` key. Re-storing an
  existing key mutates the pair in place, so the order list needs no maintenance and the
  entry keeps its first position. The shape is declared ONCE in `runtime/RontoHashTable`
  and read by both `JvmHashRuntimeBuilder` and the hand-written runtimes that build tables
  for emitted code (`RontoHttpClack`'s `:headers`) -- a plain `HashMap`, or a table built
  the old way, fails at the first `gethash`. A bucket is `new ArrayList<>(1)` on both
  sides, not the default ten (56 vs 24 bytes per bucket over a million keys).
- **WASM**: `FUNC_HASH` + `_equal`. `_hash` counts live recursion depth in a `(mut i32)`
  global, incremented on entry and restored on exit. Global and guard are emitted only for
  a program using a hash table, so every other module is BYTE-IDENTICAL to a pre-cap build
  (a hash-using module grows 31 bytes). If the source scan under-predicts the gate, `_hash`
  keeps its uncapped recursion.

**Invariant**: `_hash` (`FUNC_HASH`, always emitted, signature `((ref null eq)) -> i32` =
`TYPE_RAT_GET`) must agree with `_equal` -- equal keys hash equal. It folds
i31/char/string-content-bytes/float-bits/ratio and recurses on conses, with a constant-0
fallback for identity-compared values (e.g. closures). Strings/symbols fold their content
bytes (`h = h*31 + byte`) because `_equal` compares string content via `_string_eq`, not
interned offsets, so runtime-built strings are keys equal to literals (`.kb/json.md`).

An INSTANCE key: `equal` on instances is structural (`.kb/instance-syntax.md`), so every
backend folds the layout plus the slot hashes -- the layout TAG on the interpreter, the
interned layout array's identity on the JVM, the layout address on WASM -- and two
separately constructed instances with equal slots find each other in an `equal` table
(ci-spec `instance-print-syntax-and-identity`). That also makes an instance the WORST CASE
for the work budget: an object with a BACK-REFERENCE (parent, owner, `previous`) reaches
its whole graph from every slot, with cons fan-out per link, so it goes exponential faster
than a bare cons DAG. A GENERAL ARRAY key is the opposite: `equal` is identity, the hash is
an identity hash (0 on WASM), two equal-element vectors are two keys.

`puthash` doubles (`FUNC_HASH_RESIZE`) past load factor 0.75.
`FUNC_HASH`/`FUNC_HASH_RESIZE` sit just before `FUNC_USER_BASE`; both present in Preview 1
and `--component` (no import/`FUNC_START` shift, so component blobs are unaffected).
`maphash` order is unspecified (README): interpreter and JVM walk insertion order (the JVM
through `#order`, which is why the bucket index may reorder freely), WASM bucket order.

## Printing
`#<HASH-TABLE :TEST EQUAL :COUNT n>` (`EQUALP` for a folding table) on all four backends,
through `print`/`princ`/`prin1`/`princ-to-string`/`format ~A`/`~S`, nested positions
included (ci-spec `hash-table-print-syntax` plus one test per backend). No entry content.

- `:TEST` is the test LOOKUP IMPLEMENTS -- `EQUALP` for a folding table, `EQUAL` otherwise,
  since an `eql` table still places structurally. Same answer `hash-table-test` gives, from
  the same place per backend: `LispHashTable.equalpTest()`, `_hashEqp` (JVM marker), the
  header count's low bit (WASM). The two tags are two whole constants
  (`LispHashTable.HASH_TABLE_PREFIX`, `HASH_TABLE_PREFIX_EQUALP`), not one assembled at run
  time, so a program with no `equalp` table interns only the tag it can print.
- `:COUNT` is the live entry count, the same O(1) number `hash-table-count` reads.
- SBCL's trailing `{1004F8E1C3}` is deliberately absent: an identity hash varies between
  runs (`.kb/emitted-output-determinism.md`).

- interpreter: `LispHashTable.print()`, counting `map.size()`.
- JVM: the count comes from `_hashSize` (the map's own `size()` counts BUCKETS, not
  entries). The arm is keyed on `JvmHashRuntimeBuilder.MAP_CLASS` =
  `java.util.LinkedHashMap`, deliberately NOT the plain `HashMap` a `java:` call can hand
  back -- that class is the discriminator `_hashP` and the printer share, so a host map
  stays a host object (`hash-table-p` nil, printed `#<java java.util.HashMap>`, matching
  the interpreter); being LINKED is why JVM `maphash` walks insertion order. Without the
  arm a table fell through to `Object.toString` and printed Java's map syntax.
- WASM: the non-array arm of the shared `TYPE_CELL` branch
  (`WasmRuntimeBuilder.emitPrintArray`, used by `_print_val` and `_princ_val`). **That
  branch must RETURN for every cell** -- a cell falling out of it lands in the cons tail,
  which prints `" . "` and re-enters the printer on the SAME value: unbounded recursion, an
  unrecoverable `call stack exhausted` trap that also loses buffered stdout. Hence the arm
  answers for any cell, not only one passing the full `hash-table-p` test. The count is
  emitted only behind a `ref.test i31` on the header car, so a non-table cell prints a `0`
  count instead of trapping on `i31.get_s`. `FUNC_PRINT_I32_NO_NL` writes the digits (the
  same arm already calls it for an array's rank -- no new index, no blob shift).

## `with-hash-table-iterator`
A MACRO here, like `with-package-iterator`: the name is bound by `flet` to a local
FUNCTION, not CL's `macrolet`, so the iterator can also be passed as a value.
`LispMacroExpander.expandWithHashTableIterator` lowers `(with-hash-table-iterator (name
table) body...)` to a `let` over a SNAPSHOT alist -- the same
`(let ((acc nil)) (maphash (lambda (k v) (setq acc (cons (cons k v) acc))) TABLE) acc)`
walk `loop`'s `being the hash-keys` uses -- plus an `flet` popping one entry per call and
answering `(values t key value)`, or `(values nil nil nil)` once exhausted. Snapshotting
avoids a per-backend hash cursor: the expansion is ordinary `let`/`flet`/`maphash`, so the
evaluator, both compilers and the fold pass need one dispatch line each
(`LispEvaluator.evalCons`, `Jvm`/`WasmExprCompiler.compileCons`, `PureBuiltinFolder`'s
name-headed-spec arm). The lite part: an entry added or removed DURING the walk is not seen
(CLHS leaves that undefined).

Internal variables are named after the ITERATOR (`__whti_<name>`, `..._acc`, `..._k`,
`..._v`, `..._e`), not gensyms, so nesting two iterators shadows as the iterator names do
and the emitted form is identical across backends and runs
(`.kb/emitted-output-determinism.md`).

Tests: `LispEvaluatorTest.evalWithHashTableIterator`,
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`'s
`*SharpLAndCommaDotAndWithHashTableIterator`, ci-spec
`sharp-l-comma-dot-and-hash-table-iterator`.
