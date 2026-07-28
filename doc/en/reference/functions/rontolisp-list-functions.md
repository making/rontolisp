# rontolisp:list-functions

`(rontolisp:list-functions &optional package)`

Returns the function symbols of a package, sorted alphabetically. The optional
package designator is a keyword, a bare symbol, a quoted symbol, or a string
(`:cl`, `cl`, `'cl`, `"cl"`) and defaults to `:cl`. A name is listed as a
function exactly when it is usable as a function value via `#'name`. For
`:cl-user` it lists the user-defined `defun`s. An unknown package is an error.
See [Package introspection](../packages.md#package-introspection) for details.

```lisp
(rontolisp:list-functions :rontolisp) ; => (AWAIT CATCH FETCH FINALLY HTTP-HANDLER JSON-PARSE JSON-STRINGIFY LIST-FUNCTIONS LIST-MACROS LIST-SPECIAL-FORMS MAKE-MUTEX MUTEX-ACQUIRE MUTEX-RELEASE QUERY-PARAM QUERY-PARAMS RANDOM-BYTES TCP-ACCEPT TCP-CONNECT TCP-LISTEN TCP-LOCAL-ADDRESS TCP-LOCAL-PORT TCP-PEER-ADDRESS TCP-PEER-PORT THEN THEN* TLS-CONNECT TLS-LISTEN TLS-LISTEN-PEM URL-DECODE URL-ENCODE URL-PATH URL-QUERY VERSION WIT-ERROR-PAYLOAD WIT-PROVIDE)
```
