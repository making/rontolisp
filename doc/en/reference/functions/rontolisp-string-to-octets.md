# rontolisp:string-to-octets

`(rontolisp:string-to-octets string)`

Encodes `string` as UTF-8, returning a fresh packed `(unsigned-byte 8)` vector. `rontolisp:octets-to-string` is the decoder that reverses it.

```lisp
(rontolisp:string-to-octets "Hi") ; => #(72 105)
(rontolisp:string-to-octets "")   ; => #()
```

The encoding is **total**: every code point from `0` to `#x10FFFF` -- surrogates included, since a rontolisp character has no narrower range -- has exactly one (shortest) UTF-8 encoding, so there is no input `string-to-octets` refuses.

The pair round-trips for any well-formed input, but only in the decode-then-encode direction in general: `rontolisp:octets-to-string` is lenient about malformed bytes (see its own page), and its answer for those does not always re-encode back to the same bytes -- only a complete, canonical sequence does.

```lisp
(rontolisp:string-to-octets (rontolisp:octets-to-string #8@(226 130 172))) ; => #(226 130 172)
```
