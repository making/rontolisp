# rontolisp:query-param

`(rontolisp:query-param query name)`

Returns the url-decoded value of the first `name` match in a query string, or
`nil` when the name does not appear. `query` may be `nil` (the result is then
`nil` too), so the one-liner
`(rontolisp:query-param (getf request :query) "name")` works unchanged for
requests without a query string inside an
[`rontolisp:http-handler`](rontolisp-http-handler.md) handler.

```lisp
(rontolisp:query-param "a=1&name=ronto%20lisp" "name")   ; => "ronto lisp"
(rontolisp:query-param "q=1&q=2" "q")   ; => "1"
(rontolisp:query-param "a=1" "missing")   ; => nil
(rontolisp:query-param nil "a")   ; => nil
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
library is written in rontolisp itself and is compiled into the program when
used. To read all parameters at once use
[`rontolisp:query-params`](rontolisp-query-params.md).
