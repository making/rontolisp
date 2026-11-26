# write-string

`(write-string string [port [start [end]]])`

Writes the characters of `string` (without quotes) to the current output port. With `port`, it writes there instead; `start` and `end` select the characters from index `start` up to, not including, `end`.

```scheme
(write-string "hello")
(newline)
```

```
hello
```

```scheme
(let ((p (open-output-string))) (write-string "hello" p 1 3) (get-output-string p)) ; => "el"
```
