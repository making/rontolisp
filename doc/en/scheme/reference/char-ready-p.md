# char-ready?

`(char-ready? [port])`

Returns `#t` if a character can be read from the current input port without blocking. It always returns `#t`: with data waiting and at end of input that is the R7RS answer, but on a terminal where nothing has been typed yet it is also `#t`, and the next read then waits. With `port`, it asks about that port, which must be an open textual input port; the answer is `#t` there too.

```scheme
(char-ready?) ; => #t
```
