# open-output-bytevector

`(open-output-bytevector)`

書き込まれたバイトを蓄えるバイナリ出力ポートを返します。中身は `get-output-bytevector` で取り出します。

```scheme
(let ((p (open-output-bytevector))) (write-u8 1 p) (write-u8 2 p) (get-output-bytevector p)) ; => #u8(1 2)
```
