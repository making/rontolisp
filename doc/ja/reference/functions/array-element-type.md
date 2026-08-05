# array-element-type

`(array-element-type array)`

配列の要素型を返します。パックド浮動小数点配列は `double-float` または `single-float` を、パックド符号なし整数ベクタ (ランク 1 の配列に対するリテラルの `:element-type '(unsigned-byte 8|16|32)` 付き [`make-array`](make-array.md)、またはその要素型を結果型に指定した [`concatenate`](concatenate.md)) は実際の `(unsigned-byte n)` 指定子を返します。それ以外の一般配列では結果は常にシンボル `t` です (他の要素型は受け付けられますが追跡されません)。cl-utilities の `copy-array` のようなポータブルなコードとの互換性のために提供されています。

```lisp
(array-element-type (make-array 3)) ; => T
(array-element-type (make-array 3 :element-type '(unsigned-byte 8))) ; => (UNSIGNED-BYTE 8)
```
