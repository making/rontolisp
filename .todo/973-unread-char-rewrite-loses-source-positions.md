# The unread-char rewrite loses a malformed form's source position

Difficulty: Low

A program that names `unread-char` -- or `read`, which is prelude Lisp over `unread-char` --
compiles through `UnreadCharLibrary`'s rewrite, which rebuilds every form with fresh conses
(`listOf`, `keepThenRewrite`, `rebuildSpine` with `new LispCons`). The positions
`SourceProvenance` recorded for the program's own forms go with them, so a malformed form below
the top level reports the raw `ClassCastException` ("class am.ik.rontolisp.LispInteger cannot be
cast to class am.ik.rontolisp.LispCons") instead of `bad.lisp:2:3: ...`, on every compile target
(checked 2026-09-26: `-o X.class`, a Preview 1 `.wasm`, `--component`):

```lisp
(defun g (x)
  (let ((a 1) 2)
    a))
(print (unread-char #\a))   ; or (print (read))
(print (g 1))
```

`eval` and `read-char` keep the position. Found when a parameter named `read` in http.lisp made
every fetch component splice the prelude reader.

## Goal

The rewrite keeps an untouched form's identity (the cons-identity rule, `.kb/source-positions.md`),
and `RontoLispCliTest.aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice` gains an
`unread-char` and a `read` trigger.
