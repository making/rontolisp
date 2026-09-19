# REPL

With no file, `--source-language scheme` starts a Scheme REPL. Values are echoed as
`write` prints them. A definition echoes nothing, and neither does the unspecified value
-- what `display`, `set!`, `for-each` or an `if` with no branch taken answer, also when a
procedure of your own ends in one. A form may span lines. An error, a stack overflow
included, is reported and the session goes on with its definitions; `(exit)` ends it.
With input piped in, the session is a script runner, as the
[Common Lisp REPL](../getting-started/repl.md) is: no prompt, errors on standard error,
exit status 1 if any form failed.

```console
$ rontolisp --source-language scheme
scheme> (define (square x) (* x x))
scheme> (map square '(1 2 3))
(1 4 9)
scheme> (set! square -)
scheme> (square 5)
-5
scheme> (list #t #f '() 'Sym)
(#t #f () Sym)
scheme> (define (show x) (display x) (newline))
scheme> (show 'done)
done
scheme> (exit)
```

Everything those twelve libraries export -- plus the
[*Structure and Interpretation of Computer Programs* (SICP)-compatibility names](sicp.md),
which no `(import ...)` names, unless
[`--scheme-standard r7rs`](standards.md) -- is visible from the
start, and an
`(import ...)` typed at the prompt only adds names. Definitions typed at separate prompts
see each other in either order, as they would in one file. Two things differ from a file,
because a form is fixed when it is typed: redefining a built-in procedure (`square`) does
not reach the forms typed before it, and a procedure that calls itself in tail position
keeps looping on itself if a later `set!` replaces it while an old copy is still held.
