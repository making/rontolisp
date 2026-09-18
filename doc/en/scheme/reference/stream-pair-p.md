# stream-pair?

`(stream-pair? obj)`

Returns `#t` if `obj` is a non-empty stream -- a pair whose cdr is a promise -- and `#f` otherwise; an ordinary list is not a stream pair. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-pair? (stream 1)) ; => #t
(stream-pair? '(1 2)) ; => #f
(stream-pair? '()) ; => #f
```
