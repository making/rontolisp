# rontolisp:query-params

`(rontolisp:query-params query)`

Parses a query string such as `"a=1&b=two&flag"` into an alist of
`(key . value)` string pairs. Keys and values are url-decoded with
[`rontolisp:url-decode`](rontolisp-url-decode.md); a key without `=` gets the
value `""`; duplicate keys are preserved in order; empty segments are
skipped. `nil` (a request without a query string) yields `nil`, so
`(rontolisp:query-params (getf request :query))` is always safe inside an
[`rontolisp:http-handler`](rontolisp-http-handler.md) handler.

```lisp
(rontolisp:query-params "a=1&b=two&flag")   ; => (("a" . "1") ("b" . "two") ("flag" . ""))
(rontolisp:query-params "q=%E3%81%82&q=2")   ; => (("q" . "あ") ("q" . "2"))
(rontolisp:query-params nil)   ; => nil
```

The alist prints readably and works with `assoc`:

```lisp
(cdr (assoc "b" (rontolisp:query-params "a=1&b=two") :test #'string=))   ; => "two"
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
library is written in rontolisp itself and is compiled into the program when
used. For the common "value of one name" lookup use
[`rontolisp:query-param`](rontolisp-query-param.md).
