# boole

`(boole op integer-1 integer-2)`

Applies the two-operand bit operation named by the constant `op` to `integer-1` and `integer-2`: `boole-and`, `boole-ior`, `boole-xor`, `boole-eqv`, `boole-nand`, `boole-nor`, `boole-andc1`, `boole-andc2`, `boole-orc1` and `boole-orc2` are the matching `log*` operation; `boole-1` / `boole-2` answer an operand, `boole-c1` / `boole-c2` its complement, `boole-clr` `0` and `boole-set` `-1`. Exact for integers of any size. Any other `op` signals a `type-error`.

```lisp
(boole boole-and 12 10) ; => 8
```
