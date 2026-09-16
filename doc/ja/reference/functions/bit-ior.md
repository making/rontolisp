# bit-ior

`(bit-ior bit-array1 bit-array2 &optional result-bit-array)`

同じ次元の 2 つのビット配列の要素ごとの包括的論理和をビットベクタとして返します。両入力はビット配列でなければなりません（`#*` リテラル、または `:element-type 'bit` の [`make-array`](make-array.md) 結果。ランクは不問）。次元が等しくなければなりません。そうでなければエラーを通知します。

`result-bit-array` を省略するか `nil` の場合、新しいビット配列が作成されます。`t` の場合、`bit-array1` が破壊的に再利用されます。それ以外の場合は同じ次元のビット配列でなければならず、書き込まれて返されます。何がビット配列かは [`bit-vector-p`](bit-vector-p.md) を参照してください。

```lisp
(bit-ior #*0110 #*1100) ; => #(1 1 1 0)
```
