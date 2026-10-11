# f47. Clojure: a fn inside a collection Java receives is no `Runnable`, `Callable` or `Comparator`

Difficulty: High

A fn Java takes as a `Runnable`, `Callable`, `Comparator` or `Object` argument is the object
the fn is (`JavaImplementations.functionObject`, `.kb/java-interop.md`), but a fn inside a
collection crosses as `%clojure-host-object`'s identity handle (`RontoJavaHandle`), which
implements none of the three. Measured 2026-10-11 against clj 1.12.6, the interpreter and the
JVM alike (`pool` an `Executors/newFixedThreadPool`, `f` a fn):

| program | oracle | here |
|---|---|---|
| `(mapv #(.get %) (.invokeAll pool [(fn [] 1) (fn [] 2)]))` | `[1 2]` | `ClassCastException`: `RontoJavaHandle` to `Callable` |
| `(.invokeAny pool [(fn [] 3)])` | `3` | the same |
| `(let [l (Collections/checkedList (ArrayList.) Runnable)] (.addAll l [f]) (identical? f (.get l 0)))` | `true` | `ClassCastException`: `Attempt to insert class ...RontoJavaHandle element` |

## What decides the design

- Where the member's object comes from. `%clojure-host-member` makes each member's Java
  object before the call (`%clojure-host-members`), so the call site's conversion never sees
  the fn. A `:value` `java:reify` of the three in `clojure.lisp` is a shell in every program
  converting a collection, fn or not; a function flavour of the runtime handle (the three
  over `RontoJavaCalls`, as `compareTo` calls its order function) changes `java:handle` on
  both backends and its user doc.
- Its calls answer as the direct argument's do: through `%clojure-host-call` with the kind
  (`run` none, `call` `:object`, `compare` read as `AFunction.compare`), and it stays equal
  both ways to the function object and the identity handle of the same fn
  (`RontoJavaValue.sameValue`).
- What the chosen shape adds to a program that never hands Java a fn.

## Plan

1. `ClojureInteropTest` rows for the table, interpreter and JVM.
2. The member's object on both backends, equal to the direct argument's.
3. `doc/*/clojure/deviations.md`'s fn bullet: the `invokeAll` sentence goes.
