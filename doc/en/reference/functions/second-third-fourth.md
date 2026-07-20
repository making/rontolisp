# second third fourth

`(second list)`, `(third list)`, `(fourth list)`

Ordinal accessors for the 2nd, 3rd, and 4th elements of a list, equivalent to `cadr`, `caddr`, and `cadddr` respectively. Each returns `nil` when the list is too short to have that element, since the underlying `car`/`cdr` walk bottoms out at `nil`.

```lisp
(third '(a b c d)) ; => C
```

```lisp
(fourth '(a b)) ; => nil
```
