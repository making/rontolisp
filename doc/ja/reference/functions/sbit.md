# sbit

`(sbit bit-array index)`

ビットベクタ(`#*` リテラル、または `:element-type 'bit` の `make-array` 結果。0/1 を保持する汎用ベクタとして表現)の `index` 位置のビットを読みます。`(setf (sbit bit-array index) bit)` で書き込みます。

```lisp
(sbit #*0110 1) ; => 1
```
