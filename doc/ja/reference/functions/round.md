# round

`(round number)`

`number` を最も近い整数に丸めます。丸めには銀行家の丸め (banker's rounding) を使い、2 つの整数のちょうど中間にある値は偶数側に丸められます。rontolisp では単一の引数を取り、単一の整数値を返します (完全な Common Lisp のような省略可能な除数や 2 番目の剰余値はありません)。

```lisp
(round 3.5) ; => 4
```

```lisp
(round 2.5) ; => 2
```
