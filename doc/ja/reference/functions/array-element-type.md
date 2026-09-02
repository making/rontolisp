# array-element-type

`(array-element-type array)`

配列の要素型を返します。返るのは**アップグレード後**の型、つまりプログラムが書いた綴りではなく配列が実際に保持する型です。パックド浮動小数点配列は `double-float` または `single-float` を、パックド符号なし整数ベクタ (ランク 1 の配列に対する`:element-type '(unsigned-byte 8|16|32)` 付き [`make-array`](make-array.md)、またはその要素型を結果型に指定した [`concatenate`](concatenate.md)) は実際の `(unsigned-byte n)` 指定子を返します。文字列 (文字のベクタ) は `character` を返します。

一般配列は、専用の表現が存在しない場合でも要求された要素型を**記憶**します。ランク 2 以上の `character` と 3 つの `(unsigned-byte n)` 幅、およびそれらと `:fill-pointer`/`:adjustable` の組み合わせがそれにあたります。退化するのは表現であって宣言された型ではなく、値を与えられなかった要素は `nil` ではなくその型のゼロになります。アップグレード先を持たない要素型 (`fixnum`、`integer`、`bit`、クラス) は `t` にアップグレードされます。変位した (displaced) ビューは自身の記憶域を持たないため、変位チェーン全体をたどった先の**変位先**の要素型を返します。[`make-array`](make-array.md) は両者が型として等価であることを要求するので、これはビュー自身が宣言した `:element-type` でもあります。ビューが変位でなくなっても答えは変わりません。[`adjust-array`](adjust-array.md) と、ビューの範囲を超える [`vector-push-extend`](vector-push-extend.md) はどちらもビューの変位を解除しますが、その答えは捨てられずに記録されます。

```lisp
(array-element-type "abc") ; => CHARACTER
(array-element-type (make-array 3)) ; => T
(array-element-type (make-array 3 :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
(array-element-type (make-array '(2 2) :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
(array-element-type (make-array 4 :element-type 'double-float :fill-pointer 0)) ; => DOUBLE-FLOAT
(array-element-type (make-array 3 :element-type 'fixnum)) ; => T
```
