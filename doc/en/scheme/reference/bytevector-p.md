# bytevector?

`(bytevector? obj)`

Returns `#t` if `obj` is a bytevector, otherwise `#f`. A vector of small integers is not a bytevector, and a bytevector is not a vector: `vector?` answers `#f` for it.

```scheme
(bytevector? #u8(1 2)) ; => #t
(bytevector? #(1 2)) ; => #f
(vector? #u8(1 2)) ; => #f
```
