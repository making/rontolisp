# Scheme file mode: a builtin used BEFORE the file redefines it diverges three ways

Difficulty: Medium

Found while probing for `.todo/828`. A `.scm` file that uses a builtin and LATER defines
the same name -- the book's evaluators do exactly this to keep the host's `apply`,
`(define apply-in-underlying-scheme apply)` then `(define (apply procedure arguments) ...)`
-- means the builtin up to the definition. Measured 2026-09-17:

```scheme
;; c.scm                                              ;; b.scm
(define old-abs abs)                                  (display (abs -5)) (newline)
(define (abs x) (if (< x 0) 'neg (old-abs x)))        (define (abs x) 'mine)
(display (abs -5)) (display (abs 5))                  (display (abs -5)) (newline)
```

| | `c.scm` | `b.scm` |
|---|---|---|
| expected, and what the REPL session prints | `neg5` | `5` / `mine` |
| interpreter, file mode | `The function abs is undefined` | `The function abs is undefined` |
| JVM | `neg` then `StackOverflowError` (`old-abs` IS the user's `abs`) | `mine` / `mine` |
| wasm | not run | `mine` / `mine` |

Cause: the whole-file pre-scan (`.kb/scheme-frontend.md`, "A whole FILE is lowered at
once") makes `abs` the file's own `defun` for EVERY occurrence, position-blind. The
interpreter then runs top-level forms in order and has no `abs` yet; the compile path
hoists the `defun`. The session lowers each form against the names known when it was
typed, which is the right rule.

- First a failing case per shape in `scheme-spec.yaml` (all four backends) and the emitted
  forms in `SchemeLoweringTest`; then the fix.
- Direction: a top-level name that shadows a builtin and is REFERENCED before its first
  definition cannot take the `defun` shape. Resolve occurrences textually before the
  definition to the builtin (call template / `:function` value), occurrences after it to
  the user's binding; bodies of procedures defined earlier but called later see the user's
  binding, as in any Scheme (a global is looked up at call time) -- so the general shape is
  the variable + `funcall` one, initialized to the builtin's `:function` value. Keep the
  direct-call `defun` for the overwhelmingly common case (defined before any use: 121
  corpus files define `square`, 44 `abs`, 16 `sqrt`) -- measure that the corpus emits the
  same bytes before and after for those.
- The interpreter/compile-path difference for a NON-builtin called before its `define`
  (`(f 1)` above `(define (f x) ...)`: an error in Scheme) is the same hoisting; decide
  whether to pin it or leave it, and write the decision into the kb.
