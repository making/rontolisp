# list-index

`(list-index pred list)`

Returns the zero-based position of the first element of `list` for which `pred` returns a true value, or `#f` when there is none. Only one list is accepted. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(list-index even? '(1 3 4 5)) ; => 2
(list-index even? '(1 3 5)) ; => #f
```
