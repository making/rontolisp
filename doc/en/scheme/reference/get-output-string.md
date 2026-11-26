# get-output-string

`(get-output-string port)`

Returns a string of everything written so far to `port`, which must be a port made by `open-output-string`. The port keeps its contents, so a second call answers them again with anything written since.

```scheme
(let ((p (open-output-string))) (display "ab" p) (get-output-string p)) ; => "ab"
```
