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
  Aggregates (conses, vectors, instances, tables, allocated strings, mutable
  character vectors) hash by identity (`System.identityHashCode` on the
  interpreter/JVM; on WASM the identity-hash SLOT the object carries -- "The
  identity-hash slot" below -- except an allocated `TYPE_STRING`, which is
  immutable, has no slot and folds its content, which its id comparison tolerates),
  so a key mutated after insertion keeps its bucket; every other value hashes
  structurally exactly as an `equal` table hashes it, which the value-compared
  `eql`/`eq` on numbers/symbols/string CONSTANTS agrees with. The semantics are
  identical on all four backends, and since 2026-09-17 so is the cost: before the
  slot every WASM aggregate key shared bucket 0, and a table of n such keys was an
  n-entry chain.

## Strings under eq/eql
ANSI on all four backends (2026-09-17): two distinct strings with equal contents are
neither `eq` nor `eql`, so `member`/`assoc`/`position`/`case`/an `eq`/`eql` table miss
on them; `equal`/`equalp` compare contents. Equal string CONSTANTS are one object --
CLHS 3.2.4.4 coalescing, which both compiled backends do by construction (JVM `ldc`
interning, the WASM string table's offset id) and the interpreter reproduces by
comparing two `LispString.sourceLiteral()` strings by content. A symbol's name
(`symbol-name`, `string` of a symbol; `LispString.symbolName`) is such a constant.

- interpreter: `LispEquality.isIdentityAggregate` counts a non-constant `LispString`.
- JVM: `_eqv` answers identity for a `List` (every array shape, character vectors
  included) and a quote-framed `String`; a bare `String` (a symbol) stays by name.
  `_equal` gains the content arm `_eqv` lost (`_strv` both, then `String.equals`), and
  an eql/eq table's key hash sends a `List` to `identityHashCode` before `_hash`'s
  `_strv` folds it by content.
- WASM: `eq`/`eql` already compared a `TYPE_STRING` by id and a character vector by
  `ref.eq`; an eql/eq table places a character vector -- a `TYPE_CELL` -- by its
  identity-hash slot through `_ihash` like every other cell, where `_hash` would fold
  it by content.

Blast radius measured before the change: the whole `./mvnw test` suite (eval, codegen,
cli, e2e, ansi, scheme) stayed green, and no shipped library or example relied on
value comparison.

## The two constant-coalescing edges (`.todo/853`, 2026-09-18)
Both were constant-coalescing edges ANSI leaves implementation-dependent, and both now
read the same on all four backends:

- **`string` of a string is that string** (SBCL, CLHS `string`): a literal, an allocated
  copy and a character vector alike answer themselves; a fresh string stays distinct.
  The interpreter always did; the JVM routed every computed designator through the
  display render (`JvmSymbolApiCompiler.compileString`), WASM through `_princ_to_str`.
  Both now test first: JVM `stringp` (proper or charvec) answers the temp, else the
  guarded coercion (symbol incl. nil / char through display and reframe, anything else
  the strict form's own error); WASM `stringp`-i32 answers the local, else nil /
  unquoted-`TYPE_STRING` / `TYPE_CHAR` render, else trap. The other designator
  positions (`string=`, `%string-compare`, trims) keep copying -- they only read
  contents, so identity is unobservable there, and no audit found a consumer that
  mutates what `string` answers.
- **A string a macro builds into its expansion is a constant** like a reader literal
  (CLHS 3.2.4.4 lets either coalesce): `(eq (m) (m))` is `T` everywhere, matching the
  compiled backends' literal coalescing and SBCL. `expandMacroCall` marks every string
  in the fresh expansion tree a source literal (shared by `defmacro`,
  compiler macros and `macrolet`, on every path through the one method); identity,
  hashing and the literal-write protections follow the flag, so a mutated
  macro-built string rebinds/copies exactly like a mutated reader literal. The
  alternative -- documenting the interpreter's `NIL` -- would have pinned a
  divergence in the project's own print-the-same-thing rule for no safety gain:
  programs relying on per-expansion string identity are already broken on the three
  compiled backends.
- Pinned by ci-spec `string-identity-edges-string-and-macro-built-constants`,
  `LispEvaluatorTest#stringOfAStringIsThatStringAndMacroBuiltStringsAreConstants`,
  ci-spec `string-identity-edges`.
- **The transport boundary keeps its own render**: the HTTP stack read `string` as
  "normalize to a framed string" (a charvec from `string-downcase`/`format`/
  `concatenate`/`get-output-stream-string` into the transports, which unquote
  `String`s and drop anything else). Those three call sites now spell
  `%normalize-string` -- charvec to fresh framed string, anything else through
  unchanged (JVM `_strv` / WASM `_charvec_to_str`, no-ops where no character
  vector can exist; the interpreter answers the argument). Identity and
  mutability of its result are unspecified; strings only.

## `equalp` is a KEY FOLD, on all four backends
`equalp` on two values is `equal` on their folds, so one structural table carries both tests.
Fold: string/character to UPPER CASE code point by code point; a finite float to the exact rational
it equals, read out of `mantissa * 2^exponent` (`1`, `1.0`, `2/2` are one key, and so are `0.5` and
`1/2`; the least subnormal folds to `1/2^1074`); a cons element-wise; everything else is its own key
(NaN and the infinities included). One deliberate ANSI deviation, a MISS and never a false match:
an ARRAY does not fold (`equal` on a vector is identity). The fraction fold waited on WASM
`TYPE_RATIO`'s two i32 components, too narrow for a power-of-two denominator, until they became
exact integers ([[wasm-bignum]], "Ratios").
The fold only PLACES the key; the key as first stored is what every reader hands back ("The key
read back" below).

- interpreter `LispHashTable` via `LispEquality.equalpKey`; the float arm is `LispRatio.ofDouble`,
  the same decomposition `rational` answers.
- JVM `runtime/RontoHashTable.equalpKey`, in the class every compiled table is
  (`.kb/jvm-export.md`, "What travels"). The test is the table's own final field
  (`RontoHashTable.newTable(test)`, read by `test`): `_hashKey` folds and
  `_hashGet`/`_hashPut`/`_hashRem` run every key through it, then compare and hash by
  `_hashTest`. `clrhash` replaces only the entries, so the test stays.
- WASM `_equalp_key` (`WasmEqualpKeyRuntimeBuilder`, `FUNC_EQUALP_KEY`, appended after the last
  fixed helper so no index shifts). A fraction strips the mantissa's trailing zero bits and builds
  the `TYPE_RATIO` directly (`_int_new` numerator, `_big_ash` denominator): an odd numerator over a
  power of two is already in lowest terms, so `_rat_new`'s gcd never comes aboard: +54 B in a
  module with an `equalp` table, every other module byte-identical. The tag rides in the LOW TWO
  BITS of the header count, stored as `entries * 4 + test` (0 equal, 1 equalp, 2 eql, 3 eq), so the header car stays an i31; every
  count read shifts past it. An eql/eq key is placed by `_ihash` (`FUNC_IHASH`, the
  identity-hash slot below) and compared with the eql/eq comparison inlined in
  `_ht_get`/`_ht_put`/`_ht_rem` (`WasmHashTableRuntimeBuilder.emitKeyTest`); an entry keeps
  the hash it was placed by, so neither growing the buckets nor building the trie hashes
  again.

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

## The key read back
SBCL's rule on all four backends: `maphash` (and `with-hash-table-iterator` and
`loop ... being the hash-keys`, which both lower to it) hands back each key AS FIRST STORED --
`"hello"`, `2.0`, `0.5`, `("ab" #\c)` from an `equalp` table, never the fold -- and a re-store
under a key the table already has replaces only the value, under every test. A `remhash` then a
store is a fresh entry with the new key. Until 2026-10-05 every backend handed back the fold, and
the interpreter and JVM kept the NEWEST key object of an `equal` table where WASM and SBCL keep the
first (observable by `eq`).

- interpreter and JVM (one `RontoHashTable` pair): an `equalp` table's pair is
  `{fold, value, key}` when the fold differs from the key (compared by reference), else the
  two-slot `{key, value}`; in a program that makes an `equalp` table `maphash` reads slot
  `(length - 2) * 2`, branch-free (the interpreter's `LispHashTable.entries` slot 2 of a
  three-slot pair). A re-store writes only the value slot.
- WASM: an entry array holds the fold in its key slot and, when the fold differs, the key as
  first stored in a seventh slot, which `maphash` reads (`WasmHashTableCompiler`'s walk of
  `_ht_entries`: slot 6 of a seven-slot entry in an `equalp` module, else slot 4).

Checked 2026-10-05 (linux-x86-64): every module of a program with no `equalp` table is
byte-identical on P1 and the component (bench-report, size-report and 30 examples/probes), the
JVM class shrinks by the dropped key write. In an `equalp` program WASM pays per `gethash`/`puthash`
site, the JVM
nothing per site (helpers are shared) and a little per class. Store, lookup and `maphash`
speed is unchanged within noise on every backend. Pinned by
`testsupport/HashTableKeyPrograms` (SBCL's output) through
`LispEvaluatorTest.aHashTableHandsBackTheKeyAsFirstStored` and its JVM/P1/component twins, and ci-spec
`hash-table-key-as-first-stored`.

## The two caps
**Depth: `LispEquality.HASH_DEPTH_CAP` (64)**, folding a constant below it — what makes a cyclic
key hashable. **Work: `LispEquality.HASH_WORK_CAP` (4096) NODE VISITS across the whole traversal** —
a depth cap bounds HEIGHT, not SIZE, and paths through a shared graph are exponential in height (an
`equal` table keyed by a DAG of n shared conses cost 2^n; one `gethash` two links down a
parent-linked chain hung). 4096 is 32x the at-most `2 * 64` nodes a LINEAR key
can reach. A cap may only ever be by depth/count, never anything order- or address-dependent, or
`equal` keys would stop hashing equal. The budget is REFILLED at the start of every top-level hash
and spent across the WHOLE traversal, never handed down per branch.

- interpreter: a one-cell `int[]` threaded by `LispEquality.hash`.
- JVM: `_hash(key, depth, gas)` third `[I` parameter, allocated per placement by
  `JvmHashRuntimeBuilder`'s `KeyHash`.
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
ONE structure on all four backends: CHAINS of entries placed by the key's hash and scanned with
the test, plus the entries in INSERTION ORDER, which `maphash` walks. The interpreter and the JVM
share the class (`runtime/RontoHashTable`; the interpreter's `LispHashTable` wraps one and
compares with `LispEquality`, a compiled program's `_hash*` helpers with `_equal`/`_eqv`); WASM
rebuilds it over wasm-GC arrays (`WasmHashTableRuntimeBuilder`, the `_ht_*` functions).

- A table starts FLAT: power-of-two buckets of entries linked through `next` (a bucket mixes
  hashes, so a scan skips an entry of another hash), doubling past three quarters full, and an
  order array. A removal unlinks the entry and kills its order slot (`END` on the JVM, nil on
  WASM); the order compacts once half its slots are dead, so each `remhash` is O(1) amortised.
  Unlinking from the order instead -- the JVM's `#order` list until 2026-09-18 -- was far
  slower per removal on a large `eq` table (`.todo/855`); `RontoHashTableTest` pins the
  order slots a removal leaves where they were until half are dead (the wall-clock pin on
  400,000 removals was removed 2026-10-23). A table nobody copies stays
  flat all its life; the first copy makes it a TRIE ("Copies share structure"), `clrhash` flat
  again.
- An entry keeps the hash it was placed by, so growing and converting never hash again. The
  bucket index and the trie path read `spread(h) = h ^ (h >>> 16)`, HashMap's: a bijection, so
  two keys share a trie chain exactly when their hashes are equal; a double's hash, often zero
  below bit 20, takes its low bits from the high half, and consecutive integers stay
  neighbours. WASM keeps the spread hash cut to 30 bits (an i31).
- The PAIR is what readers outside the table code see. JVM/interpreter: an `Object[]`,
  `{key, value}` or `{fold, value, key}` ("The key read back"), reached only through the class:
  `maphash` (`liveValues`, the JVM's `_hashValues`), the Java bridge's `tableEntries`
  (`JavaBridgeTemplate`), the direct sites' `_jtab` (`JvmJavaDirectSites`) and the HTTP runtime
  (`RontoHttpClack` builds `:headers` through `RontoHashTable.put`, whose atom keys hash by
  `hashCode` as `_hash` hashes them). Anything but a `RontoHashTable` fails at the first
  `gethash`.
- WASM: a `TYPE_CELL` box (so `consp` is nil) holding a header `cons (count . record)`, `count`
  an i31 of live entries (tagged, "`equalp` is a KEY FOLD"), the record a `TYPE_HASH_BUCKETS`
  array (`array (mut (ref null eq))`, index 33, bare comptype after `TYPE_CHAR`): flat
  `[buckets, order, size, dead]` or trie `[root, vroot, vtail, vsize, vshift, dead]`, told apart
  by `array.len`. An entry is an array `[hash, pos, owner, next, key, value]` (+ the key as
  written in an `equalp` table). General arrays share the box, so `hash-table-p` is
  `ref.test TYPE_CELL` PLUS the header-car test. The functions are 25 fixed indices from
  `FUNC_HT_BASE` (after `FUNC_READ_LINK`) plus `_ht_entries` at `FUNC_HT_ENTRIES`, the slot
  `_hash_resize` held; each reuses a `TYPE_CALLABLE_BASE` signature (an integer travels as an
  i31), so no type is added, and the tree shaker drops whichever a module never calls.
- `maphash` walks INSERTION ORDER on all four backends: a re-store keeps the key's place, a key
  removed and stored again goes last. The walk is a snapshot (`liveValues`/`_ht_entries`), so a
  store during it does not change it. Until 2026-10-11 WASM walked bucket order (a Clojure map
  of a dozen keys printed in another order there).
- `LispEquality.hash` / `.equal` sit in the ROOT package next to each other because they must
  agree; conses and instances are folded by `LispEquality` itself, not their own `hashCode`.
- WASM `FUNC_HASH` must agree with `_equal` (equal keys hash equal); signature
  `((ref null eq)) -> i32` = `TYPE_RAT_GET`, always emitted. Strings/symbols fold content bytes
  (`h = h*31 + byte`) because `_equal` compares via `_string_eq`, not interned offsets
  (`.kb/json.md`); constant-0 fallback for identity-compared values. The depth global is emitted
  only for a hash-using program; other modules stay BYTE-IDENTICAL.
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
- A HASH TABLE key is identity under every test and on every backend, like a vector.
  `RontoHashTable` overrides neither `hashCode` nor `equals`, and `_hash` asks
  `System.identityHashCode` for one explicitly. Until 2026-10-11 a JVM table was a
  `LinkedHashMap`, whose `hashCode` and `equals` walk the entries, so `_hash` and `_eqv` had an
  identity arm for any `java.util.Map`; before that arm (2026-09-17) a table filled after it
  was stored lost its entry under all four tests and two empty tables were `eq`
  (`JvmLispCompilerTest#compileAndRunAHashTableKeyHashesAndComparesByIdentity`,
  ci-spec `hash-table-as-hash-key`). Audit 2026-09-17, all four backends agree: a
  rank-2 array, a packed fixnum/double vector and a structure mutated after storage
  in an `eq` table keep their entry, and so does a fill-pointer STRING grown after
  storage since strings key an eq/eql table by identity ("Strings under eq/eql").

## Copies share structure (`.todo/f50`, 2026-10-11)

**`rontolisp::%copy-equal-table` -- the copy every Clojure `assoc`, `dissoc`, `merge` and set
`conj`/`disj` takes (`.kb/clojure-frontend.md`, "The copy a map verb takes") -- is native on all
four backends and shares the table's structure: O(n) the first time a table is copied, O(1)
after it, and a store into either side O(log n).** The JVM and WASM compiled the prelude loop (a
`maphash` and a store per entry) until then, so a map grown one `assoc` at a time was O(n^2):
instaparse's GLL parse table spent the bulk of its grammar load copying.

- The first copy turns the source into a TRIE: a hash array mapped trie of hash chains (32 ways
  a level on the JVM, 16 on WASM so a bitmap fits an i31) plus the entries in insertion order as
  Clojure's persistent vector (a 32-way trie of full leaves plus a tail). The source and the copy
  then hold it under FRESH edit tokens. A node, vector node or entry changes in place only when
  it carries the table's own token (Clojure's transient ownership); anything else is copied
  along its path first, and an entry another table shares has its pair cloned before its value
  changes. So neither side changes in place what the other reads.
- A removal kills the entry's order slot; the trie rebuilds without the dead slots once half are
  dead -- amortised O(1) along one line of copies, though copies branching from one table at
  the threshold each pay the rebuild.
- Another test's table (eql, eq, equalp) is re-placed key by key under `equal`, each key as
  first stored, as before.
- THREADS (the `RontoHashTable` javadoc): reading writes nothing; a copy writes one volatile
  field of its source (`repr`), replacing the shape by one of the same entries, so a reader on
  another thread works on whichever shape it read -- the flat one is never changed again. The
  todo's "a copy may not write its source" meant no reader may see the source's storage change
  under it, which this keeps: a Clojure map shared by Ring request threads is read and copied
  by all of them at once (`RontoHashTableTest#threadsCopyingAndReadingOneTableLeaveItUnchanged`).
  WASM runs one thread and replaces the header's record.
- Common Lisp tables, never copied, stay flat and pay nothing: bench-report `hash` (`eql`
  stores and reads) got roughly twice as fast on the JVM and WASM (the flat shape
  replacing the bucket index of lists, and the spread), the interpreter unchanged
 (2026-10-11, interleaved runs).
- Checked 2026-10-11 (linux-x86-64): repeated `assoc` onto `{}` and `(into {} ...)` dropped
  from seconds to fractions of one on every backend, the JVM class and WASM Preview 1 most of
  all.

  instaparse 1.5.0's README probe as a JVM class: `instaparse.core` load and the whole run
  fell to near the clj 1.12.6 oracle's; on the interpreter the gain is smaller, since its copy
  was already native and its remaining cost is interpretation.
- WASM: a map grown by tens of thousands of `assoc`s leaves every replaced path for the
  collector, and wasmtime's copying collector rescans the live heap, so large maps cost
  progressively more than on the JVM. The clojure-spec pin grows 20,000 for that reason.
- Pins: `testsupport/HashTableCopyPrograms` (the insertion-order walk, a copy's independence, an
  eql and an equalp table's copy, and a table grown to 50,000 keys one copy and one store at a
  time) through
  `LispEvaluatorTest#aHashTableWalksInInsertionOrderAndACopySharesItsStructure`, its JVM twin
  and both WASM twins; `RontoHashTableTest` (a random workload over copies and clears against
  one `LinkedHashMap` each, 200,000 copy-and-store steps under a timeout, eight threads over one
  table); clojure-spec `a-map-grown-one-assoc-at-a-time-shares-its-table`. A REGRESSION IS A
  HANG.

## The identity-hash slot (WASM)

**Invariant: a wasm-GC reference has no address, so an object's identity hash lives IN the
object, and only in a module that can key by identity.** In a module that makes an
`eq`/`eql` table (`Ctx.usesIdentityHashTables`, the same one flag that tags the counts)
`TYPE_CONS` is `{car, cdr, (mut i32) ihash}`, `TYPE_CELL` (a general array, a hash table, a
closure's box) `{value, (mut i32) ihash}` and `TYPE_INSTANCE` `{layout, slots, (mut i32)
ihash}`; the slot is `0` until the object is first keyed. Every other module keeps the
two-field cons and one-field cell byte for byte (checked by hand on three benchmark
modules).

- **Every allocation goes through `WasmEmitHelper.emitNewCons/emitNewCell/emitNewInstance`**
  (`(w, identityHash)` for a runtime builder, `(ctx)` for a compile site), which push the
  slot's `0` exactly when the type section declares it. A site that spelled its own
  `struct.new` would compile clean and fail validation in identity-table modules only;
  the flag reaches the static builders as an explicit `boolean identityHash` parameter,
  the way `charvecPossible` does.
- **`_ihash ((ref null eq)) -> i32`** (`WasmIdentityHashRuntimeBuilder`, `FUNC_IHASH`, the
  last fixed helper, a constant-0 stub in other modules): reads the slot, and on `0`
  assigns `fmix32(++seq)` -- `seq` a `(mut i32)` global at `identityHashSeqGlobalIndex`,
  murmur3's finalizer a bijection so a value of 1 or more never mixes to the unassigned
  `0`, and the mix spreads consecutive assignments over every bit where a bucket mask
  reads only the low ones. A non-aggregate falls through to `_hash`, which `eql`/`eq` on
  such a value agrees with. The hash never moves once assigned, whatever the fields do.
- **Who calls it**: an `eq`/`eql` table's `gethash`/`puthash`/`remhash` placement
  (`_ht_get`/`_ht_put`/`_ht_rem`, `WasmHashTableRuntimeBuilder.emitPlacementHash`), for
  every key -- the entry keeps the hash, so growth never asks again; and `_hash`'s
  `TYPE_CELL` arm in a slotted module, so a vector or table key of an `equal` table -- whose
  `equal` IS identity -- is placed by it too. A cons in an `equal` table still hashes
  structurally. A Clojure program storing a collection key gets the slot through
  `%clojure-key-reps`, an eq table (`.kb/clojure-frontend.md`, "Structural keys").
- **`%identity-hash`** (a `cl` internal, `LispNames.IDENTITY_HASH`) answers the hash an eq
  table places an aggregate by, as a signed 32-bit integer: `_ihash` sign-extended through
  `_int_new` here, `System.identityHashCode` on the JVM (`JvmIdentityHashCompiler`) and the
  interpreter (a host object's of the object it wraps, the wrapper being fresh per Java
  call). A module calling it carries the slot like one making an eq table
  (`LispMacroExpander.programHashesByIdentity`, which the module's gate reads; the JVM's
  table emission keeps `programMakesIdentityHashTable`). An atom's answer is unspecified.
  The Clojure front end's `hash` of a value hashed by identity reads it
  (`.kb/clojure-frontend.md`, "Hashes"). Pinned by
  `WasmLispCompilerIntegrationTest.compileAndRunIdentityHashReadsTheSlotAModuleCallingItCarries`
  (P1 and component: stable through mutation, two fresh conses apart) and its
  `LispEvaluatorTest`/`JvmLispCompilerTest` twins.
- **`TYPE_CLOSURE` carries the slot too, since 2026-09-17** (`.todo/854`): a fourth
  `ref.test` arm in `WasmIdentityHashRuntimeBuilder.build`, the slot as the closure's
  THIRD field (`WasmEmitHelper.emitNewClosure`, every closure-allocation site --
  `WasmEvalRuntimeBuilder`'s eval/apply/store bodies, `WasmFunctionFormCompiler`,
  `WasmLambdaCompiler`, `WasmRuntimeBuilder.emitSymbolClosure`, the async waiter in
  `WasmAsyncEmit`). Pinned by
  `WasmLispCompilerIntegrationTest.compileEqHashTableWithManyClosureAndPackedArrayKeysStaysHashed`
  (100,000 closure keys).
- **A packed array stays in bucket 0 -- a measured, documented decision, not an
  oversight.** `TYPE_FARRAY` (the packed FLOAT array) is a struct and, like the closure,
  COULD carry the slot for the cost of one more `ref.test` arm; but its `struct.new` sites
  are not confined to the compile-time array builders (`WasmArrayCompiler`,
  `WasmQuoteCompiler`) -- two of the eight also sit in the `--simd` kernel runtime
  (`WasmVecSimdRuntimeBuilder`, `WasmLinalgSimdRuntimeBuilder`), which do not thread an
  `identityHash` flag today, so the slot's blast radius reaches a backend the closure
  fix never had to touch. The packed INTEGER vectors (`TYPE_I8ARR`/`TYPE_I16ARR`/
  `TYPE_I32ARR`) are bare wasm `array`s with no wrapper struct at all -- the vector
  VALUE the reader/`aref`/`aset` fast paths hold IS the array reference, so giving one an
  identity slot means wrapping every packed integer vector in a struct, a representation
  change touching every access site, not an added field. Nothing in the corpora keys a
  table by either kind of packed array (the reason `.todo/839` left this a `TYPE_CLOSURE`
  follow-up rather than folding it in), so the blast radius of either change is paid for
  a feature with no measured user. Both keep hashing through `_hash`'s constant-0
  fallback, sharing bucket 0, until something measures otherwise.
- **Cost** (`.kb/wasm-gc-object-size.md`): the slot is free in
  wasmtime's heap layout but not in V8's without pointer compression -- the reason for the
  gate; it also costs module bytes at every cons allocation site plus `_ihash` and the global.
- **Fill + lookup of an `eq` table keyed by n fresh conses, 2026-09-17** (linux-x86-64,
  wasmtime 47.0.3): before the slot the WASM columns were
  quadratic -- which is what `.todo/835`' minute-long write of a
  50,000-element list was; with the slot they track the JVM's.

Pinned by `WasmLispCompilerIntegrationTest.compileEqHashTableWithManyAggregateKeysStaysHashed`
(300,000 identity keys in one table; under bucket 0 that is a chain the runner's 300 s
wasmtime timeout cuts -- A REGRESSION IS A HANG), the same program as
`LispEvaluatorTest.eqHashTableWithManyAggregateKeysStaysHashed` / the JVM `compile` twin,
and ci-spec `identity-hash-table-many-aggregate-keys`.

## Printing
`#<HASH-TABLE :TEST EQUAL :COUNT n>` (`EQUALP`/`EQL`/`EQ` for those tables) on all four backends through
`print`/`princ`/`prin1`/`princ-to-string`/`format ~A`/`~S`, nested included (ci-spec
`hash-table-print-syntax`, `hash-table-identity-test`). No entry content; SBCL's trailing identity hash is deliberately absent
(`.kb/emitted-output-determinism.md`).

- `:TEST` is the test LOOKUP IMPLEMENTS, from the same place per backend as `hash-table-test`:
  `LispHashTable.testCode()`, `_hashTest`, the header count's low two bits. One whole constant
  per test, not one assembled at run time.
- `:COUNT` is the O(1) live count (`_hashSize` on the JVM, `RontoHashTable.count`).
- JVM: the printer arm is keyed on `JvmHashRuntimeBuilder.MAP_CLASS` = `runtime/RontoHashTable`,
  a class of the program's own runtime that no `java:` call answers -- the discriminator `_hashP`
  and the printer share, so a host map stays a host object.
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
