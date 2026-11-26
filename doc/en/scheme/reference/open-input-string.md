# open-input-string

`(open-input-string string)`

Returns a textual input port that reads the characters of `string`. Each port keeps its own read position, pushed-back character and `#!fold-case` state, so several ports can be read in turn.

```scheme
(read (open-input-string "(a . b) c")) ; => (a . b)
```
