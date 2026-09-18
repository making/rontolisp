# bytevector?

`(bytevector? obj)`

`obj` がバイトベクタなら `#t`、そうでなければ `#f` を返します。小さな整数のベクタはバイトベクタではなく、バイトベクタもベクタではありません（`vector?` は `#f` を返します）。

```scheme
(bytevector? #u8(1 2)) ; => #t
(bytevector? #(1 2)) ; => #f
(vector? #u8(1 2)) ; => #f
```
