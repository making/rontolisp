# ffi:poke

`(ffi:poke pointer type value &optional offset)`

Writes `value` as `type` at `pointer` plus `offset` bytes and
answers the value. A `:string` poke is refused: a string has to be allocated
somewhere, and this verb writes into memory you already own.

```lisp
(let ((p (ffi:alloc 8)))
  (prog1 (ffi:poke p :double 1.5) (ffi:free p)))
; => 1.5
```

An integer written as a narrower type keeps its low bits, the way a C assignment does.
