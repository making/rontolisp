# rem

`(rem number divisor)`

`number` を `divisor` で切り捨て除算 (truncated division) したときの剰余を返します。そのため結果は常に被除数の符号を取ります。これは `truncate` の対になる関数です。結果を除数の符号に合わせたい場合は代わりに `mod` を使用してください。

```lisp
(rem 13 4) ; => 1
```

```lisp
(rem -13 4) ; => -1
```

```lisp
(rem 7/2 3) ; => 1/2
```
