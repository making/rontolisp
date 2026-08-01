# nsubstitute-if-not

`(nsubstitute-if-not new predicate list &key key)`

The destructive variant of [`substitute-if-not`](substitute-if-not.md): rewrites the `car` of every cons whose element the predicate *rejects* and returns the (possibly mutated) original list. Lists only; see [`nsubstitute-if`](nsubstitute-if.md) for the shared cons-reuse semantics.

```lisp
(nsubstitute-if-not 0 #'oddp (list 1 2 3 4 5)) ; => (1 0 3 0 5)
```
