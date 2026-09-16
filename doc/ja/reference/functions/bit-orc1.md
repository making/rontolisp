# bit-orc1

`(bit-orc1 bit-array1 bit-array2 &optional result-bit-array)`

同じ次元の 2 つのビット配列の要素ごとの第一引数を補数化した論理和（not-a または b）をビットベクタとして返します。両入力はビット配列でなければなりません（`#*` リテラル、または `:element-type 'bit` の [`make-array`](make-array.md) 結果。ランクは不問）。次元が等しくなければなりません。そうでなければエラーを通知します。

`result-bit-array` を省略するか `nil` の場合、新しいビット配列が作成されます。`t` の場合、`bit-array1` が破壊的に再利用されます。それ以外の場合は同じ次元のビット配列でなければならず、書き込まれて返されます。何がビット配列かは [`bit-vector-p`](bit-vector-p.md) を参照してください。

```lisp
(bit-orc1 #*0110 #*1100) ; => #*1101
```
