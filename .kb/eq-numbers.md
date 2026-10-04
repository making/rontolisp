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
  The wasm walk also answered NIL for a pair removed past the first key (measured 2026-10-04,
  both wasm backends; `br` out of the `if` targeted the void `$nil` block, one label short of
  `$result`, which dropped the `t`). Now T on all four, pinned by the fixture's answer rows and
  ci-spec `remf-answer-past-the-first-key`; the fix is one operand byte, so no size moved
  (a program without `remf` is byte-identical).
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

### `equal` of a host object and a Lisp value (decided 2026-10-04)

- A host object on the LEFT asks its `equals`, handed what an `Object` parameter receives:
  nil's `null`, another host object, or the receiver rule's one object
  ([java-interop.md](java-interop.md), "A Lisp value as a `java:call` receiver"); any other
  value (symbol, cons, vector, ratio, function, table) is `equal` to no host object and
  `equals` is not asked. A Lisp value on the left is never `equal` to a host object. Clojure's
  `=` asks its left operand (`Util.equiv`), so `%clojure-equal`'s `equal` arm agrees.
- Why not convert a list or vector to the `ArrayList` a `java:call` argument gets: a host
  list would then be `equal` to `(1 2)` while `(1 2)` is not `equal` to it. With atoms
  only, a host object honouring `equals`' symmetry is never `equal` to a Lisp value (every
  converted image is a final JDK class -- `String`, the boxes, `BigInteger`, `Character`,
  `Boolean` -- that no host object is, `unmarshal` turns them into Lisp values), so only
  an `equals` breaking symmetry answers T, and then as in Clojure and Java.
- The `equal` hash is unchanged: a host hashes by its `hashCode`, a Lisp value
  structurally. They could agree only for an `equals` breaking symmetry, for which a table
  promises nothing (`java.util.HashMap` neither).
