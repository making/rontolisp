# bytevector-append

`(bytevector-append bytevector ...)`

Returns a new bytevector holding the elements of the arguments in order.

```scheme
(bytevector-append #u8(1) #u8() #u8(2 3)) ; => #u8(1 2 3)
(bytevector-append) ; => #u8()
```
