# f10. Clojure: a deftype or reify crosses into Java as an implementation of its interfaces

Difficulty: High

A Clojure value reaches a `java:` member through `%clojure-host-member` (`.kb/clojure-frontend.md`,
"Java interop"): a deftype or reify is an identity handle, an object of its own class equal only
to itself. The oracle's is an instance of every Java interface its body implements, and Java
calls its `Object` overrides. Measured 2026-10-09 against clj 1.12.6, on the interpreter and the
JVM:

| program | oracle | here |
|---|---|---|
| `(deftype Task [] Runnable (run [_] (println "ran"))) (doto (Thread. (Task.)) .start .join)` | `ran` | `No matching constructor for java.lang.Thread with 1 argument(s)` |
| `(doto (Thread. (reify Runnable (run [_] (println "ran")))) .start .join)` | `ran` | the same |
| `(deftype Box [x] Comparable (compareTo [_ o] (compare x (.-x o)))) (vec (map #(.-x %) (java.util.TreeSet. [(Box. 2) (Box. 1)])))` | `[1 2]` | `ClassCastException: class user.Box cannot be cast to class java.lang.Comparable` |
| `(deftype P [x] Object (equals [_ o] (and (instance? P o) (= x (.-x o)))) (hashCode [_] (hash x)))` then `(.size (java.util.HashSet. [(P. 1) (P. 1)]))` | `1` | `2` |

Before e89 the first three failed with `No matching constructor` too (the value crossed as its
wrapper list).

## Plan

1. The lowering knows each deftype's and reify's Java interfaces and `Object` overrides where it
   defines the type (the rows under its tag, "Host interfaces"). Emit beside the type a maker
   over literal interface names -- `java:proxy` (or `java:reify` per method) calling the type's
   own methods, `equals`/`hashCode`/`toString` included -- so the JVM resolves it to a generated
   class and nothing reflects.
2. The object must come back as the value: a `java:proxy` / `java:reify` has no value today.
   Give the generated implementation (and the interpreter's `Proxy`) the
   `runtime/RontoJavaValue` contract, e.g. a value the form ends with, which every unmarshal
   then answers (`.kb/java-interop.md`, "Handles and views").
3. `%clojure-host-member` makes a typed value through its maker, the identity handle staying
   for a type implementing no Java interface and overriding no `Object` method. Pin in
   `ClojureInteropTest`; the deviation clause in `doc/{en,ja}/clojure/deviations.md` ("Java sees a
   deftype or reify implement none of its Java interfaces ...") goes.
