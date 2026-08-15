# 386. The cons-tree prelude defuns recurse once per CDR, so a long flat list overflows the stack

Difficulty: Medium

`copy-tree`, `equalp` and `subst` (`LispPreludeLibrary`, lines ~113 / ~1151 /
~1205) walk a cons tree by recursing in BOTH directions:

```lisp
(cons (copy-tree (car tree)) (copy-tree (cdr tree)))
```

The CAR recursion is bounded by the tree's nesting depth, which is fine. The
CDR recursion is one frame **per element**, so a plain flat list of ten
thousand conses -- an ordinary size -- is a `StackOverflowError`:

```
(defun mk (n) (let ((out nil)) (dotimes (i n) (setq out (cons i out))) out))
(copy-tree (mk 10000))          ; StackOverflowError
(equalp (mk 10000) (mk 10000))  ; StackOverflowError
(subst 1 2 (mk 10000))          ; StackOverflowError
```

`equal` (a Java built-in) is fine at the same size, so the divergence is
between the Lisp-source definitions and the native ones, on every backend.

`tree-equal` had the same shape and was fixed when it landed (`.todo/033`,
2026-08-15): the CDR direction became a `while` loop over two cursors, the CAR
direction kept its recursion. That is the model -- and for `copy-tree`/`subst`
the loop has to BUILD the spine as it walks (collect, then `nreverse` onto the
final tail) while `subst` additionally has to keep its structure sharing: an
unchanged subtree is returned as-is today, and a naive rebuild would copy
everything.

## Acceptance

- `(copy-tree (mk 100000))`, `(equalp (mk 100000) (mk 100000))` and
  `(subst 1 2 (mk 100000))` answer without overflowing, on all four backends.
- `subst` still shares unchanged subtrees (the `(eq a (car x))` /
  `(eq d (cdr x))` test the current body makes) -- pin it.
- Audit the rest of the prelude and the bundled Lisp libraries for the same
  shape; these three were found by probe, not by a sweep.

## Non-goals

- The CAR direction. A tree nested ten thousand deep is not an ordinary shape,
  and making that iterative costs an explicit work stack.
