# open-output-string

`(open-output-string)`

書き込まれたものを蓄えるテキスト出力ポートを返します。中身は `get-output-string` で取り出します。

```scheme
(let ((p (open-output-string))) (write 'x p) (write "y" p) (get-output-string p)) ; => "x\"y\""
```
