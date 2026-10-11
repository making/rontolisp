# f39. Clojure: a fn where Java expects an `Object`, and a fn answering a primitive

Difficulty: High

A fn crossing into Java is converted by the `java:` surface: an argument where a functional
interface is expected becomes an implementation Java calls through `%clojure-host-call`
(`java:`'s `:through`, `.kb/java-interop.md` "Markers"), which answers an object method the
value's own object. Two crossings still differ from clj 1.12.6, measured 2026-10-11, the
interpreter and the JVM alike:

| program | oracle | here |
|---|---|---|
| `(let [f (fn [] 1) l (java.util.ArrayList.)] (.add l f) (identical? f (.get l 0)))` | `true` | `No matching method java.util.ArrayList.add with 1 argument(s)` |
| `(let [l (java.util.ArrayList. [1 2 3])] (.removeIf l (fn [x] nil)) (vec l))` | `NullPointerException` | `[1 2 3]` |
| `(.removeIf (java.util.ArrayList. [1]) (fn [x] :kw))` | `ClassCastException` (`Keyword` to `Boolean`) | `java:reify: cannot return (:C%KEYWORD "kw") as boolean ...` |
| `(.sum (.mapToInt (.stream [1 2 3]) (fn [x] (/ x 2))))` | `2` | `java:reify: cannot return 1/2 as int ...` |
| `(.sum (.mapToInt (.stream [1]) (fn [x] 1.9)))` | `1` | `java:reify: cannot return 1.9 as int ...` |
| `(.sum (.mapToInt (.stream [1]) (fn [x] 4294967297)))` | `ArithmeticException: integer overflow` | `1` |
| `(.sum (.mapToInt (.stream [1]) (fn [x] nil)))` | `NullPointerException` (`Character.charValue`) | `java:reify: cannot return NIL as int ...` |
| `(.sum (.mapToDouble (.stream [1]) (fn [x] 1/2)))` | `0.5` | `java:reify: cannot return 1/2 as double ...` |

## What decides the design

- A primitive answer: the oracle's functional-interface adapter casts it per type -- a
  `boolean` by `(Boolean) x` (nil the NPE, any other value the CCE), an `int` by
  `RT.intCast` (a number's `long` value range-checked, `integer overflow`; anything else a
  `Character` cast), a `double` by a number's `doubleValue`. `:through` hands its function
  only whether the method answers an object; the oracle's casts need the primitive's type.
  Handing the return type's kind instead (`:boolean`, `:int`, ... `:object`, nil for
  `void`) changes the `:through` protocol on the three copies (interpreter `callBack`, the
  bridge's, `_jimpl$K`'s constant) and its user doc.
- A fn where `Object` is expected: a FUNCTION argument costs `NO_MATCH` against `Object`
  (`JavaOverloads.kindCost`), so no overload taking an `Object` matches. The oracle's fn is
  an `AFunction` object; here a fn inside a collection already crosses as an identity handle
  (`%clojure-host-object`), and a fn converted to `Runnable`/`Callable`/`Comparator` stands
  for itself. A cost for `Object` changes overload choice and the kind memos on every
  backend's resolution (`.kb/java-interop.md`, "Resolution").

## Plan

1. `ClojureInteropTest` rows for the table, interpreter and JVM.
2. The return kind through `:through`, `%clojure-host-call` casting a primitive answer as the
   oracle does; then the `Object` crossing, measured against the overload choices it moves.
3. `doc/*/clojure/deviations.md`'s fn bullet.
