# display

`(display obj)`

Writes `obj` to the current output port for a human reader: strings without quotes and characters as themselves, everything else as `write` shows it. A circular list or vector is written with datum labels (`#0=(a b . #0#)`); structure shared without a cycle is written out each time. There is no port argument.

```scheme
(display "a \"quoted\" word")
(newline)
(display '(1 "two" #\3))
(newline)
```

```
a "quoted" word
(1 two 3)
```
