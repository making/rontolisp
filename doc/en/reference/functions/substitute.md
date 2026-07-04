# substitute

`(substitute new old sequence)`

Returns a new sequence in which every element `eql` to `old` is replaced by `new`; all other elements are kept unchanged. The sequence may be a list or a string; a string yields a new string (with `new` a character). Arguments are positional only -- there is no `:test` or `:key`. The original sequence is not modified; use `nsubstitute` for the destructive version (lists only).

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(substitute #\o #\a "banana") ; => "bonono"
```
