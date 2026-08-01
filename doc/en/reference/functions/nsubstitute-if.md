# nsubstitute-if

`(nsubstitute-if new predicate list &key key)`

The destructive variant of [`substitute-if`](substitute-if.md): rewrites the `car` of every cons whose element satisfies the predicate and returns the (possibly mutated) original list. The cons cells are reused, so any other reference to the list observes the change. Lists only — unlike `substitute-if` it does not rebuild strings or vectors.

```lisp
(nsubstitute-if 0 #'oddp (list 1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(let* ((a (list 1 2 3)) (b a)) (nsubstitute-if 0 #'oddp a) b) ; => (0 2 0)
```
