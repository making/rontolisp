# rontolisp:url-query

`(rontolisp:url-query string)`

Returns the raw query-string part of a URL or request-target string: the text
after the first `?` (possibly empty), or `nil` when there is no `?`. The
counterpart [`rontolisp:url-path`](rontolisp-url-path.md) returns the part
before it. The result is not decoded — pass it to
[`rontolisp:query-params`](rontolisp-query-params.md) or
[`rontolisp:query-param`](rontolisp-query-param.md).

```lisp
(rontolisp:url-query "/get?a=1&b=2")   ; => "a=1&b=2"
(rontolisp:url-query "/get")   ; => NIL
(rontolisp:query-param (rontolisp:url-query "https://example.com/s?q=lisp") "q")   ; => "lisp"
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
library is written in rontolisp itself and is compiled into the program when
used.
