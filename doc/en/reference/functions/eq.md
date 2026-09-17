# eq

`(eq x y)`

Tests object identity, returning `t` or `nil`. Symbols and small integers with the same value are the same object and so compare `eq`, but floats and ratios are distinct boxed objects and are never `eq` even when numerically equal; cons cells and strings compare by reference, so two distinct strings with the same characters are not `eq` (two equal string literals in the program text are one object). Use `eql` or `equal` to compare numbers or structure by value. Works in all three backends.

```lisp
(eq 'foo 'foo) ; => T
```

```lisp
(eq 1.5 1.5) ; => NIL
```
