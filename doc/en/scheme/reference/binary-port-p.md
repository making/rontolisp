# binary-port?

`(binary-port? obj)`

Returns `#t` if `obj` is a binary port: a bytevector port. The standard ports and string ports are not binary (Gauche's are).

```scheme
(binary-port? (open-input-bytevector #u8(1))) ; => #t
```
