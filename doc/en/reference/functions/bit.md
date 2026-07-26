# bit

`(bit bit-array index)`

Reads the bit at `index` of a bit array (a `#*` literal or a `make-array` result with `:element-type 'bit`, represented as the general vector holding 0/1). `(setf (bit bit-array index) bit)` writes it. The non-simple twin of [`sbit`](sbit.md); both behave identically here.

```lisp
(bit #*0110 1) ; => 1
```
