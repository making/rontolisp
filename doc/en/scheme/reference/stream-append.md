# stream-append

`(stream-append stream ...)`

Returns the stream of the elements of each argument stream in turn, computed lazily. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (stream-append (stream 1 2) (stream 3) (stream 4 5))) ; => (1 2 3 4 5)
(stream-append) ; => ()
```
