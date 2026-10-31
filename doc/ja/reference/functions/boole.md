# boole

`(boole op integer-1 integer-2)`

定数 `op` が示す2引数のビット演算を `integer-1` と `integer-2` に適用します。`boole-and`、`boole-ior`、`boole-xor`、`boole-eqv`、`boole-nand`、`boole-nor`、`boole-andc1`、`boole-andc2`、`boole-orc1`、`boole-orc2` は対応する `log*` 演算、`boole-1` / `boole-2` は引数そのもの、`boole-c1` / `boole-c2` はその補数、`boole-clr` は `0`、`boole-set` は `-1` を返します。任意精度の整数に対して正確です。それ以外の `op` は `type-error` を通知します。

```lisp
(boole boole-and 12 10) ; => 8
```
