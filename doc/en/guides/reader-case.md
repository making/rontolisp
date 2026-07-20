# Reader Case (Upcasing)

rontolisp's reader upcases symbols the way standard Common Lisp's does: every
unescaped character of a symbol token is converted to upper case while
reading (the `:upcase` readtable case), so `foo`, `Foo` and `FOO` in source
all name the same symbol `FOO`. Escaped characters keep their case:
`|mixed Case|` and `\(` read verbatim.

The upcased name is the canonical one -- there is no fold back to a lowercase
spelling. Standard names, `t`/`nil`, lambda-list markers and built-in package
members are all upper case like everything else:

- standard `cl` names (`defun` and `DEFUN` both read as `DEFUN`, `list` as
  `LIST`), including type-specifier and condition-type names (`HASH-TABLE`,
  `TYPE-ERROR`),
- `T` / `NIL` / `PI` and the other read-time constants,
- lambda-list markers (`&OPTIONAL`, `&KEY`, ...),
- built-in package prefixes and their members (`rl:fetch` reads as
  `RL:FETCH`, `ql:quickload` as `QL:QUICKLOAD`),
- keyword or `#:` designators (`(in-package :cl-user)` reads `:CL-USER`,
  `(:use #:cl)` reads `#:CL`).

Your symbols, your packages and your data keywords upcase the same way,
self-consistently, exactly like Common Lisp:

```lisp
(defun greet (name) (format nil "Hello, ~a!" name))
(greet "world") ; => "Hello, world!"
'foo ; => FOO
(symbol-name 'foo) ; => "FOO"
(symbol-name 'car) ; => "CAR"
(eq 'foo 'FOO) ; => T
(cdr (assoc :note '((:note . "hi")))) ; => "hi"
```

An escaped name keeps its case and is therefore a *distinct* symbol from the
upcased one, as in Common Lisp: `|car|` is not `CAR`.

Built-in keyword parameters match case-insensitively (`:TEST` works where
`:test` does), and `(intern "TIME")` names the standard `TIME`, so the
`(intern (string-upcase ...))` name-synthesis idiom lines up with body
references, the pattern behind macros like assoc-utils' `with-keys`:

```console
$ cat keys.lisp
(ql:quickload :assoc-utils)
(print (assoc-utils:with-keys ("name") (list (cons "name" "eitaro"))
         name))
$ rontolisp keys.lisp
"eitaro"
```

Libraries loaded with `load`, `asdf:load-system` or `ql:quickload` are read
the same way, so their definitions and your references upcase consistently.
A symbol system designator is downcased like ASDF's `coerce-name`
(`(ql:quickload :ASSOC-UTILS)` finds the `assoc-utils` system).

The runtime reader upcases too, so a datum read at run time behaves like the
same datum written in source: `read` and `read-from-string` upcase your
symbols identically on the interpreter, the JVM and both WASM backends.

```lisp
(read-from-string "foo") ; => FOO
(symbol-name (read-from-string "foo")) ; => "FOO"
(eq (read-from-string "list") 'list) ; => T
(eval (read-from-string "(reverse (list 1 2 3))")) ; => (3 2 1)
```

## Deviations from Common Lisp

- `intern`, `make-symbol` and `find-symbol` take the name verbatim (there is
  no separate intern table; a symbol *is* its name). `(find-symbol "car")` is
  `NIL` because the standard symbol is named `"CAR"`, and `(make-symbol "X")`
  twice yields `eq` symbols. Reading is unaffected -- `car` in source still
  upcases to `CAR`.
- A keyword or `#:` designator that spells a member in mixed case names that
  exact (mixed-case) symbol; write built-in members upper case (`#:CL`) or let
  the reader upcase a bare name.
