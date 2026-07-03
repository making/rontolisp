# vector

`(vector &rest elements)`

指定された要素を左から右に評価して格納した、新しいランク 1 の配列を作成して返します。`(vector)` は空のベクタ `#()` を返します。要素数を次元として [`make-array`](make-array.md) を呼び、続けて [`aref`](aref.md) 形式で格納するのと等価ですが、これを 1 ステップで行います。`make-array` や `aref` と同様に、`vector` は第一級の関数値ではありません。`#'vector` は利用できないため、直接呼び出してください。

```lisp
(vector 1 2 3) ; => #(1 2 3)
(vector) ; => #()
(aref (vector 10 20 30) 2) ; => 30
```
