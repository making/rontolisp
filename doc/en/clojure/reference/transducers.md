# Transducers

A transducer is what the oracle's is: a function from a reducing function to a reducing
function. `comp` composes transducers left to right (the first sees each input first),
and a program's own `(fn [rf] (fn ([] ...) ([acc] ...) ([acc x] ...)))` runs beside the
core ones. `into`, `transduce`, `sequence` and `eduction` consume them; `reduce`,
`reduce-kv` and `transduce` stop at a `reduced` answer.

The one-argument arity of these seq verbs is their transducer (zero arguments for
`dedupe` and `distinct`), in call position and as a value: `map`, `filter`, `remove`,
`keep`, `keep-indexed`, `map-indexed`, `take`, `drop`, `take-while`, `drop-while`,
`take-nth`, `mapcat`, `partition-all`, `partition-by`, `interpose`, `dedupe`,
`distinct`, `replace`. `cat` is a transducer itself.

| Name | Example | Result |
|---|---|---|
| `transduce` | `(transduce (map inc) + [1 2 3])` | `9` |
| `eduction` | `(eduction (filter odd?) (range 6))` | `(1 3 5)` |
| `sequence` | `(sequence (map inc) [1 2])` | `(2 3)` |
| `completing` | `(transduce (take 2) (completing + str) [1 2 3])` | `"3"` |
| `reduced` | `(reduce (fn [a x] (reduced x)) [1 2])` | `2` |
| `reduced?` | `(reduced? (reduced 1))` | `true` |
| `unreduced` | `(unreduced (reduced 1))` | `1` |
| `ensure-reduced` | `(reduced? (ensure-reduced 1))` | `true` |
| `cat` | `(into [] cat [[1] [2 3]])` | `[1 2 3]` |

```clojure
(println (into [] (comp (map inc) (filter odd?)) [1 2 3 4 5])) ; [3 5]
(println (into [] (comp (take 2) (partition-all 3)) (range 10))) ; [[0 1]]
(defn tens [rf] (fn ([] (rf)) ([acc] (rf acc)) ([acc x] (rf acc (* 10 x)))))
(println (into [] tens [1 2])) ; [10 20]
```
