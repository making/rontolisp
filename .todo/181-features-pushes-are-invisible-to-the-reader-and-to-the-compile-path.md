# A program cannot observe its own `*features*` pushes: invisible to the reader on both paths, and to the compile path entirely

Split out of `.todo/179` (Finding 3), where it was found as the cause of a live
correctness bug in a real library. Nothing here is uax-15-specific.

## The two gaps, measured

`trivial-utf-16.lisp` in uax-15 opens with

```lisp
(eval-when (:compile-toplevel :load-toplevel :execute)
  (case char-code-limit
    (#x10000  (pushnew :utf-16 *features*))
    (#x110000 (pushnew :utf-32 *features*))
    (t (error "Unexpected char-code-limit ..."))))
```

and later files read `#+utf-32` / `#-utf-16`. rontolisp's `char-code-limit` is
1114112 = #x110000, so the right branch is selected -- and the push has no
effect anyway:

| | interpreter | JVM compile path |
| --- | --- | --- |
| `pushnew` inside `eval-when` mutates `*features*` | yes | **no** |
| plain top-level `pushnew` mutates `*features*` | yes | **no** |
| a later `#+my-feature` in the same file sees it | **no** | **no** |

- **The reader gap** (both paths): a file is read whole before any of its forms
  is evaluated, so a read-time conditional can never see a feature the same file
  pushes. Real CL's `load` reads and evaluates FORM BY FORM, so upstream's
  arrangement works there. Fixing this means form-at-a-time loading -- for the
  interpreter a real option, for the compile path a question of what "read the
  program" means when the reader's behavior depends on values the program
  computes.
- **The compile-path gap**: `pushnew` onto `*features*` has no effect AT ALL, so
  a compiled program cannot observe its own feature pushes even at run time,
  long after reading is over. This one is a plain bug with no design tension:
  `*features*` should be an ordinary special variable holding a list at run time.

## Why it matters

A library that keys behavior on its own feature push silently takes the WRONG
branch, and the failure is invisible -- an `#+`-guarded `let` binding simply
degenerates to `(let ((x)) x)` -> `nil`. That is exactly what happened to
`uax-15:unicode-letter-p`: `char-from-hexstring` returned NIL for every input,
so all 21,765 data-derived letter entries collapsed onto one `nil` hash key and
`(unicode-letter-p #\A)` answered NIL on every backend. `postmodern/util.lisp`
calls `unicode-letter-p`, and Postmodern proper is the declared follow-up to
`.todo/115`.

That specific victim is no longer broken -- `.todo/179`'s derived uax-15 tables
sidestep `char-from-hexstring` entirely (`eval/Uax15Tables`, and the pin in
`Uax15E2eTest` asserts `T` for `#\A`) -- but that is luck, not a fix. The next
library that pushes a feature and reads it back gets the same silent wrong
branch.

## Scope

1. **Make `*features*` mutable at run time on the compile paths** (the plain
   bug). `pushnew`/`push`/`setq` on it must behave like any other special.
   Cross-check `.kb/reader-features.md` for what the emitted program is supposed
   to know about `*features*` at all.
2. **Decide the reader story**, and write the decision plus its reason into
   `.kb/reader-features.md` so the next visitor can tell whether it still holds:
   either form-at-a-time reading (at least for `load`/`asdf:load-system` on the
   interpreter, matching CL), or an explicit documented divergence saying a file
   cannot condition on its own pushes.
3. Whatever lands must hold on **all four backends** and be pinned by a test
   that a file's own `pushnew` + a later `#+`/`#-` in the SAME file agrees with
   the interpreter.
