# rontolisp:octets-to-string

`(rontolisp:octets-to-string octets)`

Decodes a packed `(unsigned-byte 8)` vector as the UTF-8 text its bytes spell, returning a fresh string. `rontolisp:string-to-octets` is the encoder that reverses it.

```lisp
(rontolisp:octets-to-string (rontolisp:string-to-octets "Hello, 世界!")) ; => "Hello, 世界!"
```

The decode is **total and lenient**: `octets` is never rejected. A byte that leads no valid sequence, and a sequence the vector's end cuts short, both decode to their own byte value as a one-character result rather than signaling -- the same rule an HTTP body carried as raw bytes is decoded by, so a body decodes the same whichever transport carried it. An overlong encoding and a UTF-8-encoded surrogate are not rejected either: each decodes to the code point its bits assemble, since a rontolisp character admits any code point in `0` to `#x10FFFF`, surrogates included.

```lisp
(rontolisp:octets-to-string #8@()) ; => ""
(mapcar #'char-code (coerce (rontolisp:octets-to-string #8@(#xE2 #x82)) 'list)) ; => (226 130)
```

The second example is a 3-byte sequence (`€`) cut off after its first continuation byte: each of the two remaining bytes decodes to its own value rather than being dropped or signaled.
