# e69. Clojure: `false`, maps and fns across the `java:` boundary

Difficulty: Medium

Three Clojure values do not cross to Java as the oracle's do. Measured 2026-10-08 on the
interpreter (`java -cp target/classes ... RontoLispCli`) against `clj` 1.12.6:

| program | oracle | here |
|---|---|---|
| `(let [l (java.util.ArrayList.)] (.add l false) (vec l))` | `[false]` | `No matching method java.util.ArrayList.add with 1 argument(s)` |
| `(Boolean/toString false)` | `false` | no matching method |
| `(.removeIf (java.util.ArrayList. [1 2 3]) odd?)` | `[2]` | `java:reify: cannot return \|false\| as boolean from java.util.function.Predicate.test` |
| `(.test (proxy [java.util.function.Predicate] [] (test [x] false)) 1)` | `false` | `java:proxy: cannot return \|false\| ...` |
| `(java.util.HashMap. {"a" 1})` | `{a=1}` | `No matching constructor for java.util.HashMap with 1 argument(s)` |
| `(.call (fn [] 5))` | `5` | `java:call expects a java object as the first argument, got #<lambda>` |

- `false` is the symbol `|false|` (`rontolisp::%clojure-false`); `java:` marshals no symbol,
  so it reaches no `boolean`/`Boolean`/`Object` parameter and no callback return.
- A Clojure map is a CL hash table; `java:` marshals no hash table (the oracle's
  `PersistentArrayMap` is a `java.util.Map`). Sets and vectors as `Collection` likewise
  (a vector already marshals as a `List`).
- A fn as a RECEIVER: the oracle's fn is a `Runnable`/`Callable`/`Comparator`
  (`AFunction`); `.call`/`.run`/`.compare` on one are refused here. `Comparator`'s
  boolean answer (`AFunction.compare`: true -1, else the reversed call) is part of this.
- `(proxy [Super] [fn] ...)` constructor arguments convert a fn as `java:proxy` (name
  first), not as the `:functional` sites do (`.kb/clojure-frontend.md`, "Java interop").

## Plan

1. Decide where Clojure `false` becomes Java false without the `java:` runtime learning a
   Clojure name (`.kb/clojure-frontend.md`, invariant): e.g. a `java:` notion of the
   program's false object, or a Clojure-side conversion at the sites the lowering emits.
   Same for a map/set argument. Measure what each costs on the JVM direct sites
   (`.kb/java-interop.md`, the kind codes and `_jconv$N`) before choosing.
2. Pin on the interpreter and the JVM (`ClojureInteropTest`), the bridge parity test.
3. `doc/{en,ja}/clojure/deviations.md` (the fn bullet names the `false` gap),
   `.kb/clojure-frontend.md` "Java interop".
