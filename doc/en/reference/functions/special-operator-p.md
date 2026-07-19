# special-operator-p

`(special-operator-p symbol)`

Lite stub: always returns `nil`. Compiled programs have no reified operator table, and the interpreter's evaluator dispatch is not exposed as data. Exists so introspection helpers on cold branches (cl-ppcre's `regex-apropos`) compile.

```lisp
(special-operator-p 'if) ; => nil
```
