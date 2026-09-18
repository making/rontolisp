# true

`true`

A variable holding `#t`. It is an ordinary variable, not a literal, so a program may rebind it. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
true ; => #t
(eq? true #t) ; => #t
```
