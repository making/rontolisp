# write

`(write obj [port])`

Writes `obj` to the current output port in its external representation: strings in quotes with escapes, characters in `#\` notation. A circular list or vector is written with datum labels; structure shared without a cycle is written out each time. `'x` is written as `(quote x)` and the unspecified value as `#!unspecific`. A symbol whose name would not read back as an identifier is written between vertical lines (`|a b|`, `||`, `|1|`, `|a\x09;b|` for a tab). With `port`, it writes there instead; `port` must be an open textual output port.

```scheme
(write '(1 "two" #\3))
(newline)
(define c (list 'a 'b))
(set-cdr! (cdr c) c)
(write c)
(newline)
```

```
(1 "two" #\3)
#0=(a b . #0#)
```

```scheme
(let ((p (open-output-string))) (write "two" p) (get-output-string p)) ; => "\"two\""
```
