# rontolisp:url-encode

`(rontolisp:url-encode string)`

Encodes a string for embedding in a URL: RFC 3986 unreserved characters
(letters, digits, `-`, `.`, `_`, `~`) pass through unchanged and every other
character becomes the percent-encoded form of its UTF-8 bytes (a space
becomes `%20`, not `+`). The inverse is
[`rontolisp:url-decode`](rontolisp-url-decode.md).

```lisp
(rontolisp:url-encode "a b/c~d")   ; => "a%20b%2Fc~d"
(rontolisp:url-encode "あ")   ; => "%E3%81%82"
(rontolisp:url-decode (rontolisp:url-encode "日本語 text?&="))   ; => "日本語 text?&="
```

The typical use is building a [`rontolisp:fetch`](rontolisp-fetch.md) URL
from runtime values:

```lisp
(concatenate 'string "https://httpbin.ik.am/get?q=" (rontolisp:url-encode "ronto lisp"))
; => "https://httpbin.ik.am/get?q=ronto%20lisp"
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
library is written in rontolisp itself and is compiled into the program when
used.
