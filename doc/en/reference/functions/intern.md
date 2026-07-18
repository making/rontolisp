# intern

`(intern string)`

Returns the symbol named by `string` (no case folding). rontolisp symbols compare by name — there is no separate intern table — so the result is `eq` to any symbol with the same name, including quoted literals. On the interpreter the name is interned into the **current package** (Common Lisp's `*package*` semantics): an accessible symbol keeps its home spelling, and an unknown name becomes a symbol of the package selected by `in-package` — which is what lets a macro-time `(intern (concatenate ...))` name the same function as a literal `defun` in that file. `(intern name :keyword)` builds a keyword. Deviations from Common Lisp: any other package argument signals an error, there is no second `status` value, and on the compiled backends a runtime `intern` call is package-blind (the name is used exactly as given).

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
