# nil

`nil`

A variable holding the empty list `'()`. Unlike Common Lisp's `nil` it is not false: in Scheme only `#f` is false, so `nil` in a test counts as true. It is an ordinary variable, not a literal. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
nil ; => ()
(eq? nil '()) ; => #t
(if nil 'yes 'no) ; => yes
```
