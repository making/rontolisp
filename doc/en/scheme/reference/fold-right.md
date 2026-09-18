# fold-right

`(fold-right f initial list1 list2 ...)`

Combines the elements of the lists from the right: `(f e1 (f e2 (f e3 initial)))`. With several lists `f` takes one element of each and then the result so far, and the fold stops at the end of the shortest list. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(fold-right cons '() '(1 2 3)) ; => (1 2 3)
(fold-right - 0 '(1 2 3)) ; => 2
(fold-right list '() '(a b) '(1 2)) ; => (a 1 (b 2 ()))
```
