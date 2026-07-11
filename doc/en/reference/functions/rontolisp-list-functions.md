# rontolisp:list-functions

`(rontolisp:list-functions &optional package)`

Returns the function symbols of a package, sorted alphabetically. The optional
package designator is a keyword, a bare symbol, a quoted symbol, or a string
(`:cl`, `cl`, `'cl`, `"cl"`) and defaults to `:cl`. A name is listed as a
function exactly when it is usable as a function value via `#'name`. For
`:cl-user` it lists the user-defined `defun`s. An unknown package is an error.
See [Package introspection](../packages.md#package-introspection) for details.

```lisp
(rontolisp:list-functions :rontolisp) ; => (await fetch http-handler json-parse json-stringify list-functions list-macros list-special-forms promisep query-param query-params tcp-accept tcp-connect tcp-listen tcp-local-address tcp-local-port tcp-peer-address tcp-peer-port then tls-connect tls-listen tls-listen-pem url-decode url-encode url-path url-query version)
```
