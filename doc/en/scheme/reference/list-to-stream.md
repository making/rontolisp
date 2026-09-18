# list->stream

`(list->stream list)`

Returns a finite stream of the elements of `list`, in order. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (list->stream '(a b c))) ; => (a b c)
(stream-pair? (list->stream '(1))) ; => #t
```
