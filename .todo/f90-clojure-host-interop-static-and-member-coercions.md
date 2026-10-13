# f90. Clojure host interop: static/member coercions against the oracle

Difficulty: High

Found while scoping f81's pinning tests (2026-10-13): the interpreter's and the
JVM's `java:` overload resolution coerces unlike the oracle's reflector, in both
directions. Measured against clj 1.12.6:

- Looser: `(Double/valueOf 5)` is `5.0` (oracle: no-matching-method
  `IllegalArgumentException`), `(Math/min 1 1.0)` is `1.0` and `(Math/min \a 1)`
  is `1` (oracle: the same refusal), `(Integer/toHexString 4294967295)` wraps to
  `"ffffffff"` (oracle: `ArithmeticException`, integer overflow).
- Stricter: `(Integer/toHexString 1.5)` is `No matching method` (oracle: `"1"`),
  `(Integer/parseInt 5 16)` is `No matching method` (oracle: `ClassCastException`
  to `String`).

## What decides the design

- f81 lowers the named statics portably on wasm only, so its four-backend pins cover
  the host∩oracle lines and the wasm legs cover the rest
  (`ClojureWasmInteropRefusalTest`); the host keeps its own coercions. Unifying them
  is the interop's overload-resolution design, not five special cases.
- Each coercion needs the oracle's table first (widening, narrowing, boxing,
  null): which pairs answer, which refuse, and in whose words.

## Plan

1. The oracle's coercion table for statics and member calls, measured one by one.
2. The resolution change, with the blast-radius review (every `java:` site on both
   host backends changes path).
3. The affected pins re-run on all four backends.
