# stream-for-each

`(stream-for-each proc stream)`

Calls `proc` on each element of a finite `stream` in order, for its effect. Only one stream is accepted. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-for-each (lambda (x) (display x) (newline)) (stream 1 2 3))
```

```
1
2
3
```
