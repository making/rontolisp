# complement

`(complement function)`

`function` と逆の答えを返す 1 引数の述語を返します。`function` が `nil` を返すところで `t` を返し、その逆も同様です。簡易版: Common Lisp と異なり返される関数はちょうど 1 引数を取り、`complement` はインライン展開されるため `#'complement` は使えません。

```lisp
(funcall (complement #'evenp) 3) ; => t
```

```lisp
(remove-if (complement #'oddp) '(1 2 3 4 5)) ; => (1 3 5)
```
