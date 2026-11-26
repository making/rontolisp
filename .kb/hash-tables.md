# Hash tables

A table separates PLACEMENT (a hash agreeing with the test) from COMPARISON (the test's predicate
within the bucket) on every backend. Nothing prints the key -- keying on the key's `prin1` TEXT cost
the whole printed graph per lookup and never terminated on a cyclic key.

## The four tests

`LispHashTable.TEST_*` codes (0 equal, 1 equalp, 2 eql, 3 eq) are what every backend agrees on;
`LispMacroExpander.hashTableTestCode` reads a literal `:test`, and `programMakesIdentityHashTable`
gates the identity machinery beside `programMakesEqualpHashTable`'s fold gate.

**A computed `:test`** is decided at run time from its VALUE: a symbol by name, a function by being
the very object `#'eq`/`#'eql`/`#'equal`/`#'equalp` answers (identity; `equalp` is a prelude closure
with no name); anything else places as `equal`. Alexandria's `plist-hash-table`/`copy-hash-table`
always pass it computed.
- interpreter: `Environment.hashTableTestName`.
- JVM/WASM: `LispMacroExpander.lowerComputedTestMakeHashTable` rewrites the site to
  `(%make-hash-table-by-test test)`, a prelude helper whose arms write each test LITERALLY -- so the
  spliced helper is what turns the fold and identity gates on, with no gate change. Selection is a
  surface fact (`mayMakeHashTableWithComputedTest`, also in `LibraryDefunPruner.SYNTHESIZED_ENTRIES`):
  a computed `:test`, or `make-hash-table` anywhere but a call head (the `#'make-hash-table` wrapper
  passes `(getf initargs :test)` through).

Pinned by `LispEvaluatorTest.aHashTableReportsAndPrintsTheTestItImplements`, its
`compileEqualpHashTablePrintsAndReportsItsTest` JVM/P1 twins and ci-spec `hash-table-computed-test`.

- `equal`: the structural hash plus real `equal` in the bucket. A structure or CLOS instance, which
  `equal` compares by identity ([instance-syntax.md](instance-syntax.md)), hashes by identity: on WASM
  its identity-hash slot where the module's objects carry one and its LAYOUT ADDRESS where they do
  not -- one bucket per type, which no slot write can move.
- `equalp`: the key fold below, then the same pair.
- `eql`/`eq`: the predicate in the bucket. Aggregates hash by identity (WASM: the identity-hash SLOT
  -- except an allocated `TYPE_STRING`, immutable, slot-less, which folds its content, which its id
  comparison tolerates), so a key mutated after insertion keeps its bucket; every other value hashes
  structurally exactly as an `equal` table hashes it.

## Strings under eq/eql
ANSI on all four backends: two distinct strings with equal contents are neither `eq` nor `eql`.
Equal string CONSTANTS are one object -- CLHS 3.2.4.4 coalescing, which both compiled backends do by
construction (JVM `ldc` interning, the WASM string table's offset id) and the interpreter reproduces
by comparing two `LispString.sourceLiteral()` strings by content. A symbol's name is such a constant
on the compile paths only where it folds (a literal symbol): a computed symbol's name is a fresh
string per call, so `(eq (symbol-name s) (symbol-name s))` is NIL on JVM, P1 and the component, T on
the interpreter and SBCL. One object per name would take `_intern` in every wasm module with a
computed `symbol-name`, and no consumer asks. **Re-evaluate when** one does.

- interpreter: `LispEquality.isIdentityAggregate` counts a non-constant `LispString`.
- JVM: `_eqv` answers identity for a `List` (every array shape, character vectors included) and a
  quote-framed `String`; a bare `String` (a symbol) stays by name. An eql/eq table's key hash sends a
  `List` to `identityHashCode` before `_hash`'s `_strv` folds it by content.
- WASM: an eql/eq table places a character vector (a `TYPE_CELL`) by its identity-hash slot.

## The two constant-coalescing edges
Both are implementation-dependent in ANSI and read the same on all four backends:

- **`string` of a string is that string** (SBCL): a literal, an allocated copy and a character vector
  answer themselves. The other designator positions (`string=`, trims) keep copying -- they only read
  contents, so identity is unobservable there.
- **A string a macro builds into its expansion is a constant** like a reader literal: `(eq (m) (m))`
  is `T` everywhere. `expandMacroCall` marks every string in the fresh expansion a source literal;
  identity, hashing and the literal-write protections follow the flag. Documenting the interpreter's
  `NIL` instead would have pinned a divergence for no safety gain: programs relying on per-expansion
  identity are already broken on the three compiled backends.
- Pinned by ci-spec `string-identity-edges-string-and-macro-built-constants`,
  `LispEvaluatorTest#stringOfAStringIsThatStringAndMacroBuiltStringsAreConstants`, ci-spec
  `string-identity-edges`.
- **The transport boundary keeps its own render**: the HTTP stack read `string` as "normalize to a
  framed string", so its call sites spell `%normalize-string` (charvec to fresh framed string,
  anything else unchanged).

