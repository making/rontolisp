# map

`(map result-type function &rest sequences)`

与えられた `sequences`(それぞれリスト、ベクタまたは文字列)の要素に順に `function` を適用し、要求された型の結果を構築します。複数のシーケンスが与えられた場合、関数は各シーケンスから 1 つずつ要素を受け取り、最も短いシーケンスの末尾で反復を終了します。`'list` と `'cons` は結果をリストに集め、`'string` は文字の結果から文字列を構築し、`'vector` は一般のベクタを構築し、`nil` は副作用のためだけに関数を呼び出して nil を返します。要素型を指定したベクタの `result-type` は [`coerce`](coerce.md) と同じものを構築します。`'(vector (unsigned-byte 8))` は特殊化ベクタ、`'(vector single-float)` はパックされた浮動小数点配列、`'(vector character)` は文字列です。引数なしの [`deftype`](../macros/deftype.md) の名前は、その展開先と同じ意味です。計算された `result-type` は `coerce` と同じく実行時にディスパッチします。

```lisp
(map 'list #'+ '(1 2 3) '(10 20 30)) ; => (11 22 33)
```

```lisp
(map 'string #'char-upcase "abc") ; => "ABC"
(array-element-type (map '(vector (unsigned-byte 8)) #'char-code "ab")) ; => (UNSIGNED-BYTE 8)
```
