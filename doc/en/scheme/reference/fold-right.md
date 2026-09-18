# fold-right

`(fold-right f initial list)`

Combines the elements of `list` from the right: `(f e1 (f e2 (f e3 initial)))`. Only one list is accepted; MIT Scheme's form taking several lists is refused by arity. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(fold-right cons '() '(1 2 3)) ; => (1 2 3)
(fold-right - 0 '(1 2 3)) ; => 2
```
