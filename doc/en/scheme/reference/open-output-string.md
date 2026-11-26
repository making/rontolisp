# open-output-string

`(open-output-string)`

Returns a textual output port that accumulates what is written to it, for `get-output-string`.

```scheme
(let ((p (open-output-string))) (write 'x p) (write "y" p) (get-output-string p)) ; => "x\"y\""
```
