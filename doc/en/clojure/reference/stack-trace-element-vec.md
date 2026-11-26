# StackTraceElement->vec

`(StackTraceElement->vec element)`

Answers the oracle's `[class method file line]` vector of the host stack-trace element:
the class and the method as symbols, the file as written (`nil` when the element names
none), the line as written. `nil` is the oracle's `NullPointerException`, anything else
its `ClassCastException`; of no argument the oracle's arity refusal. Works as a function
value too. Elements exist only on the interpreter and the JVM (`.getStackTrace` answers
`[]`), so on the wasm backends only the two refusals can run
(see [Deviations](../deviations.md)).

```clojure
(prn (StackTraceElement->vec (StackTraceElement. "C" "m" "F.clj" 42))) ; [C m "F.clj" 42]
```
