# values

`(values obj ...)`

引数を多値として返します。受け取るのは `call-with-values`、`let-values`、`define-values` です。`(values)` は値を 1 つも返しません。値が 1 つだけ期待される場所では最初の値が使われます。呼び出しとして書いた `(values 1 2)` はどのバックエンドでもすべての値を返しますが、第一級の手続きとして使った `values`（`(apply values '(1 2))`、変数経由、`eval` の中）は、コンパイルされたバックエンドでは最初の値だけを返します。インタプリタはすべてを返します。

```scheme
(values 1 2) ; => 1, 2
(values 'a) ; => a
```
