# exact-integer?

`(exact-integer? obj)`

`obj` が正確な整数なら `#t` を返します。

```scheme
(exact-integer? 5) ; => #t
(exact-integer? 2.0) ; => #f
```
