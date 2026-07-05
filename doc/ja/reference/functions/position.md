# position

`(position item sequence &key test test-not key start end from-end)`

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

`:test-not` は比較の否定を、`:start`/`:end` は走査範囲を指定します(返るインデックスはシーケンス全体の先頭から数えます)。`:from-end` が真のときは最後にマッチした位置を返します。

```lisp
(position #\, "a,b,c" :start 2) ; => 3
```

```lisp
(position 2 '(1 2 3 2 4) :from-end t) ; => 3
```
