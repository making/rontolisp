# integer?

`(integer? obj)`

`obj` が整数なら `#t` を返します。正確・不正確を問わず、`(integer? 2.0)` も `#t` です。浮動小数点数を除くには `exact-integer?` を使います。

```scheme
(integer? 2.0) ; => #t
(integer? 2.5) ; => #f
(integer? 4/2) ; => #t
```
