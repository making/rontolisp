# fold-left

`(fold-left f initial list1 list2 ...)`

Combines the elements of the lists from the left: `(f (f (f initial e1) e2) e3)`. With several lists `f` takes the result so far and then one element of each, and the fold stops at the end of the shortest list. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(fold-left cons '() '(1 2 3)) ; => (((() . 1) . 2) . 3)
(fold-left - 0 '(1 2 3)) ; => -6
(fold-left list '() '(a b) '(1 2)) ; => ((() a 1) b 2)
```
