# `delete` / `delete-if` / `nsubstitute` are silent no-ops on a vector, and `sort` drops a vector's fill pointer

Difficulty: Low

Two separate misses in the same family, both measured on the installed
interpreter binary; check all four backends before fixing (`.kb/sequence-op-runtimes.md`).

## 1. The destructive `n`-forms answer the sequence unchanged

They are correct on a LIST and do nothing on a vector or a string -- and they
answer the untouched sequence rather than signalling, so the caller sees a
plausible wrong value.

```lisp
(delete 1 (vector 3 1 2))            ; => #(3 1 2)   SBCL: #(3 2)
(delete #\a (copy-seq "aba"))        ; => "aba"      SBCL: "b"
(delete-if #'oddp (vector 1 2 3 4))  ; => #(1 2 3 4) SBCL: #(2 4)
(delete-if-not #'oddp (vector 1 2 3)); => #(1 2 3)   SBCL: #(1 3)
(nsubstitute 9 1 (vector 1 2 1))     ; => #(1 2 1)   SBCL: #(9 2 9)
(nsubstitute-if 0 #'oddp (vector 1 2 3)) ; => #(1 2 3) SBCL: #(0 2 0)

(delete 1 (list 1 2 1))              ; => (2)        correct
(remove 1 (vector 3 1 2))            ; => #(3 2)     correct
```

`remove`/`remove-if`/`substitute` are all right, so the non-destructive halves
already know how to walk a vector -- what is missing is the `n`-form's vector
arm. CL lets a destructive form answer a FRESH sequence, so the cheapest
correct fix is to route the vector case through the `remove` implementation and
answer its result; only add real in-place compaction (shifting down and pulling
the fill pointer back) if a caller needs the same object.

## 2. `sort` on an adjustable vector answers a fresh simple vector

```lisp
(let ((v (make-array 3 :adjustable t :fill-pointer 3 :initial-contents '(3 1 2))))
  (let ((s (sort v #'<)))
    (list (fill-pointer s) (adjustable-array-p s) (eq v s))))
;; rontolisp: fill-pointer signals "array has no fill pointer", adjustable nil, eq nil
;; SBCL:      (3 T T)
```

`.kb/sequence-op-runtimes.md`'s `seqResultDispatchForm` converts the result back
to a string/vector/list, and "vector" there means a SIMPLE vector -- the fill
pointer and the adjustable flag are lost on the way out. `.todo/602` is the same
dispatch missing entirely for `nreverse`/`stable-sort`; this is the dispatch
being present but lossy, so the two are worth fixing together.

This one has a caller: `practicals-1.0.3/Chapter27/database.lisp` stores its
rows in `(make-array size :adjustable t :fill-pointer 0)` and `sort-rows` does
`(setf (rows table) (sort (rows table) ...))`. The next `delete-rows` ends its
loop with `(setf (fill-pointer rows) store-idx)` and dies with
`%SET-FILL-POINTER: array has no fill pointer` -- the failure surfaces two
operations after the one that caused it. Found by `.todo/620`.

Pin both halves in `LispEvaluatorTest` + both compiler tests + ci-spec rows.
