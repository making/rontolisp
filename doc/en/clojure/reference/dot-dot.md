# ..

`(.. receiver step... name)`

Nests `.` forms: each step's answer is the next receiver. A bare trailing name reads, a
listed one `(m args...)` calls. Runs on the interpreter and the JVM only -- the wasm
backends reject `java:`.

```clojure
(println (.. "hi" (toUpperCase) (length))) ; 2
```
