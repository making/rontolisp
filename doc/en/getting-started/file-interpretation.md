# File Interpretation

Pass a path to a `.lisp` file and rontolisp interprets it directly, without
producing any compiled artifact. The file's top-level forms are read and
evaluated in order on the tree-walking interpreter, sharing one environment, so a
function or variable defined near the top is available to everything below it.

```bash
rontolisp program.lisp
```

Unlike the REPL, a script does not echo the value of each form -- output is
whatever the program writes explicitly with `print`, `format`, and the like. The
process exits with status `0` when the file runs to completion, or non-zero if a
form signals an error.

Example (`program.lisp`):

```lisp
(defun square (x) (* x x))
(print (square 5))
(print (square 12))
```

```
25
144
```

This is the same source you would later hand to the JVM or WASM compiler; running
it through the interpreter first is the fastest way to check a program's behavior
before compiling it.
