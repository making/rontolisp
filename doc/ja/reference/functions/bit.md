# bit

`(bit bit-array index)`

ビット配列(`#*` リテラル、または `:element-type 'bit` の `make-array` 結果。0/1 を保持する汎用ベクタとして表現)の `index` 位置のビットを読みます。`(setf (bit bit-array index) bit)` で書き込みます。[`sbit`](sbit.md) の非単純版の対で、ここでは両者の挙動は同じです。

```lisp
(bit #*0110 1) ; => 1
```