## `equalp` is a KEY FOLD, on all four backends
`equalp` on two values is `equal` on their folds, so one structural table carries both tests. Fold:
string/character to UPPER CASE code point by code point; a finite float to the exact rational it
equals (`1`, `1.0`, `2/2` are one key, and so are `0.5` and `1/2`); a cons element-wise; a structure
or CLOS instance to `(marker tag slot-fold...)`, the marker a value equal to nothing but itself (so no
key a program writes matches a fold) -- the table matches two instances slot by slot as the
predicate does (petalisp's shape tables); everything else is its own key (NaN and the infinities
included). One deliberate ANSI deviation, a MISS and never a false match: an ARRAY does not fold
(`equal` on a vector is identity; a bit vector, compared by elements, needs no fold). The fold only
PLACES the key; the key as first stored is what every reader hands back ("The key read back").

- interpreter `LispEquality.equalpKey` (float arm `LispRatio.ofDouble`, the same decomposition
  `rational` answers).
- JVM `runtime/RontoHashTable.equalpKey` (`.kb/jvm-export.md`, "What travels"). The test is the
  table's own final field; `clrhash` replaces only the entries, so the test stays.
- WASM `_equalp_key` (`WasmEqualpKeyRuntimeBuilder`). A fraction builds the `TYPE_RATIO` directly
  (an odd numerator over a power of two is in lowest terms, so `_rat_new`'s gcd never comes aboard).
  The test rides in the LOW TWO BITS of the header count, `entries * 4 + test`, so the header car
  stays an i31; every count read shifts past it. An entry keeps the hash it was placed by.

**Gate: `LispMacroExpander.programMakesEqualpHashTable`**, one scan shared by both compiled backends
(literal `:test`s; a computed one reaches it through `%make-hash-table-by-test`). **Every count in a
WASM module must agree about whether the tag is there**, so the gate is carried into each top-level
CHUNK context (`WasmAsyncEmit.freshCtx`). Pinned by the `*EqualpHashTable*` tests in
`LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`, ci-spec
`equalp-hash-table-key-fold` and `hash-table-identity-test`, and `RontoHashTableEqualpKeyTest`.

## The key read back
SBCL's rule on all four backends: `maphash` (and what lowers to it) hands back each key AS FIRST
STORED -- `"hello"`, `2.0`, `("ab" #\c)` from an `equalp` table, never the fold -- and a re-store
under a key the table already has replaces only the value, under every test (observable by `eq`). A
`remhash` then a store is a fresh entry with the new key.

- interpreter and JVM: an `equalp` table's pair is `{fold, value, key}` when the fold differs from
  the key, else `{key, value}`; `maphash` reads slot `(length - 2) * 2`, branch-free.
- WASM: the key as first stored sits in a seventh entry slot when the fold differs.

A program with no `equalp` table is byte-identical. Pinned by `testsupport/HashTableKeyPrograms`
(SBCL's output) through `LispEvaluatorTest.aHashTableHandsBackTheKeyAsFirstStored` and its
JVM/P1/component twins, and ci-spec `hash-table-key-as-first-stored`.

## The two caps
**Depth: `LispEquality.HASH_DEPTH_CAP` (64)**, folding a constant below it -- what makes a cyclic key
hashable. **Work: `LispEquality.HASH_WORK_CAP` (4096) NODE VISITS across the whole traversal** -- a
depth cap bounds HEIGHT, not SIZE, and paths through a shared graph are exponential in height (a DAG
of n shared conses cost 2^n). 4096 is 32x the `2 * 64` nodes a LINEAR key can reach. A cap may only
ever be by depth/count, never anything order- or address-dependent, or `equal` keys would stop
hashing equal. The budget is REFILLED at the start of every top-level hash and spent across the WHOLE
traversal, never handed down per branch.

- JVM: `_hash(key, depth, gas)`'s `[I` parameter. WASM: a `(mut i32)` global (`hashGasGlobalIndex`;
  `_hash`'s signature is fixed), refilled by the outermost entry, NOT restored on the way out. A
  symbol or string at depth zero folds its bytes before any of that
  (`WasmRuntimeBuilder.emitTopLevelStringFold`): 167 -> 99 instructions for a short key, a
  speed-for-size trade, so not under `--optimize=size`.
- The JSON decoder builds a parsed object's record itself through
  `WasmHashTableRuntimeBuilder.emitFlatInsert` and `emitEqualPlacementHash`: a change to either
  layout or the spread changes both (`.kb/json.md`, "The wasm-GC codec").

**The `equalp` fold carries the same budget**, more sharply: it BUILDS the structure it walks (an
unbudgeted fold of a 26-cons DAG was an `OutOfMemoryError`). `RontoHashTable.FOLD_WORK_CAP` is pinned
equal to `HASH_WORK_CAP`.

**`equal` answers on IDENTITY before it recurses**, so store-and-retrieve under the SAME object
terminates. Two DISTINCT cyclic structures compared with `equal` may not terminate (ANSI leaves it
undefined).

Pinned by ci-spec `cyclic-hash-key` and `shared-graph-hash-key`,
`LispEvaluatorTest.hashTableSharedGraphKeysArePlacedInBoundedWork` (+ `compile` twins),
`RontoHashTableEqualpKeyTest.theWorkBudgetStopsBothFoldsOnASharedGraphKey`. A REGRESSION IS A HANG,
not a flaky number.

## Representation
ONE structure on all four backends: CHAINS of entries placed by the key's hash and scanned with the
test, plus the entries in INSERTION ORDER, which `maphash` walks. The interpreter and the JVM share
the class (`runtime/RontoHashTable`); WASM rebuilds it over wasm-GC arrays
(`WasmHashTableRuntimeBuilder`), and keeps a table of eight keys or fewer as a vector of pairs.

- **WASM fast paths in the chain test** (`emitEqualTest`): ask `ref.eq` before `_equal` (the call
  spills the walk's references); two `TYPE_STRING`s go straight to `_string_eq`; a small record's
  string key is looked for by identity first (literal keys read by the same literals). A table
  operand is checked in three `br_on_cast_fail`s (`headerSlotOf`), not `hash-table-p`'s seven type
  operations, and an i31 key's hash is read inline (a fixnum is its own hash). Measured on Starlark
  dict programs, 5-10% each.
- **WASM: a string keeps its hash** in its `STRING_HASH_FIELD` (0 = not yet;
  `.kb/wasm-gc-strings.md`, "Representation"), free on wasmtime's 16-byte rounding
  (`.kb/wasm-gc-object-size.md`).
- **WASM: a table of at most `SMALL_MAX` (8) keys is SMALL**, a record `[count, key0, value0, ...]` of
  odd length (flat is 4, trie 6), for every test but `equalp`. The pairs are scanned with no hash
  computed; the ninth key turns the table FLAT. `maphash` walks the record in place with its count
  read once, so a walk reads the keys it started with whatever it stores or removes
  (`HashTableCopyPrograms` pins it on all four). `equalp` tables stay flat (entries keep fold and
  key). Why: a parsed two-key JSON object was 1,128 bytes as sluice's `PluginHeap` charges and is 424
  small (`.kb/json.md`, "The wasm-GC codec"); lookups 7-8% less fuel, large tables unchanged. Not on
  the JVM/interpreter: their entry is one pair array, and no consumer measured them.
- FLAT: power-of-two buckets linked through `next` (a bucket mixes hashes), doubling past three
  quarters full, plus an order array. A removal kills its order slot; the order compacts once half
  its slots are dead, so `remhash` is O(1) amortised (unlinking from an order list was far slower on
  a large `eq` table; `RontoHashTableTest` pins the dead slots staying until half are dead). A table
  nobody copies stays flat all its life; the first copy makes it a TRIE, `clrhash` flat again.
- An entry keeps the hash it was placed by, so growing and converting never hash again. Bucket index
  and trie path read `spread(h) = h ^ (h >>> 16)`, HashMap's: a bijection, so two keys share a trie
  chain exactly when their hashes are equal; a double's hash, often zero below bit 20, takes its low
  bits from the high half. WASM cuts it to 30 bits (an i31).
- The PAIR is what readers outside the table code see (JVM/interpreter: an `Object[]` reached only
  through the class -- `maphash`, the Java bridge's `tableEntries`, `_jtab`, `RontoHttpClack`'s
  `:headers` through `RontoHashTable.put`). Anything but a `RontoHashTable` fails at the first
  `gethash`.
- WASM: a `TYPE_CELL` box (so `consp` is nil) holding a header `cons (count . record)`, the record a
  `TYPE_HASH_BUCKETS` array told apart by `array.len`. General arrays share the box, so
  `hash-table-p` is `ref.test TYPE_CELL` PLUS the header-car test. The functions reuse
  `TYPE_CALLABLE_BASE` signatures (an integer travels as an i31), so no type is added, and the shaker
  drops whichever a module never calls.
- `maphash` walks INSERTION ORDER on all four backends: a re-store keeps the key's place, a key
  removed and stored again goes last. The walk is a snapshot, so a store during it does not change
  it.
- `LispEquality.hash` / `.equal` sit in the ROOT package next to each other because they must agree;
  conses and instances are folded by `LispEquality` itself, not their own `hashCode`.
- WASM `FUNC_HASH` must agree with `_equal`: strings/symbols fold content bytes because `_equal`
  compares via `_string_eq`, not interned offsets (`.kb/json.md`); constant-0 fallback for
  identity-compared values. The depth global is emitted only for a hash-using program; other modules
  stay BYTE-IDENTICAL.
- An INSTANCE key in an `equal` table folds layout + slot hashes, so two separately built instances
  with equal slots find each other (ci-spec `instance-print-syntax-and-identity`) -- and a
  BACK-REFERENCE makes it the WORST CASE for the work budget. In an `eql`/`eq` table they are two keys.
- A GENERAL ARRAY key is the opposite: identity -- except a bit vector, folded by its elements
  ([array-literals.md](array-literals.md)). On the JVM a general vector is an `ArrayList`, whose own
  `hashCode` walks the elements, so `_hash` asks `System.identityHashCode` explicitly (else a vector
  holding itself overflows and a vector grown after storage loses its entry;
  `JvmLispCompilerTest#compileAndRunAVectorKeyHashesByIdentity`).
- A HASH TABLE key is identity under every test on every backend: `RontoHashTable` overrides neither
  `hashCode` nor `equals`, and `_hash` asks `System.identityHashCode`
  (`JvmLispCompilerTest#compileAndRunAHashTableKeyHashesAndComparesByIdentity`, ci-spec
  `hash-table-as-hash-key`). A rank-2 array, a packed vector, a structure and a fill-pointer string
  mutated after storage in an `eq` table keep their entry on all four.

## Copies share structure

**`rontolisp::%copy-equal-table` -- the copy every Clojure `assoc`, `dissoc`, `merge` and set
`conj`/`disj` takes (`.kb/clojure-frontend.md`, "The copy a map verb takes") -- is native on all four
backends and shares the table's structure: O(n) the first time a table is copied, O(1) after it, and
a store into either side O(log n).** A copying loop made a map grown one `assoc` at a time O(n^2)
(instaparse's grammar load).

- The first copy turns the source into a TRIE: a hash array mapped trie of hash chains (32 ways a
  level on the JVM, 16 on WASM so a bitmap fits an i31) plus the entries in insertion order as
  Clojure's persistent vector. Source and copy then hold it under FRESH edit tokens; a node or entry
  changes in place only when it carries the table's own token (Clojure's transient ownership),
  anything else is copied along its path first, and a shared entry's pair is cloned before its value
  changes. So neither side changes in place what the other reads.
- A removal kills the entry's order slot; the trie rebuilds once half are dead -- amortised O(1) along
  one line of copies, though copies branching from one table at the threshold each pay the rebuild.
- Another test's table is re-placed key by key under `equal`, each key as first stored.
- THREADS (the `RontoHashTable` javadoc): reading writes nothing; a copy writes one volatile field of
  its source (`repr`), replacing the shape by one of the same entries, so a reader on another thread
  works on whichever shape it read -- the flat one is never changed again. No reader may see the
  source's storage change under it: a Clojure map shared by Ring request threads is read and copied
  by all of them at once (`RontoHashTableTest#threadsCopyingAndReadingOneTableLeaveItUnchanged`).
- Common Lisp tables, never copied, stay flat and pay nothing.
- WASM: a map grown by tens of thousands of `assoc`s leaves every replaced path for the collector,
  and wasmtime's copying collector rescans the live heap, so large maps cost progressively more than
  on the JVM. The clojure-spec pin grows 20,000 for that reason.
- Pins: `testsupport/HashTableCopyPrograms` (insertion order, a copy's independence, eql and equalp
  copies, 50,000 keys one copy and one store at a time) through
  `LispEvaluatorTest#aHashTableWalksInInsertionOrderAndACopySharesItsStructure`, its JVM twin and both
  WASM twins; `RontoHashTableTest` (a random workload against one `LinkedHashMap` each, 200,000
  copy-and-store steps under a timeout, eight threads over one table); clojure-spec
  `a-map-grown-one-assoc-at-a-time-shares-its-table`. A REGRESSION IS A HANG.

## The identity-hash slot (WASM)

**Invariant: a wasm-GC reference has no address, so an object's identity hash lives IN the object,
and only in a module that can key by identity.** In a module that makes an `eq`/`eql` table
(`Ctx.usesIdentityHashTables`, the same flag that tags the counts) `TYPE_CONS`, `TYPE_CELL` and
`TYPE_INSTANCE` carry a trailing `(mut i32) ihash`, `0` until first keyed. Every other module keeps
the narrower structs byte for byte.

- **Every allocation goes through `WasmEmitHelper.emitNewCons/emitNewCell/emitNewInstance`**, which
  push the slot's `0` exactly when the type section declares it. A site spelling its own
  `struct.new` compiles clean and fails validation in identity-table modules only; static builders
  get the flag as an explicit `boolean identityHash` parameter.
- **`_ihash`** (`WasmIdentityHashRuntimeBuilder`, a constant-0 stub in other modules): on `0` assigns
  `fmix32(++seq)` -- murmur3's finalizer is a bijection, so a value of 1 or more never mixes to the
  unassigned `0`, and it spreads consecutive assignments over the low bits a bucket mask reads. A
  non-aggregate falls through to `_hash`. The hash never moves once assigned.
- **Who calls it**: an `eq`/`eql` table's placement, and `_hash`'s `TYPE_CELL` arm in a slotted
  module, so a vector or table key of an `equal` table -- whose `equal` IS identity -- is placed by it
  too. A cons in an `equal` table still hashes structurally. Clojure collection keys get it through
  `%clojure-key-reps` (`.kb/clojure-frontend.md`, "Structural keys").
- **`%identity-hash`** (`LispNames.IDENTITY_HASH`) answers the hash an eq table places an aggregate
  by, a signed 32-bit integer (`System.identityHashCode` on the JVM and interpreter). A module calling
  it carries the slot (`LispMacroExpander.programHashesByIdentity`). An atom's answer is unspecified.
  The Clojure front end's `hash` reads it (`.kb/clojure-frontend.md`, "Hashes"). Pinned by
  `WasmLispCompilerIntegrationTest.compileAndRunIdentityHashReadsTheSlotAModuleCallingItCarries` and
  its `LispEvaluatorTest`/`JvmLispCompilerTest` twins.
- **`TYPE_CLOSURE` carries the slot too** (third field, `WasmEmitHelper.emitNewClosure` at every
  closure-allocation site). Pinned by
  `WasmLispCompilerIntegrationTest.compileEqHashTableWithManyClosureAndPackedArrayKeysStaysHashed`.
- **A packed array stays in bucket 0 -- a measured, documented decision, not an oversight.**
  `TYPE_FARRAY` could carry the slot, but its `struct.new` sites include the `--simd` kernel runtimes,
  which do not thread an `identityHash` flag, so the blast radius reaches another backend. The packed
  INTEGER vectors are bare wasm `array`s -- the value IS the array reference -- so a slot means
  wrapping every packed integer vector in a struct, a representation change at every access site.
  Nothing in the corpora keys a table by a packed array, so both keep `_hash`'s constant-0 fallback
  until something measures otherwise.
- **Cost** (`.kb/wasm-gc-object-size.md`): free in wasmtime's heap layout but not in V8's without
  pointer compression -- the reason for the gate; plus module bytes at every cons allocation site.
  Without the slot, an `eq` table of n fresh conses was an n-entry chain (quadratic fill).

Pinned by `WasmLispCompilerIntegrationTest.compileEqHashTableWithManyAggregateKeysStaysHashed`
(300,000 identity keys; under bucket 0 the runner's wasmtime timeout cuts it -- A REGRESSION IS A
HANG), `LispEvaluatorTest.eqHashTableWithManyAggregateKeysStaysHashed` / the JVM twin, and ci-spec
`identity-hash-table-many-aggregate-keys`.

## Printing
`#<HASH-TABLE :TEST EQUAL :COUNT n>` on all four backends, nested included (ci-spec
`hash-table-print-syntax`, `hash-table-identity-test`). No entry content; SBCL's trailing identity
hash is deliberately absent (`.kb/emitted-output-determinism.md`).

- `:TEST` is the test LOOKUP IMPLEMENTS, from the same place as `hash-table-test`; one whole constant
  per test, not assembled at run time.
- JVM: the printer arm is keyed on `JvmHashRuntimeBuilder.MAP_CLASS` = `runtime/RontoHashTable`, a
  class no `java:` call answers -- the discriminator `_hashP` shares, so a host map stays a host
  object.
- WASM: the non-array arm of the shared `TYPE_CELL` branch. **That branch must RETURN for every
  cell** -- a cell falling out lands in the cons tail, prints `" . "` and re-enters the printer on the
  SAME value: an unrecoverable `call stack exhausted` trap that also loses buffered stdout. The count
  sits behind a `ref.test i31` so a non-table cell prints `0`.

## `with-hash-table-iterator`
A MACRO, like `with-package-iterator`: the name is bound by `flet` to a local FUNCTION, not CL's
`macrolet`, so the iterator can be passed as a value. It lowers to a `let` over a SNAPSHOT alist plus
an `flet` answering `(values t key value)` / `(values nil nil nil)` -- the snapshot avoids a
per-backend cursor. An entry added or removed DURING the walk is not seen. The walk is NEWEST first,
unlike `maphash`'s insertion order, so the page keeps "the order is unspecified" and no case pins it.
Internal variables are named after the ITERATOR (`__whti_<name>`), not gensyms, so nesting shadows as
the iterator names do and the form is identical across backends. The prefix goes on the MEMBER: an
uninterned name gives `#:__whti_G1`, a qualified one `P::__whti_N` -- spliced onto the whole name, a
gensym's `#:` made `__whti_#` a package once the expansion reached the compilers' resolver.

Tests: `LispEvaluatorTest.evalWithHashTableIterator`, `JvmLispCompilerTest`/
`WasmLispCompilerIntegrationTest`'s `*SharpLAndCommaDotAndWithHashTableIterator`, ci-spec
`sharp-l-comma-dot-and-hash-table-iterator`.
