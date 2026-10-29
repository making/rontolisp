# Clojure: `N` and `M` numbers as their own kinds (`1.5M` prints `3/2`)

Difficulty: High

Integers are Lisp integers and decimals Lisp rationals (`doc/en/clojure/deviations.md`,
the `N`/`M` literal rule):

```clojure
(println (+ 1N 2) 1.5M (bigdec "1.5") (*' 9223372036854775807 2))
;; clj, cljw 1.15.4: 3N 1.5M 1.5M 18446744073709551614N
;; rontolisp:        3 3/2 3/2 18446744073709551614
```

A decimal printing as a ratio leaks into user output, JSON (data.json writes it), and `=`
(`(= 1.5M 3/2)`). cljw keeps a distinct `BigInt` and `BigDecimal`. Add a decimal kind
(unscaled integer + scale, with the oracle's contagion and `*math-context*`) and a BigInt
flag contagious through `+ - *`, on all four backends. Weigh it against the blast radius per
CLAUDE.md before starting: the `.kb` premises behind the rule are in
`.kb/clojure-frontend.md` (lowering table, `bigint`/`bigdec` row).
