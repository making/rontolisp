# position

`(position item sequence &key test key)`

`sequence` の要素のうち `item` に一致する最初の要素の 0 始まりのインデックスを返します。一致する要素がなければ `nil` を返します。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます。シーケンスにはリストまたは文字列を渡せます。文字列の要素は文字です。要素を返す `find` とは異なり、`position` は整数の位置を返します。

```lisp
(position 3 '(1 2 3)) ; => 2
```

```lisp
(position #\space "hello world") ; => 5
```

```lisp
(position "b" '("a" "b" "c") :test #'string=) ; => 1
```
