# fold-left

`(fold-left f initial list)`

Combines the elements of `list` from the left: `(f (f (f initial e1) e2) e3)`. Only one list is accepted; MIT Scheme's form taking several lists is refused by arity. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(fold-left cons '() '(1 2 3)) ; => (((() . 1) . 2) . 3)
(fold-left - 0 '(1 2 3)) ; => -6
```
