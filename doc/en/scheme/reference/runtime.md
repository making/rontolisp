# runtime

`(runtime)`

Returns the elapsed real time in seconds as an inexact number, for timing a computation by subtracting two readings. Only differences between readings are meaningful. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(real? (runtime)) ; => #t
(exact? (runtime)) ; => #f
```
