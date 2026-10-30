# h47. `make-hash-table :test` given as a function value (or computed) falls back to EQUAL

Difficulty: Medium

Found 2026-10-30 probing `cl-yaml`. `(alexandria:plist-hash-table plist :test #'equalp)`
passes the test through `apply`, so the YAML mapping is an EQUAL table and a lookup with a
different case misses.

```lisp
(let ((h (make-hash-table :test #'equalp))) (setf (gethash "A" h) 1) (gethash "a" h))
(defun mk (test) (make-hash-table :test test))
```

| case | SBCL | interpreter | JVM |
|---|---|---|---|
| literal `#'equalp` | EQUALP, 1 | **EQUAL, NIL** | EQUALP, 1 |
| `(mk #'equalp)` | EQUALP, 1 | EQUAL, NIL | EQUAL, NIL |
| `(mk 'eq)` | EQ | EQ | **EQUAL** |

`.kb/hash-tables.md` documents "a computed `:test` places as `equal`". That is a silent
wrong answer: alexandria's `plist-hash-table`/`alist-hash-table`/`copy-hash-table` always pass
the test computed. Decide the test at run time from the value (symbol or one of the four
function objects) on all four backends. The gates `programMakesEqualpHashTable`/
`programMakesIdentityHashTable` then have to treat a computed `:test` as possibly every
test. The interpreter's literal `#'equalp` row is a separate bug: add its failing case
first. (`.todo/012` owns the separate default-test gap: `(make-hash-table)` still answers EQUAL
where CLHS says EQL. Measured again 2026-10-30 on the interpreter, JVM and Preview 1.)
