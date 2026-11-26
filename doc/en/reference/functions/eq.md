# eq

`(eq x y)`

Tests object identity, returning `t` or `nil`. Symbols compare by identity; cons cells and strings compare by reference, so two distinct strings with the same characters are not `eq` (two equal string literals in the program text are one object). Numbers and characters have no identity apart from their value: CL leaves `eq` on them implementation-dependent, and here it is exactly [`eql`](eql.md) -- two numbers of the same type and value are `eq`, floats and ratios included, so a float is always `eq` to itself however it was stored. Use `equal` to compare structure. Works in all three backends.

```lisp
(eq 'foo 'foo) ; => T
```

```lisp
(eq 1.5 1.5) ; => T
```
