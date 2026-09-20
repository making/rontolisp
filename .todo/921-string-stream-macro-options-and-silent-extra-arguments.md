# `with-input-from-string` / `with-output-to-string`'s full option set -- and the extra arguments a call silently drops

Difficulty: Medium (two macro expansions plus one lambda-list rule)

Split out of `.todo/906` (2026-09-20). Two unrelated findings that both surfaced
while measuring the ANSI `streams` chapter; the first is contained, the second is
one line in the binder with a reach far past `streams`.

## Measured (2026-09-20, interpreter, suite `ca06bd9`, ANSI `streams` after `.todo/906`)

| cluster | count | reason |
|---|---:|---|
| `with-input-from-string` | 13 LOST top-level forms | `WITH-INPUT-FROM-STRING supports only a (var string) spec` -- the spec takes `:index`, `:start` and `:end` |
| `with-output-to-string` | 6 LOST top-level forms | `WITH-OUTPUT-TO-STRING supports only a nil string-form (fresh-string) spec` -- the spec takes a STRING with a fill pointer to append to, and `:element-type` |
| extra arguments dropped | 4 in `streams`, many chapters over | `(clear-input t nil nil)` must be a `program-error`; here it just runs |

Both refusals are thrown at EXPANSION time, so each costs its whole `deftest`.
Making them CALL-time stubs first (`LispMacroExpander.callTimeUnsupportedStub`)
makes the chapter's denominator honest without moving the pass rate.

## Extra arguments

```lisp
(defun f (&optional a) a)
(f 1 2 3)   ; => 1 here; a PROGRAM-ERROR in CL (and SBCL)
```

The interpreter's lambda binder ignores arguments past the lambda list instead of
signalling. Every `*.ERROR.N` test in the suite that calls an operator with one
argument too many is failing for this reason, in every chapter -- `streams` alone
has `CLEAR-INPUT.ERROR.3/4`, and the built-ins that DO check
(`Environment.requireArgCount*`, which signals a `program-error`) only cover the
Java-side primitives, not a prelude or user `defun`. The compile paths need the
same answer, and a fixed-arity call site that the compiler can see wrong should
still be allowed to refuse at compile time.

## Notes

- `.kb/lambda-lists.md` is the design home for the binder half.
- Check the blast radius before landing the binder change: rontolisp's own
  prelude and the quicklisp corpus may contain calls that currently rely on the
  extra argument being dropped.
- Measure as a DIFF of failing test NAMES across SEVERAL chapters for the binder
  half, not just `streams`; report fixed AND regressed.
