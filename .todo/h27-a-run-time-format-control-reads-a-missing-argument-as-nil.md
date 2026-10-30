# A run-time `format` control reads a missing argument as nil

Difficulty: Medium

A literal control with too few arguments signals, as CLHS requires and SBCL does
(`format: not enough arguments for directive ~a`, the expansion's `FmtArgs.next`). The
same control reaching `format` as data renders the missing argument as nil instead:

```console
$ rontolisp -e '(print (let ((s "Skip ~a.")) (format nil s)))'
"Skip NIL."
```

SBCL signals `error in FORMAT: No more arguments`. Seen while rendering restart reports for
`rontolisp --swank`'s debugger, where a report control without its arguments printed
`Skip NIL.`.

Direction: the run-time renderer (`macro/FormatRenderer`'s `format-render.lisp`, spliced
into every program that formats a non-literal control) signals the same error where a
directive consumes past the argument list (`~^` and `~*` keep their own rules). It is
shared by all four backends, so it changes on each with a ci-spec case next to the
literal one; measure each directive that consumes an argument against SBCL.
