# reduce

`(reduce f initial list)`

Combines the elements of `list` with the two-argument procedure `f`. An empty list answers `initial`, and a one-element list answers its element without calling `f`. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(reduce + 0 '(1 2 3 4)) ; => 10
(reduce max 0 '(3 9 2)) ; => 9
(reduce + 0 '()) ; => 0
```
