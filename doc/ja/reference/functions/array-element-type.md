# array-element-type

`(array-element-type array)`

配列の要素型を返します。返るのは**アップグレード後**の型、つまりプログラムが書いた綴りではなく配列が実際に保持する型です。パックド浮動小数点配列は `double-float` または `single-float` を、パックド符号なし整数ベクタ (ランク 1 の配列に対する`:element-type '(unsigned-byte 8|16|32)` 付き [`make-array`](make-array.md)、またはその要素型を結果型に指定した [`concatenate`](concatenate.md)) は実際の `(unsigned-byte n)` 指定子を返します。文字列 (文字のベクタ) は `character` を返します。

一般配列は、専用の表現が存在しない場合でも要求された要素型を**記憶**します。ランク 2 以上の `character` と 3 つの `(unsigned-byte n)` 幅、およびそれらと `:fill-pointer`/`:adjustable` の組み合わせがそれにあたります。退化するのは表現であって宣言された型ではなく、値を与えられなかった要素は `nil` ではなくその型のゼロになります。アップグレード先を持たない要素型 (`fixnum`、`integer`、`bit`、クラス) は `t` にアップグレードされ、変位した (displaced) ビューも `t` を返します。

```lisp
(array-element-type "abc") ; => CHARACTER
(array-element-type (make-array 3)) ; => T
(array-element-type (make-array 3 :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
(array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
(array-element-type (make-array 4 :element-type 'double-float :fill-pointer 0)) ; => DOUBLE-FLOAT
(array-element-type (make-array 3 :element-type 'fixnum)) ; => T
```
