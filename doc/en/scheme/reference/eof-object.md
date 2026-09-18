# eof-object

`(eof-object)`

Returns the end-of-file object, the value the reading procedures return at the end of input. There is one such object; it prints as `#<eof>` and has no read syntax.

```scheme
(write (eof-object))
(newline)
(write (eof-object? (eof-object)))
(newline)
```

```
#<eof>
#t
```
