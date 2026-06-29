# second third fourth

`(second list)`, `(third list)`, `(fourth list)`

リストの 2 番目、3 番目、4 番目の要素にアクセスする序数アクセサで、それぞれ `cadr`、`caddr`、`cadddr` に相当します。リストがその要素を持つほど長くない場合は `nil` を返します。これは基礎となる `car`/`cdr` の走査が `nil` で底を打つためです。

```lisp
(third '(a b c d)) ; => c
```

```lisp
(fourth '(a b)) ; => nil
```
