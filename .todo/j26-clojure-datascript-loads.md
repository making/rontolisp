# Clojure: datascript loads -- `deftype*`, the primitive `IFn` interfaces and persistent-sorted-set

Difficulty: High

datascript 1.8.1 (`{:deps {datascript/datascript {:mvn/version "1.8.1"}}}`; probe in j07's
`datascript/`: `create-conn` with a ref schema, `transact!`, five `q` shapes, `entity`, `pull`,
`datoms`, a retract) stops at load, and patching past four stops reached a fifth that needs a
decision. Its own deps: persistent-sorted-set 0.3.1, extend-clj 0.1.0 (both by the same
author).

## Stops in load order (2026-11-08, develop `a3c68a63f`, interpreter)

1. `datascript/db.cljc:80` `clojure.core$long` as a class value -> j15.
2. `db.cljc:250` `(clojure.lang.MapEntry k v)` (a class in call position, never called) -> j15.
3. `db.cljc:481-498` `defcomp`: a `reify` of `java.util.Comparator`, `clojure.lang.IFn` AND
   `clojure.lang.IFn$OOL` (`invokePrim`) -- `Unable to resolve classname: clojure.lang.IFn$OOL`
   (`ClojureInterfaces`). Repro:

   ```clojure
   (def c (reify java.util.Comparator (compare [_ a b] (compare a b))
                 clojure.lang.IFn (invoke [this a b] (.compare this a b))
                 clojure.lang.IFn$OOL (invokePrim [this a b] (.compare this a b))))
   (prn (c 1 2) (sort c [3 1 2]))
   ```

   oracle `-1 (1 2 3)`. The `IFn$` primitive interfaces (`IFn$LL`, `IFn$DD`, `IFn$OOL`, ... 358
   of them in clojure 1.12.6's jar) only add `invokePrim`; accepting them as members of the IFn row family
   (their `invokePrim` reachable through `.invokePrim`, a call of the object going through
   `invoke`) is enough for every user seen.
4. `db.cljc:132-156, 675` `defrecord-updatable`: `(macroexpand-1 (list* 'defrecord name fields
   impls))`, then a `postwalk` that finds the `deftype*` form inside the expansion, de-duplicates
   its `:implements` list and removes the record's default methods the caller overrides
   (`hashCode`, `hasheq`, `count`, `equiv`, `empty` ...). Here `(macroexpand-1 '(defrecord R [a]))`
   answers the form unchanged (oracle: `(let [] (declare ->R) ... (deftype* user/R user.R [a __meta
   __extmap __hash __hasheq] :implements [clojure.lang.IRecord ...] ...) ...)`), the walk finds
   nothing, and the record with `IHashEq` named twice is refused like the oracle refuses it
   (`Duplicate interface name "clojure/lang/IHashEq" in defrecord`). Then
   `datascript/conn.cljc:11` uses extend-clj's `deftype-atom`, which writes `deftype*` directly
   (`extend_clj/core.cljc:165`, `:implements [clojure.lang.IMeta IReference IDeref IRef IAtom
   IAtom2 ...]`): `unknown name: deftype*`.
   Needed: `defrecord`/`deftype` macroexpand to the oracle's shape, and `deftype*` (with
   `:implements`, the mutable-field metadata, the record's extra fields) is a special form the
   lowering accepts -- i.e. `deftype`/`defrecord` become ordinary macros over `deftype*`, the way
   the KB already lowers `let`/`loop`/`letfn`/`case` through `let*`/`loop*`/`letfn*`/`case*`
   ("Macros": the core macros expanded as data to the special forms). Also `reify` -> `reify*`.
   And the `IAtom`/`IAtom2`/`IRef`/`IReference` interfaces a type body implements (KB "Host
   interfaces": `IDeref` is supported; `IAtom`'s `swap`/`reset`/`compareAndSet` are what
   `swap!`/`reset!` must call on such a type).
5. Behind those: `me.tonsky.persistent-sorted-set` (persistent-sorted-set 0.3.1) is Java:
   `PersistentSortedSet extends APersistentSortedSet extends clojure.lang.APersistentSet`,
   plus `ANode`/`Leaf`/`Branch`, used from Clojure through `.cons`, `.disjoin`, `.slice`,
   `.rslice`, `RT` and `Seq`. KB "Classes compiled against `clojure.lang`" decided (2026-10):
   no `clojure.lang` bridge; such a class refuses catchably. So datascript on the `:clj` branch
   cannot run here whatever 1-4 do. The jar also ships `me/tonsky/persistent_sorted_set.cljs`, a
   pure ClojureScript implementation (uses `cljs.core` protocols: `ISeq`, `IReduce`, `-lookup`,
   `goog` arrays ...).

## The decision 5 needs

Options to weigh, with numbers (load time, the probe's run time, what each costs in front-end
surface), not by preference:

- a. Revisit "no `clojure.lang` bridge" for classes that EXTEND `APersistentSet`/`APersistentMap`
  (persistent-sorted-set, potemkin's `PersistentMapProxy`, data.avl?): host objects that are
  `IPersistentSet`s answering the collection verbs through their Java methods (`seq`, `cons`,
  `contains`, `count` ...) on the interpreter and the JVM; wasm refuses. The census in KB
  "Classes compiled against `clojure.lang`" lists which jars this reaches.
- b. Read the library's `.cljs` port when the `.clj` file needs a class compiled against
  `clojure.lang` -- needs `cljs.core` protocol names (`IReduce`, `ISeqable`, `ILookup` -> the
  `clojure.lang` rows already supported) and `goog.array`; a large surface, but it is the
  portable path the library's author already maintains, and it would serve wasm.
- c. Leave datascript red; record why in the KB table.

Measure what (a) and (b) would each need for datascript before choosing; split the chosen one
into its own item if it is large.

## Done when

- Items 3 and 4 are done (repros above print the oracle's lines on all four backends; `deftype*`
  and `reify*` pinned by ci-spec cases, `macroexpand-1` of `defrecord`/`deftype`/`reify` answering
  the oracle's shape up to gensym names).
- 5 is decided with the measurements in `.kb/clojure-frontend.md`, and datascript's probe either
  prints the oracle's 8 lines on the interpreter and the JVM or its KB row says exactly what stops
  it.
