# reduce

`(reduce f initial list)`

Combines the elements of `list` from the left with the two-argument procedure `f`, as SRFI-1 and MIT Scheme do: each element is the first argument and the result so far the second, `(f e3 (f e2 e1))`. An empty list answers `initial`, and a one-element list answers its element without calling `f`. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(reduce + 0 '(1 2 3 4)) ; => 10
(reduce max 0 '(3 9 2)) ; => 9
(reduce + 0 '()) ; => 0
(reduce - 0 '(1 2 3 4)) ; => 2
```
