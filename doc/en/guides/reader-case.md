# Reader Case (Upcasing)

rontolisp's reader upcases symbols the way standard Common Lisp's does: every
unescaped character of a symbol token is converted to upper case while
reading (the `:upcase` readtable case), so `foo`, `Foo` and `FOO` in source
all name the same symbol `FOO`. Escaped characters keep their case:
`|mixed Case|` and `\(` read verbatim.

The one rontolisp twist is that the *canonical spelling* of every built-in
name is lowercase. After upcasing, the reader folds a name whose canonical
spelling is lowercase back down, so all of these still resolve no matter how
they are written:

- standard `cl` names (`DEFUN` and `defun` both read as `defun`, `LIST` as
  `list`), including type-specifier and condition-type names (`HASH-TABLE`,
  `TYPE-ERROR`),
- `T` / `NIL` / `PI` and the other read-time constants,
- lambda-list markers (`&OPTIONAL`, `&KEY`, ...),
- built-in package prefixes and their members (`RL:FETCH` reads as
  `rl:fetch`, `QL:QUICKLOAD` as `ql:quickload`),
- keyword or `#:` designators of built-in packages (`(in-package :CL-USER)`,
  `(:use #:CL)`).

Everything else -- your symbols, your packages, data keywords -- reads
upcased, self-consistently, exactly like Common Lisp:

```lisp
(defun greet (name) (format nil "Hello, ~a!" name))
(greet "world") ; => "Hello, world!"
'foo ; => FOO
(symbol-name 'foo) ; => "FOO"
(eq 'foo 'FOO) ; => t
(cdr (assoc :note '((:NOTE . "hi")))) ; => "hi"
```

Built-in keyword parameters match case-insensitively (`:TEST` works where
`:test` does), and `(intern "TIME")` names the standard `time` -- the same
answer Common Lisp's upcase world gives -- so the
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
the same way, so their definitions and your references fold consistently.
`.asd` system definitions are the exception: they are parsed as data and
stay case-preserving, and a symbol system designator is downcased like
ASDF's `coerce-name` (`(ql:quickload :ASSOC-UTILS)` finds `assoc-utils`).

The runtime reader folds too, so a datum read at run time behaves like the
same datum written in source: `read` and `read-from-string` upcase your
symbols and fold the standard names back to their canonical spelling,
identically on the interpreter, the JVM and both WASM backends.

```lisp
(read-from-string "foo") ; => FOO
(symbol-name (read-from-string "foo")) ; => "FOO"
(eq (read-from-string "list") 'list) ; => t
(eval (read-from-string "(reverse (list 1 2 3))")) ; => (3 2 1)
```

## Deviations from Common Lisp

- rontolisp's canonical spelling of the standard symbols is lowercase, so
  `(symbol-name 'car)` is `"car"` (CL says `"CAR"`); a user symbol like
  `'foo` reports `"FOO"` as in CL, and printing follows the same rule
  (`(print 'car)` shows `car`, `(print 'foo)` shows `FOO`).
- `|car|` (pipe-escaped lowercase) is the standard `car`, not a distinct
  lowercase symbol -- the fold applies to the finished name.
- `(:import-from #:cl #:car)`-style clauses that spell a `cl` member as an
  uninterned designator resolve only when the member's lowercase spelling is
  used.
