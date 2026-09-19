# include

`(include "file"...)`

Reads each file and puts its contents where the `include` stands, as a `begin`: at the top level its definitions are the program's own, at the head of a body they are internal definitions. A relative file name is relative to the directory of the file the `include` is written in, and at the REPL to the working directory. The files are read when the program is, so a compiled program carries their contents and reads nothing at run time. A file including itself is refused, and so is an `include` a macro expands into.

```scheme
; file: greet.scm
(define (greet name) (string-append "hello, " name))
```

```scheme
(include "greet.scm")
(display (greet "world"))
(newline)
```

```
hello, world
```
