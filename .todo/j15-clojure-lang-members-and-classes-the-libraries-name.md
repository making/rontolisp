# Clojure: `clojure.lang` constructors, statics, members and class names five libraries use

Difficulty: Medium

The j07 probe round met these `clojure.lang` (and `clojure.core$...`) references. Each is
refused at lowering (`unknown name`) or at run time (`No such class`), on the interpreter and
the JVM class alike (wasm not run). Each row is small; they share the mechanism the KB's
"Collection interfaces" (the `clojure.lang` statics: `RT/map`, `PersistentHashSet/createWithCheck`
...) and "Host classes" (`ClojureLangJar`) sections describe, so they are one item.

## Repro (2026-11-08, develop `a3c68a63f`; oracle clj 1.12.6.1673)

| form | oracle | here | used by |
|---|---|---|---|
| `(ex-data (clojure.lang.ExceptionInfo. "m" {:a 1}))` | `{:a 1}` | `No such class: clojure.lang.ExceptionInfo` (run time) | schema `macros.clj:65` `error!`: every failed `s/defn` input check throws this, so `with-fn-validation` reports a `ClassCastException` from `+` instead of `:schema.core/error` |
| `(let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "{:a 1}\n:b"))] [(read r) (read r) (.getLineNumber r)])` | `[{:a 1} :b 3]` | `No such class: clojure.lang.LineNumberingPushbackReader` | aero `core.cljc:194`: every `read-config` (wrapping `io/reader` of a file or `Reader`, then `edn/read` with `:readers`, and `.getLineNumber` for the error message) |
| `(get clojure.lang.Compiler/CHAR_MAP \-)` | `"_"` | in a macro: `No such class: clojure.lang.Compiler` | schema `utils.cljc:58` `(defmacro char-map [] clojure.lang.Compiler/CHAR_MAP)` -- a macro answering the whole map as a literal; `unmunge` (fn names in error messages) |
| `(clojure.lang.PersistentArrayMap/createAsIfByAssoc (object-array [:a 1 :b 2 :a 3]))` | `{:a 3, :b 2}` | `No such class` | specter `navs.cljc:143,331` (`MAP-VALS`, `MAP-KEYS` transform of a small map) |
| `(clojure.lang.PersistentArrayMap. (object-array [:a 1]))` | `{:a 1}` | `No such class` | specter `navs.cljc:301` |
| `(let [m {:a 1 :b 2}] [(.next (.keyIterator m)) (.next (.valIterator m))])` | `[:a 1]` | `Method keyIterator taking 0 args is not supported for class clojure.lang.PersistentArrayMap` | specter `navs.cljc:119,277,307` (`IMapIterable`) |
| `(instance? clojure.lang.Cons (cons 1 '(2)))` | `true` | `unknown name: clojure.lang.Cons` (lowering) | specter `impl.cljc:444,648` |
| `(instance? clojure.lang.ArraySeq (seq (object-array [1])))` | `true` | `unknown name` | -- (same family) |
| `(instance? clojure.lang.ChunkedCons 1)` | `false` | `unknown name` | -- |
| `(instance? clojure.lang.PersistentStructMap m)` | `false` for a map | `unknown name` | schema `core.cljc:806` (map schema walker) |
| `(defn f [k v] (clojure.lang.MapEntry k v))` then `(f 1 2)` | loads; the call throws `ClassCastException` (a class is not a fn) | `unknown name: clojure.lang.MapEntry` (lowering) | datascript `db.cljc:250` (a latent bug in datascript that the oracle tolerates because the method is never called) |
| `(= clojure.core$long (class long))` | `true` | `unknown name: clojure.core$long` | datascript `db.cljc:80` `(assoc meta :tag clojure.core$long)` |

`LazySeq`, `PersistentList`, `APersistentMap`, `ASeq`, `IRecord`, `IType`, `MultiFn`, `Ratio`,
`Atom`, `Volatile`, `Delay`, `Namespace`, `PersistentTreeMap` already lower in `instance?`
(measured the same day). The missing ones are classes the oracle's jar has but the KB's list does
not.

## Direction, per row

- `ExceptionInfo.` (2 and 3 arguments): the same value `ex-info` makes; `(ExceptionInfo. msg map
  cause)` too.
- `LineNumberingPushbackReader`: a pushback reader value over any reader the program has
  (string, file, `io/reader` result), `read`/`edn/read` over it, `.getLineNumber`,
  `.getColumnNumber`, `.readLine`, `.unread`. On wasm through the portable reader values (KB
  "`clojure.java.io`").
- `Compiler/CHAR_MAP`: a constant map (the oracle's 26 entries); `munge`/`demunge` already use
  the same table (KB, the protocol-interface paragraph: "`munge`/`CHAR_MAP`").
- `PersistentArrayMap/createAsIfByAssoc`, `PersistentArrayMap.` over an object array:
  the map the pairs make (`createAsIfByAssoc`: later duplicates win, the oracle's order;
  the constructor: no duplicate check, like the oracle -- decide what a duplicate does here).
- `.keyIterator`/`.valIterator` of a map (and of a record?): `java.util.Iterator` values over
  the keys/vals in the map's walk order (KB "Collection interfaces": iterators exist).
- `instance?` of `Cons`, `ArraySeq`, `ChunkedCons`, `PersistentStructMap`, and the rest of the
  jar's public classes: lower to the kind test the representation allows (`Cons`: what does
  `(cons 1 '(2))` answer here -- a list? then `Cons` is `false` where the oracle says `true`, a
  pinned-deviation decision; `PersistentStructMap`: `false` while struct maps are plain maps,
  `.todo/f75` history). The rule to aim for: no class the oracle's jar has is an `unknown name`
  in a class position.
- A class symbol in call position: lower to the oracle's call-time `ClassCastException`
  (`class java.lang.Class cannot be cast to class clojure.lang.IFn`), not a lowering error.
- `clojure.core$NAME`: the class of a core fn value (`(class long)`), which also means
  `(class f)` of a core fn answers that name -- coordinate with `.todo/h09` (class values),
  whose direction already lists `AFunction`.

## Done when

- Every row prints the oracle's answer on the interpreter and the JVM, and on both wasm
  backends where the value exists there (ci-spec cases, one per family; refusals named in
  `doc/{en,ja}/clojure/deviations.md` where a row stays a deviation).
- aero, schema and specter run without the hunks of j07's `patches.diff` for these sites.
