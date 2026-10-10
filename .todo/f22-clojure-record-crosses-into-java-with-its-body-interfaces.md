# f22. Clojure: a record crosses into Java with the Java interfaces its body implements

Difficulty: Medium

A deftype or reify crosses into Java as its face (`.kb/clojure-frontend.md`, "Java faces"): an
implementation of the Java interfaces its body implements. A record still crosses as its `:map`
view (`%clojure-host-member`, a `RontoJavaMapView`), so Java sees none of its body's interfaces.
The oracle's record class implements `java.util.Map` and every interface its body names.
Measured 2026-10-10 against clj 1.12.6, on the interpreter:

| program | oracle | here |
|---|---|---|
| `(defrecord Person [name age] Comparable (compareTo [_ o] (compare age (:age o))))` then `(vec (map :name (java.util.TreeSet. [(->Person "b" 2) (->Person "a" 1)])))` | `[a b]` | `ClassCastException: class am.ik.rontolisp.runtime.RontoJavaMapView cannot be cast to class java.lang.Comparable ...` |
| `(defrecord Job [] Runnable (run [_] (println "job")))` then `(doto (Thread. (->Job)) .start .join)` | `job` | `No matching constructor for java.lang.Thread with 1 argument(s)` |

## Plan

1. A record whose body names a Java interface gets a face too (`ClojureJavaFaces.give` skips
   `defrecord` today): a `java:reify` over `java.util.Map` and the body's Java interfaces,
   standing for the record (`:value`, `:class` its class), each `Map` method calling the record's
   map view (`(the (java:object "java.util.Map") view)`, so the calls resolve and nothing
   reflects) and each body method its row.
2. `%clojure-host-member`'s record clause asks for the face first (the java-face family's arm),
   so a record with no Java interface keeps its view and a program registering no face splices
   as before. `equals`/`hashCode` stay the view's (the oracle's `APersistentMap.mapEquals` and
   map hash); `toString` the record's override or its `str`.
3. Pin in `ClojureInteropTest` (the two rows), and drop "none of the interfaces a record's body
   implements" from the deviation in `doc/{en,ja}/clojure/deviations.md`.
