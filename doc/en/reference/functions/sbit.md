# sbit

`(sbit bit-array index)`

Reads the bit at `index` of a bit vector (a `#*` literal or a `make-array` result with `:element-type 'bit`, represented as the general vector holding 0/1). `(setf (sbit bit-array index) bit)` writes it.

```lisp
(sbit #*0110 1) ; => 1
```
