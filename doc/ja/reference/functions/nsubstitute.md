# nsubstitute

`(nsubstitute new old list &key test key)`

`substitute` の破壊的対応版です。`list` の car をその場で書き換え、`old` に一致する要素をすべて `new` に置き換えます。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます。リスト構造が再利用されるため、変更は元の変数を通して見えます。

```lisp
(nsubstitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(nsubstitute 'x 2 (list '(1) '(2)) :key #'car) ; => ((1) X)
```
