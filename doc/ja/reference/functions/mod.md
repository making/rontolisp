# mod

`(mod number divisor)`

`number` を `divisor` で割った余りを、床関数による除算（floored division）で返します。そのため結果は常に除数の符号を取ります。これは `floor` と対をなします。結果を被除数の符号に従わせたい場合は代わりに `rem` を使用してください。

```lisp
(mod 10 3) ; => 1
```

```lisp
(mod -13 4) ; => 3
```

```lisp
(mod 7/2 3) ; => 1/2
```