- Copies: interpreter `LispEquality.equal` over `LispJavaObject.receiverObject` (also
  `JavaInterop`'s receiver, its one interpreter copy); JVM `_equal`'s host arm over
  `_jrecv` (`JvmJavaDirectSites.receiver()`, passed in as `hostReceiver`).
- Before (measured 2026-10-04): the interpreter answered NIL for every Lisp value (the
  `LispJavaObject` record's `equals` refuses another record type); the JVM handed `equals`
  the compiled representation (a framed `"\"s\""`, an `int[]` character, `"T"`, a symbol's
  name, a cons `Object[]`, a ratio), so a reify answering true was `equal` to all of them;
  clj `(= p [1])` was true on the JVM (oracle false).
- Cost (2026-10-04): programs without `java:` byte-identical (wasm P1, `--optimize=size`,
  component, JVM jar: hello_world, pi_approx, zlib, calc, contact-book, word-frequency,
  sorting, error-handling, `demo.clj`). A `java:` program using `equal` grows by the arm
  (+23 B, `life-gui.lisp`) or, when it had no `_jrecv`/`_jkind`, by them too (+597/598 B
  class `java-store.lisp`/`swing.lisp`, +287 B the pinning program); `java-interop.lisp` (no `equal`) unchanged. Speed
  within noise: interpreter 10.8M `equal` over atoms, JVM 108M in a `java:` program.
- Pins: `JavaInteropPrograms.HOST_EQUAL_LISP_VALUE_PROGRAM` (`JavaInteropTest` /
  `JvmJavaInteropCompilerTest#equalOfAHostObjectAndALispValueAsksEqualsWithTheValueAsJavaSeesIt`),
  `ClojureInteropTest#equalsOfAHostObjectAndAValueAsksTheLeftOperand` (oracle-identical),
  `JavaBridgeTemplateParityTest#theBridgeAndADirectSiteCallALispValueAsTheSharedRuleSays`
  (the interpreter copy beside the bridge's and `_jrecv`).

### Clojure `=` of a host collection (decided 2026-10-04)

- The oracle's `Util.equiv` hands a pair holding an `IPersistentCollection` to that
  collection's `equiv` (`pcequiv`), which takes a `java.util.List` (element by element with a
  sequential), `Map` (with a map) or `Set` (with a set) of its kind, either operand first;
  `equals` is never asked. `%clojure-equal` asks `%clojure-host-equal-p` last, after `equal`
  answered NIL (`(t (or (equal a b) ...))`, and before `(t nil)` in `%clojure-sorted-equal`):
  `equal` hands a host no collection (above), so the order changes no answer. CL `equal` is
  untouched (the symmetry argument above).
- The walk reads the host through `toArray` (unmarshalled: atoms become Lisp values, a nested
  host collection stays one and recurses through `%clojure-equal`) and looks each key or
  member up in the CLOJURE side by its own lookup (`%clojure-sorted-lookup`: `=`, a sorted
  one's comparator), sizes compared first. Iterating the host side is forced: a keyword key
  cannot be marshalled for a host `containsKey`. Not an iterator: `(java:call it "hasNext")`
  on a `HashMap$EntryIterator` is refused (declared on the non-public `HashIterator`).
- A host collection is no structural key, and `%clojure-hash` is unchanged: a table finds it
  by its own `equals`/`hashCode`. The oracle agrees for its hash maps and sets
  (`(contains? #{[1 2]} al)` false) and not for its array maps, which scan by `=`
  (`(get {[1 2] :v} al)` `:v`, here nil; `frequencies` likewise). Making it a key would put a
  host test on every lookup and break `(count (set [al [1 2]]))` 2, the oracle's answer.
- Not modeled: Java `equals` between host keys that unmarshal to one Lisp value (an
  `Integer` and a `Long` key are both `1`), a host `Boolean.FALSE` element (unmarshalled to
  nil, `java-interop.md`), `nil` as the empty list (`(= (java.util.ArrayList.) nil)` true, like
  `(= [] nil)`).
- A `java:` program only: the test is the second of `ClojureArms.Family.HOST`'s, so a program
  naming no `java:` operator sheds the arm and both bodies are what they were.
- Measured 2026-10-04 (oracle clj 1.12.6): 51 pairs of lists, maps, sets, sorted collections,
  records, lazy and infinite seqs, nested host lists -- 29 answers wrong before, oracle-identical
  after on the interpreter and the JVM; `List/of`, `subList`,
  `unmodifiableList`, `Map/of`, `ConcurrentHashMap`, `LinkedHashMap`, `TreeMap`, `Set/of`,
  `TreeSet`, `LinkedHashSet`, `keySet` agree too.
- Cost: programs without `java:` byte-identical (wasm P1, `--optimize=size`, component, JVM
  class: `demo.clj`, a 10x10 `=` matrix over every kind incl. sorted, a `defn` over `=`; all
  four backends print the same). A `java:` program reaching `=` grows by the two functions,
  `%clojure-host-instance-p` and `%clojure-sorted-lookup`: JVM class 113,241 -> 117,288 and
  115,397 -> 120,653 B (a first cut through `%clojure-set-count` pulled the seq runtime:
  +7.6 KB). Speed: JVM `=` loop in a `java:` program, 3M unequal pairs, 1,604 -> 1,645 ms
  (medians of 5); interpreter, which keeps the arm, 600k unequal pairs straight into
  `%clojure-equal` 7.1 -> 8.7 s (medians of 5, noisy host; the arm's call alone measured
  ~7.8, the type tests ahead of `%clojure-lisp-value-p` cut it from ~9.8), a mixed program
  (`cond` over keywords, `filter`, `frequencies`, `distinct`) 12.4 -> 12.3 s, within noise.
- Pins: `ClojureInteropTest#aHostCollectionIsEqualToAClojureCollectionOfItsKind`
  (oracle-identical), `ClojureLibraryTest#aProgramNamingNoJavaOperatorComparesWithoutTheHostCollectionArm`.

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
