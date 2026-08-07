# Formatting Source Code

`rontolisp format` re-indents Lisp source files in place. Point it at a file or
at a directory and every `.lisp` and `.asd` file under it is rewritten to one
canonical layout, so indentation stops being something anyone has to think about
or review.

```bash
rontolisp format app.lisp          # one file
rontolisp format src/              # every .lisp / .asd under src/
rontolisp format src/ tests/       # several paths
```

Only whitespace changes. Every token is reproduced exactly as written --
including its case, so `Foo` stays `Foo` -- and strings, character literals,
block comments and `#+`/`#-` guards are copied through untouched. The formatted
file reads as precisely the same program; nothing is macroexpanded, evaluated, or
loaded, so a file formats whether or not its dependencies are installed.

Files that are already formatted are left alone, not even rewritten, so the
command is safe to run over a whole tree repeatedly.

## Options

| Option | Meaning |
| --- | --- |
| `--check` | Do not write anything. List the files that are not formatted and exit `1` if there are any. |
| `--stdout` | Write the result to standard output instead of the file (one file only). |
| `--width=N` | Right margin to wrap to. Default `80`. |
| `-h`, `--help` | Show the command's help. |

A `-` in place of a path formats standard input to standard output, which is what
an editor's "format buffer" command wants:

```bash
echo '(let ((a 1)(b 2))(+ a b))' | rontolisp format -
```

```
(let ((a 1) (b 2)) (+ a b))
```

`--check` writes nothing and fails when the tree is not formatted, which makes it
a one-line CI gate:

```bash
rontolisp format --check src/ || { echo "run: rontolisp format src/"; exit 1; }
```

## What the layout looks like

A form that fits within the margin goes on one line. One that does not breaks
according to what its operator is.

A definition keeps its name and lambda list on the first line and indents the
body by two:

```lisp
(defun fizzbuzz (n)
  (cond ((zerop (mod n 15)) "FizzBuzz")
        ((zerop (mod n 3)) "Fizz")
        ((zerop (mod n 5)) "Buzz")
        (t (write-to-string n))))
; => FIZZBUZZ
```

`cond` clauses line up under the first clause. `if` puts its two branches under
the test, so they read as a pair rather than as a body:

```lisp
(let ((threshold (* 10 10)) (small-label "small") (measured (list 1 2 3 4 5 6)))
  (if (< (length measured) threshold)
      (list small-label (length measured))
      (list "large" (length measured))))
; => ("small" 6)
```

`let` bindings line up under the first binding, once they stop fitting on the
`let`'s own line:

```lisp
(let ((numbers (list 3 1 4 1 5 9 2 6))
      (sorted (sort (list 3 1 4 1 5) #'<))
      (total (+ 1 2 3 4 5)))
  (list (length numbers) sorted total))
; => (8 (1 1 3 4 5) 15)
```

A function call puts its arguments under the first one, and keeps each
`:keyword value` option together on a line of its own:

```console
(with-open-file (out "report.txt"
                     :direction :output
                     :if-exists :supersede
                     :if-does-not-exist :create)
  (write-line "done" out))
```

`loop` gets one line per clause, aligned under the first:

```lisp
(loop for i from 1 to 10
      when (evenp i)
      collect (* i i) into squares
      finally (return squares))
; => (4 16 36 64 100)
```

### Two body forms are always two lines

A body of two or more forms is a sequence performed in order, so it gets a line
each however short it is -- the same reason no formatter of a C-like language
will put two statements on one line. A body of exactly one form may share the
header's line:

```lisp
(defun double (x) (* 2 x))
; => DOUBLE
```

but a body of two may not, even though it would fit:

```lisp
(defun report (x)
  (print x)
  (terpri))
; => REPORT
```

This is also what keeps the output stable: a two-form body will not silently
join because a rename made it two characters shorter.

### Comments

A comment that started its own line keeps one, at the indentation of the code
around it. A comment that trailed code stays on that code's line, and the
trailing comments of consecutive lines are lined up into a column:

```console
(setq width 80)      ; the right margin
(setq body-indent 2) ; body indentation
(setq tabs nil)      ; spaces only
```

### Blank lines

A blank line is the only paragraph break Lisp source has, so wherever you left
one it stays -- inside a body as well as between top-level forms. A run of
several collapses to one, and no blank line is ever added.

## Limits

Line comments and string literals are never re-wrapped: their content is yours,
not the formatter's. A line whose content simply cannot be broken -- a long
string, a deeply nested expression with no shorter arrangement -- may therefore
end up past the margin.

A macro the formatter has not been told about is laid out from its name:
`with-...` and `do-...` take one argument then a body, `def...` takes a name then
a body, and anything else is laid out as a function call. If your own macro takes
a different shape, write it in the layout you want and the formatter will keep it
as long as it fits on one line.
