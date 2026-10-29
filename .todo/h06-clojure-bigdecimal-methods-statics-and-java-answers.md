# Clojure: a decimal answers none of `BigDecimal`'s methods, and a Java `BigDecimal` stays a host object

Difficulty: Medium

```clojure
(println (.setScale 1.555M 2 java.math.RoundingMode/HALF_UP) (.scale 1.50M) (.toPlainString 1E+3M)
         (java.math.BigDecimal/valueOf 1.5) (.add (java.math.BigDecimal. "1") 2M))
;; clj 1.12.6: 1.56M 2 1000 1.5M 3M
;; rontolisp:  No matching method setScale found taking 2 args for class java.math.BigDecimal (and the
;;             constructions and statics are host objects where a host is, refused on wasm)
```

A decimal is `(:C%DECIMAL unscaled scale)` (`.kb/clojure-frontend.md`, "Decimals"): arithmetic,
printing, `=`, hashes, `format`, `with-precision` and data.json are the oracle's, and a decimal
handed to Java crosses as a `java.math.BigDecimal`. Missing:

- the instance methods money code calls, as value-method rows over the `%clojure-bd-` kernels
  (`setScale` with a `RoundingMode`, `scale`, `precision`, `unscaledValue`, `signum`,
  `negate`, `abs`, `add`/`subtract`/`multiply`/`divide` (with a scale or a `MathContext`),
  `compareTo`, `equals`, `hashCode`, `stripTrailingZeros`, `toPlainString`, `toString`,
  `doubleValue`/`longValue`/`intValue`, `toBigInteger`), on every backend;
- the constructions and statics portably (`(BigDecimal. "1.5")`, `(BigDecimal. 10)`,
  `BigDecimal/valueOf`, `ZERO`/`ONE`/`TEN`, the `RoundingMode` constants, `(MathContext. 5)`),
  so they answer decimals on wasm too;
- a `BigDecimal` a Java member answers (the interpreter's `JavaInterop.unmarshal`, the JVM's
  bridge) back as a decimal, like the oracle's one class, without making every `java:` call
  of a program that makes no decimal pay a test.

Measure first which of these the library probes reach (next.jdbc's `NUMERIC` columns, data.json
already passes); the decimal family's strip (`ClojureArms.Family.DECIMAL`) must keep a program
making no decimal byte-identical.
