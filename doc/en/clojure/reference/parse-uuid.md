# parse-uuid

`(parse-uuid s)`

`clojure.core/parse-uuid`: the UUID the string `s` spells, or `nil` when it spells none. The
read is the oracle's `UUID.fromString`: at most 36 characters, five groups of hex digits
between exactly four dashes, each group masked to its width, so a short group reads too
(`1-1-1-1-1`) and an overlong one keeps its low digits. `nil` is the oracle's
`NullPointerException` and any other non-string its `ClassCastException`. Runs on every
backend; as a value a one-argument function.

```clojure
(prn (parse-uuid "550E8400-E29B-41D4-A716-446655440000"))
(prn (map parse-uuid ["1-1-1-1-1" "123456789-1-1-1-1" "1-1-1-1" "x"]))
```

```
#uuid "550e8400-e29b-41d4-a716-446655440000"
(#uuid "00000001-0001-0001-0001-000000000001" #uuid "23456789-0001-0001-0001-000000000001" nil nil)
```
