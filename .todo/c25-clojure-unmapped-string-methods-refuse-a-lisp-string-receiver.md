# c25. Clojure: an unmapped `String` method on a string receiver is refused

Difficulty: Medium

Measured 2026-10-03, exec jar vs oracle `clj` 1.12.6. Interpreter and JVM answer alike.

A string receiver answers the mapped core operation (`ClojureInteropLowering.stringMethod`:
`toUpperCase`, `substring`, `indexOf`, ...). Any other `String` method goes to `java:call`,
which refuses a Lisp string as the receiver:

| form | oracle | ronto |
|---|---|---|
| `(.codePointAt "abc" 0)` | `97` | `java:call expects a java object as the first argument, got "abc"` |
| `(.compareTo "a" "b")` | `-1` | same refusal |
| `(String/.compareToIgnoreCase "a" "B")` | `-1` | same refusal |
| `(.matches "abc" "a.c")` | `true` | same refusal |

Choose between mapping more methods one by one and letting the `java:` surface take a Lisp
string as a `java.lang.String` receiver (the interpreter, `JavaBridgeTemplate` and the direct
sites together, `.kb/java-interop.md`). The second covers every method at once, and
`String/.m` already names the class.
Pin the cases in `ClojureInteropTest` against the oracle.
