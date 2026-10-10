# f31. Clojure: `.equals` on a Clojure value is `=`, not the oracle's Java `equals`

Difficulty: Medium

`(.equals x y)` on a Clojure value lowers to `=` (`ClojureValueMethodLowering.updateRows`,
the `equiv`/`equals` row). The oracle's `equals` is the class's Java one, which differs from
`=` where a record meets a map (`APersistentMap.equals` ignores the record type) and where
numbers of two classes meet (`Long.equals` of a `BigInt` is false). Measured 2026-10-10
against clj 1.12.6, on the interpreter:

| program | oracle | here |
|---|---|---|
| `(defrecord Q [a])` then `(.equals (->Q 1) {:a 1})` | `true` | `false` |
| `(.equals {:a 1} (->Q 1))` | `true` | `false` |
| `(.equals 1 1N)` | `false` | `true` |

Only the interpreter was measured; the row is lowered for every backend. Java itself already
sees the Map rule: a record's view and face compare by `AbstractMap.equals`.

## Plan

1. Give `equals` its own row: `=` minus the type test of a record against a map (a map
   `equals` a record of its entries), and a number `equals` only a number of its class in the
   oracle's sense (`Long`/`BigInt`/`Double`/`Ratio`/`BigDecimal`).
2. Pin on all four backends with the rows above; check `equiv` keeps `=`.
