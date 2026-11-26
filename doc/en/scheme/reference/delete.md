# delete

`(delete x list)`

Returns a new list without the elements of `list` that are `equal?` to `x`; `list` itself is not modified. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(delete 3 '(1 3 2 3)) ; => (1 2)
(delete "b" '("a" "b" "c")) ; => ("a" "c")
```
