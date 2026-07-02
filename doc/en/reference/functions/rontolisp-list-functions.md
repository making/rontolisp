# rontolisp:list-functions

`(rontolisp:list-functions &optional package)`

Returns the function symbols of a package, sorted alphabetically. The optional
package designator is a keyword, a bare symbol, a quoted symbol, or a string
(`:cl`, `cl`, `'cl`, `"cl"`) and defaults to `:cl`. A name is listed as a
function exactly when it is usable as a function value via `#'name`. For
`:cl-user` it lists the user-defined `defun`s. An unknown package is an error.
See [Package introspection](../packages.md#package-introspection) for details.

```lisp
(rontolisp:list-functions :rontolisp) ; => (await fetch list-functions list-macros list-special-forms promisep then version)
```
