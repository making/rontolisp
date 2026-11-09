# Clojure: persistent-sorted-set through its `.cljs` port -- datascript's indexes on every backend

Difficulty: High

j26 decided, with the measurements in `.kb/clojure-frontend.md` ("Classes compiled against
`clojure.lang`", the persistent-sorted-set bullet), to read persistent-sorted-set 0.3.1's own
ClojureScript port where its `.clj` wraps Java classes compiled against `clojure.lang`.
datascript 1.8.1 (`{:deps {datascript/datascript {:mvn/version "1.8.1"}}}`, j07's
`datascript/` probe: `create-conn` with a ref schema, `transact!`, five `q` shapes, `entity`,
`pull`, `datoms`, a retract; oracle in j07's `oracle.out`) loads verbatim since j26 and stops
at its first `create-conn`:

```
Cannot link class me.tonsky.persistent_sorted_set.PersistentSortedSet: missing class clojure.lang.IEditableCollection
```

(interpreter; the JVM class with the jar on its run class path the same; wasm P1 `The
function JAVA:NEW is undefined`).

## What to build

1. The selection: a namespace whose `.clj` imports a class that cannot link here (its
   supertypes or signatures name `clojure.lang`; the JVM legs already know: `Cannot link class
   ...`; the native binary reads class files) and whose jar ships a `.cljs` of the same
   namespace is read from the `.cljs`. Decide the rule's exact trigger (an import that cannot
   link, or a per-namespace table) and where it is visible to the user (user docs: the jar's
   `.clj` is not what runs).
2. The ClojureScript surface the port spells (`me/tonsky/persistent_sorted_set.cljs`, 1111
   lines; measured by j26):
   - `(:require-macros [me.tonsky.persistent-sorted-set.arrays :as arrays])` -- the first stop
     (`ns clause :require-macros is not supported yet`); `arrays.cljc` is a `.cljc` whose
     macros take their `:clj` branch here (`clojure.lang.RT/aget`/`alength`/`aset`, answered
     since j26; `Arrays/copyOf`, `System/arraycopy`, `Arrays/parallelSort`,
     `java.lang.reflect.Array/newInstance` need portable forms for wasm);
   - `js/Math.pow` x3, `js/Math.floor` x2, `js/Error.` x3, a JS array's `.slice` x9;
   - `^:mutable` fields (ClojureScript's mutable marker, which `.clj` keeps immutable) and
     their `set!`;
   - three deftypes (`BTSet`, `Iter`, `ReverseIter`) and `Chunk` over 24 `cljs.core`
     protocols -- `ISeqable`, `IReversible`, `IReduce`, `IPrintWithWriter`, `IEquiv`,
     `ISequential`, `ISeq`, `ICounted`, `IWithMeta`, `IMeta`, `ITransientSet`,
     `ITransientCollection`, `ISet`, `ILookup`, `IIndexed`, `IHash`, `IFn`,
     `IEmptyableCollection`, `IEditableCollection`, `ICollection`, `ICloneable`,
     `IChunkedSeq`, `IChunkedNext`, `IChunk` -- each a `clojure.lang` interface row here
     under another method name (`-seq`/`seq`, `-count`/`count`, `-lookup`/`valAt`,
     `-conj`/`cons`, `-disjoin`/`disjoin`, `-reduce`/`reduce`, `-equiv`/`equiv`,
     `-hash`/`hasheq`, `-pr-writer`/`print-method` ...), plus its own protocols `INode`,
     `IIter`, `ISeek`.
3. Then the probe: past the sorted set, whatever datascript's query, pull and entity paths
   still need (j26 fixed what its load needed; the run past `create-conn` was never reached).

## Done when

datascript's probe prints j07's 8 oracle lines on the interpreter and the JVM class (wasm
measured and its row written), the KB's "Library probes" row says so, and the
persistent-sorted-set bullet in "Classes compiled against `clojure.lang`" records what the
selection rule became.
