# Scheme REPL: what it echoes, when it prompts, `(exit)`, and where errors go

Difficulty: Medium

Found while running the corpus of `.todo/828` through `--source-language scheme` with no
file (1,592 transcripts, 2026-09-17). Robustness is `.todo/835`; these are the rest.

1. **Unspecified values are echoed.** `SchemeTopLevel.echoes` silences a DIRECT call to an
   `effect` builtin only:

   ```
   scheme> (if #f #f)
   ()
   scheme> (define (g) (display "a"))
   scheme> (g)
   a
   "a"                      <- display's argument, returned and echoed
   scheme> (print-rat one-half)      ; the book's procedure ending in (display ...)
   1/2
   ()
   ```

   Every program of chapter 2 that prints through its own procedure shows this. Fix at the
   value, not the syntax: effect builtins, `(if #f #f)`, `set!`, `for-each`, a `when` /
   `unless` / `cond` with no taken clause answer ONE unspecified object (a symbol in the
   style of the false value, so no backend learns it), and the echo skips it. It must not
   be `#f`-like or `'()`-like to a program: `(list (if #f #f))` has length 1 and the
   object is true in a test.
2. **Prompts on a pipe.** With stdin not a terminal the session still writes `scheme> `
   once per form AND once per blank or comment line, so a piped run is
   `scheme> scheme> scheme> scheme> 25`. The CL session does the same (`CL-USER> ` x n).
   No prompt when `System.console()` is null -- for both languages, in the shared loop --
   and no prompt for a line that held no datum. Check `RontoLispCliTest`'s transcripts:
   they pin today's shape and need rewriting, which is the point.
3. **`(exit)` is undefined** ("The function exit is undefined"; the only way out is end of
   input). `(exit)`, `(exit n)`, `(exit #t)`, `(exit #f)` and `emergency-exit` from
   `(scheme process-context)`, in file mode and compiled output as well.
4. **Errors go to stdout and the exit status is 0** after any number of `Error:` lines on
   a pipe. Decide once for both languages: keep `Error:` on stdout for a terminal, but on
   a pipe write it to stderr and exit non-zero at end of input if any form failed --
   that is what makes a session usable as a script runner. File mode already exits 1.
5. **Applying a non-procedure** reports `The function #f is undefined` (a `(get ...)` miss
   applied, common in chapter 2's data-directed code) and `Not a function: 3`. Both
   should read like the second: `#f is not a procedure`, with the operands.
6. One malformed line drops the whole line: `(+ 5 6) garbage)` reports `unexpected ')'`
   and never evaluates `(+ 5 6)`. Acceptable; pin it or evaluate the complete prefix.

Per the bug-fix rule each of 1-5 starts as a failing transcript in `RontoLispCliTest`
(`theSchemeRepl...`) / `SchemeSessionTest`. Doc: `doc/*/guides/scheme.md`, mirrored.
