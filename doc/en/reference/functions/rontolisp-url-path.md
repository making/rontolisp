# rontolisp:url-path

`(rontolisp:url-path string)`

Returns the part of a URL or request-target string before the first `?` (the
whole string when there is no `?`). The counterpart
[`rontolisp:url-query`](rontolisp-url-query.md) returns the part after it.

```lisp
(rontolisp:url-path "/get?a=1")   ; => "/get"
(rontolisp:url-path "/get")   ; => "/get"
(rontolisp:url-path "https://example.com/a/b?x=1")   ; => "https://example.com/a/b"
```

Inside an [`rontolisp:http-handler`](rontolisp-http-handler.md) handler the
environment plist's `:path-info` already carries the path only, so this helper is
mainly for splitting URL strings on the
[`rontolisp:fetch`](rontolisp-fetch.md) (client) side.

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
library is written in rontolisp itself and is compiled into the program when
used.
