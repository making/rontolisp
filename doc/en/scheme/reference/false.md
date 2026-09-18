# false

`false`

A variable holding `#f`. It is an ordinary variable, not a literal, so a program may rebind it. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
false ; => #f
(if false 'yes 'no) ; => no
```
