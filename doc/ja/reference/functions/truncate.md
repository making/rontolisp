# truncate

`(truncate number)`

`number` を 0 方向に丸めて整数にし、小数部を切り捨てます。rontolisp では単一の引数を取り、単一の整数値を返します (完全な Common Lisp のような省略可能な除数や 2 番目の剰余値はありません)。

```lisp
(truncate 3.7) ; => 3
```

```lisp
(truncate -3.7) ; => -3
```
