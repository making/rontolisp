# get-output-bytevector

`(get-output-bytevector port)`

`open-output-bytevector` で作ったポート `port` にそれまで書かれたバイトを、新しいバイトベクタで返します。

```scheme
(let ((p (open-output-bytevector))) (write-bytevector #u8(5 6) p) (get-output-bytevector p)) ; => #u8(5 6)
```
