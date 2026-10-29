# Clojure: on wasm a `java:` call-time error is caught by a specific exception class

Difficulty: Medium

```clojure
(prn (try (BigInteger. (byte-array 3)) (catch NumberFormatException e :caught)))
;; interpreter, JVM: 0
;; wasm P1:          :caught   (JAVA:NEW is undefined, compiled as a call-time error)
```

Where no host is, a `java:` operator compiles to a call-time error (`The function JAVA:NEW is
undefined`), and a `catch` naming a specific class (`NumberFormatException`) takes it, where
nothing of that class was thrown. data.generators 1.1.1's `bigint` retries on
`NumberFormatException` around `(BigInteger. ^bytes ...)`, so `bigint` and `bigdec` loop
forever on both wasm backends (measured 2026-10-30; the interpreter and the JVM print the
oracle's lines, `.kb/clojure-frontend.md`, "Library probes").

Plan: find how the catch test classifies the undefined-function error (`.kb/clojure-frontend.md`,
"Catching", "Host arms where the host is not") and make it a condition no `java.*` class but
`Throwable`/`Exception`/`RuntimeException`-level catches take, like any other refusal by name;
pin it in `clojure-spec.yaml` (the line above, on every backend). Then, separately weighed: a
portable `(BigInteger. bytes)` (two's-complement big-endian octets of a byte array) so `bigint`
and `bigdec` run on wasm.
