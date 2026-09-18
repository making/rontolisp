# case

`(case key ((datum...) expression...)... [(else expression...)])`

`key` を評価し、データのいずれかがそれと `eqv?` である最初の節を選び、その最後の式の値を返します。`else` 節は何にでも当てはまります。節は `((datum...) => receiver)` や `(else => receiver)` の形でもよく、その場合は key を引数に `receiver` を呼びます。どの節も選ばれなければ値は未規定値です。

```scheme
(case (* 2 3) ((2 3 5 7) 'prime) ((1 4 6 8 9) 'composite) (else 'other)) ; => composite
(case #\a ((#\a #\e) 'vowel) (else 'consonant)) ; => vowel
(case 'x ((a) 1) (else => (lambda (v) (list v 'unknown)))) ; => (x unknown)
```
