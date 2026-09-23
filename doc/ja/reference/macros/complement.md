# complement

`(complement function)`

`function` と逆の答えを返す述語を返します。`function` が `nil` を返すところで `t` を返し、その逆も同様です。返される述語は任意個数の引数を取ります。3 引数までは `funcall` のディスパッチ、4 引数以降は `apply` 経由のため、コンパイル済みバックエンドでは `complement` を書いたプログラムに apply ランタイムが付きます。1 引数の述語だけでなく等価性の指定子 (`:test` / `:test-not`) としても使えます。簡易版: `complement` はインライン展開されるため `#'complement` は使えません。

```lisp
(funcall (complement #'evenp) 3) ; => T
```

```lisp
(remove-if (complement #'oddp) '(1 2 3 4 5)) ; => (1 3 5)
```

```lisp
(remove 3 (list 1 2 3 4) :test-not (complement #'eql)) ; => (1 2 4)
```
