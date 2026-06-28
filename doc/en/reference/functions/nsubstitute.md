# nsubstitute

`(nsubstitute new old list)`

The destructive counterpart of `substitute`: rewrites the cars of `list` in place, replacing every element `eql` to `old` with `new`. Arguments are positional only (no `:test`/`:key`). The list structure is reused, so the modification is visible through the original variable.

```lisp
(nsubstitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```
