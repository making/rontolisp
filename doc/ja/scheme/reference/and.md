# and

`(and test...)`

test を左から右へ評価し、最初の `#f` で止まって `#f` を返します。そうでなければ最後の test の値を返します。`(and)` は `#t` です。

```scheme
(and 1 2 'last) ; => last
(and 1 #f 3) ; => #f
(and) ; => #t
```
