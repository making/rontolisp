# pprint pprint-newline pprint-indent pprint-tab

`(pprint object &optional stream)` -- `(pprint-newline kind &optional stream)` -- `(pprint-indent relative-to n &optional stream)` -- `(pprint-tab kind colnum colinc &optional stream)`

`pprint` writes a fresh line and then `object` in its readable form, returning no values. The other three are the pretty printer's layout operators. **A rontolisp stream carries no column**, so only `(pprint-newline :mandatory)` does anything: it writes a newline when `*print-pretty*` is true. The three conditional kinds (`:linear`, `:fill`, `:miser`), `pprint-indent` and `pprint-tab` are accepted and do nothing, and `*print-right-margin*` / `*print-miser-width*` / `*print-lines*` are inert for the same reason -- text comes out as if the line were wide enough.

```lisp
(with-output-to-string (s) (princ "a" s) (pprint-newline :fill s) (princ "b" s)) ; => "ab"
```
