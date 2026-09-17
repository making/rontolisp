# REPL: a stack overflow or a cyclic value must not kill the session

Difficulty: Medium

Found while running the corpus of `.todo/828` through `--source-language scheme` with no
file. Two inputs end the whole process with a Java stack trace and exit 1, discarding every
definition typed so far (measured 2026-09-17, `java -jar`, default `--stack`):

1. **Deep recursion.** `StackOverflowError` escapes the read-eval-print loop:

   ```
   scheme> (define (count n) (if (= n 0) 0 (+ 1 (count (- n 1)))))
   scheme> (count 10000)
   Exception in thread "main" java.lang.StackOverflowError
           at am.ik.rontolisp.eval.LispEvaluator.evalConsRareOperator(...)
   ```

   The Common Lisp REPL dies the same way (`(defun ev (n) ...)` / `od`, depth 1,000,000), so
   the catch belongs where both sessions evaluate a form (`eval/SourceSession`, the CLI's
   loop), not in the Scheme front end: report `Error: stack overflow (depth ...; --stack
   <MiB> raises it)` like any other error and prompt again. Check what state an overflow
   can leave behind (`DynamicBindings`, a half-run `unwind-protect`) before calling it safe.
   File mode has the same trace for an uncaught overflow; one line and exit 1 would do.
2. **Session depth is below file depth.** Non-tail `count`: a file passes 10,000 and fails
   at 20,000; a session passes 8,000 and fails at 10,000. A session procedure is a variable
   reached through `funcall` (plus the trampoline for a name called before it existed,
   `.kb/scheme-frontend.md`, "A session"). Measure the frames per call in each shape; if
   the trampoline is on the recursive path, a name already defined when the form is typed
   should not go through it.
3. **Echoing a cyclic list.**

   ```
   scheme> (define l (list 1 2))
   scheme> (set-cdr! (cdr l) l)
   scheme> l
   Exception in thread "main" java.lang.StackOverflowError
           at am.ik.rontolisp.PackageResolver.resolveQuotedDatum(PackageResolver.java:1498)
   ```

   The frames say the echoed VALUE is walked as a quoted datum -- the echo seems to embed
   it in a form -- so the overflow precedes any printing. The CL session prints
   `(2 1 . #)` for the same structure. Hand the value to `%scheme-write` without a trip
   through the resolver, and make `%scheme-write`/`%scheme-display` finite on a cycle:
   R7RS `write` uses datum labels (`#0=(1 2 . #0#)`); `display` of a cycle loops forever
   in file mode today (`(display l)` prints `(1 2 1 2 ...` until killed). The corpus builds
   cycles on purpose (`make-cycle`, `chapter3/section3/subsection1`).

Per the bug-fix rule: failing tests first -- `RontoLispCliTest` transcripts for 1 and 3 in
both languages, a `scheme-spec.yaml` case for `write` of a cycle on all four backends.
