# last-pair

`(last-pair list)`

Returns the last pair of a non-empty list, the one whose cdr ends the list. For an improper list that is the pair holding the final dotted tail. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(last-pair '(1 2 3)) ; => (3)
(last-pair '(1 2 . 3)) ; => (2 . 3)
```
