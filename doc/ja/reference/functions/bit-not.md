# bit-not

`(bit-not bit-array &optional result-bit-array)`

1 つのビット配列の要素ごとの否定をビットベクタとして返します。入力はビット配列でなければなりません（`#*` リテラル、または `:element-type 'bit` の [`make-array`](make-array.md) 結果。ランクは不問）。そうでなければエラーを通知します。

`result-bit-array` を省略するか `nil` の場合、新しいビット配列が作成されます。`t` の場合、`bit-array` が破壊的に再利用されます。それ以外の場合は同じ次元のビット配列でなければならず、書き込まれて返されます。何がビット配列かは [`bit-vector-p`](bit-vector-p.md) を参照してください。

```lisp
(bit-not #*0110) ; => #*1001
```
