# rontolisp:url-decode

`(rontolisp:url-decode string)`

Decodes a percent-encoded (URL-encoded) string: each `%XX` escape becomes a
byte and the byte sequence is decoded as UTF-8 (multi-byte escapes reassemble
into one character), and `+` becomes a space — the query-string convention.
The inverse is [`rontolisp:url-encode`](rontolisp-url-encode.md).

```lisp
(rontolisp:url-decode "Will+it+work%3F")   ; => "Will it work?"
(rontolisp:url-decode "%E3%81%82%E3%81%84")   ; => "あい"
(rontolisp:url-decode "plain")   ; => "plain"
```

An invalid escape (`%` not followed by two hex digits, or bytes that are not
valid UTF-8) signals an error:

```console
> (rontolisp:url-decode "%2")
Error: url-decode: unterminated percent escape
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
library is written in rontolisp itself and is compiled into the program when
used. [`rontolisp:query-params`](rontolisp-query-params.md) and
[`rontolisp:query-param`](rontolisp-query-param.md) decode keys and values
with it automatically.
