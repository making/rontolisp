# bytevector-append

`(bytevector-append bytevector ...)`

引数の要素を順に並べた新しいバイトベクタを返します。

```scheme
(bytevector-append #u8(1) #u8() #u8(2 3)) ; => #u8(1 2 3)
(bytevector-append) ; => #u8()
```
