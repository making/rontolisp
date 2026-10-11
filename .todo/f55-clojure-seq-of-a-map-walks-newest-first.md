# f55. Clojure: `seq` of a map walks it newest-first

Difficulty: Medium

A map or set prints in insertion order on all four backends (the oracle's array-map order up
to eight entries), but its seq view walks the other way: `%clojure-strict-seq`'s table and set
arms (`clojure.lisp`) cons each entry onto an accumulator in `maphash` order. Measured
2026-10-11 against clj 1.12.6, identical on the interpreter, the JVM class and wasm P1:

| form | oracle | here |
|---|---|---|
| `(seq {:a 1 :b 2 :c 3})` | `([:a 1] [:b 2] [:c 3])` | `([:c 3] [:b 2] [:a 1])` |
| `(keys {:a 1 :b 2 :c 3})` | `(:a :b :c)` | `(:c :b :a)` |
| `(map->R {:x 1 :y 2 :q 9 :b 8 :a 7})` | `#user.R{:x 1, :y 2, :q 9, :b 8, :a 7}` | `... :a 7, :b 8, :q 9}` |

`println`, `reduce-kv` and an `assoc`-built record already walk in the oracle's order.
`.kb/clojure-frontend.md` records the split ("Deviations", printing; `clojure.pprint`'s
`kernel/members` note).

## Plan

1. Build the two arms in walk order and run the Clojure suites (clojure-spec, `Clojure*Test`,
   the e2e Clojure tests): list every expectation that moves and check each against the
   oracle. A set's order stays unreachable (the oracle's hash set walks hash order), so its
   seq follows its printer.
2. Pin `seq`, `keys`, `vals`, `first` of a multi-entry map and `map->R` on all four backends.
