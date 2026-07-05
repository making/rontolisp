# intern

`(intern string)`

Returns the symbol named by `string`, verbatim (no case folding). rontolisp symbols compare by name — there is no separate intern table — so the result is `eq` to any symbol with the same name, including quoted literals. Deviations from Common Lisp: the current package is ignored (the name is used exactly as given, so `(intern "foo")` under any `in-package` yields the bare symbol `foo`), a package argument signals an error, and there is no second `status` value.

```lisp
(intern "hello") ; => hello
```

```lisp
(eq (intern "foo") 'foo) ; => t
```

```lisp
(defvar *level* 7)
(symbol-value (intern "*level*")) ; => 7
```
