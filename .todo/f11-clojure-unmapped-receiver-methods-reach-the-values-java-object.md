# f11. Clojure: a method no row names on a collection, keyword or ratio receiver

Difficulty: Medium

A collection, keyword, symbol, ratio or atom receiver answers its common methods through the
core verbs (`ClojureValueMethodLowering.valueArm`, `.kb/clojure-frontend.md`, "Java interop"),
`.hashCode` through `%clojure-java-hash`, and refuses any other method by name. Since e89 every
such value has a Java object of its own (`%clojure-host-member`: a read-only `java.util` view, a
handle), which answers the rest as the oracle's object does. Measured 2026-10-09 against clj
1.12.6, on the interpreter and the JVM:

| program | oracle | here |
|---|---|---|
| `(vec (.toArray [1 2]))` | `[1 2]` | `Method toArray taking 0 args is not supported for class clojure.lang.PersistentVector` |
| `(.containsAll [1 2] [1])` | `true` | `Method containsAll taking 1 args is not supported ...` |
| `(count (.entrySet {:a 1}))` | `1` | `Method entrySet taking 0 args is not supported for class clojure.lang.PersistentArrayMap` |
| `(let [v [3 1 2]] (.sort v nil))` | `UnsupportedOperationException` | `Method sort taking 1 args is not supported ...` |
| `(let [it (.iterator [1 2])] (.next it) (.remove it))` | `UnsupportedOperationException` | `Method remove taking 0 args is not supported for class java.lang.Object` |

The last row's receiver is what the mapped `.iterator` answers, a Lisp value too.

## Plan

1. On a host target, the arm for a method no row names calls it on the receiver's Java object:
   `(java:call (%clojure-host-member recv) "m" args... :java-false)`; wasm keeps the refusal (no
   host there). A method the object lacks is then `java:`'s `No matching method`, where the
   oracle says `No matching method m found taking N args for class C`: keep the oracle's words
   (the refusal's carrier) for that case.
2. `ClojureInteropTest#collectionKeywordSymbolAndRatioReceiversAnswerTheirCommonMethods` pins
   the refusal of `.hashCode2`, a method no class has: it stays refused, in the oracle's words.
   Pin the rows above beside it.
