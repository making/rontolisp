# substitute

`(substitute new old list)`

Returns a new list in which every element `eql` to `old` is replaced by `new`; all other elements are kept unchanged. Arguments are positional only -- there is no `:test` or `:key`. The original list is not modified; use `nsubstitute` for the destructive version.

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```
