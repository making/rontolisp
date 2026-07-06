# streamp

`(streamp object)`

`object` がストリームであれば `t` を、そうでなければ `nil` を返します。ストリームはすべてのバックエンドで不透明な整数ハンドルなので、これは `integerp` に相当する軽量な判定です。`check-type`/`typecase` が使う `stream` 型指定子も同じ判定に基づいています。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(with-output-to-string (s) (princ (streamp s) s)) ; => "t"
```
