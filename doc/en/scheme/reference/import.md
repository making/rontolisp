# import

`(import import-set...)`

Program syntax, exported by no library: names the libraries whose bindings a program uses. The accepted libraries are `(scheme base)`, `(scheme write)`, `(scheme read)`, `(scheme inexact)`, `(scheme cxr)`, `(scheme lazy)`, `(scheme process-context)`, `(scheme eval)` and `(scheme repl)`, each optionally wrapped in `only`, `except`, `prefix` or `rename`; any other library is refused by name. In a file every `import` must come before everything else, and the file then sees only what it imported; a file with no `import` sees all nine libraries plus the *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)-compatibility and R5RS names. At the REPL an `import` may be typed at any time and only adds names.

```scheme
(import (scheme base) (scheme write))
(import (rename (only (scheme base) car) (car first)))
(display (first '(9 8)))
(newline)
```

```
9
```
