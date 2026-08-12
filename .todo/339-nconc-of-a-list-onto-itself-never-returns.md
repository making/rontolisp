# `(nconc x x)` never returns

Difficulty: Low

Found 2026-08-12 by the ANSI test-suite harness (`ansi-test/`): the suite's
`universe.lsp` builds its circular-cons fixtures with `(nconc s s)`, and that one
form hangs the interpreter. It is the ONLY form in the whole suite prefix that
does not terminate, and every chapter pays for it -- the harness has to kill the
child and re-run the chapter without it, which is also why `*circular-conses*`
is missing from `*universe*` in every reported chapter.

## Repro

```console
$ rontolisp -e '(let ((s (copy-list (list 1 2 3)))) (nconc s s) (print :built))'
   (never prints, never exits)
```

Splicing a list onto ITSELF is a legal way to make a circular list in CL; SBCL
returns immediately. The neighbouring constructions do work:

```console
$ rontolisp -e '(let ((s (list nil))) (setf (car s) s) (print :ok))'
:OK
```

so the cycle itself is not the problem -- walking to the last cons and then
continuing to walk it is.

## What to look at

`nconc` in the interpreter (`Environment.createGlobal`) and its compile-path
twins. The fix is to find the last cons of each argument BEFORE linking it, and
to bound the walk of the final argument (CL leaves the last argument untouched,
so the destructive splice never needs to traverse it). Check whether `append`,
`last`, `butlast` and `list-length` have the same shape of walk: `list-length`
is specified to answer `nil` on a circular list, which is a related test the
suite exercises.

## Done when

The repro above prints `:BUILT` on all four backends, `ansi-test/measure.sh`
reports no non-terminating form for any chapter, and the counts in
`ansi-test/results/interpreter.md` are re-measured with `*circular-conses*`
present.
