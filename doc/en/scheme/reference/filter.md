# filter

`(filter pred list)`

Returns a new list of the elements of `list` for which `pred` returns a true value, in their original order. Any value other than `#f` counts as true. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(filter odd? '(1 2 3 4 5)) ; => (1 3 5)
(filter (lambda (x) (> x 10)) '(1 2 3)) ; => ()
```
