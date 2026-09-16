# sbit

`(sbit bit-array index)`

Reads the bit at `index` of a bit vector (a `#*` literal or a `make-array` result with `:element-type 'bit`, the general vector holding 0/1 and stamped with the remembered element type `bit`). `(setf (sbit bit-array index) bit)` writes it.

```lisp
(sbit #*0110 1) ; => 1
```
