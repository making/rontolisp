# macro-function

`(macro-function symbol &optional environment)`

Lite stub: always returns `nil`. Macros are fully expanded at compile time, so no runtime macro table exists.

```lisp
(macro-function 'when) ; => NIL
```

The `setf` place is supported for one shape only: `(setf (macro-function 'new) (macro-function 'existing))` gives an existing `defmacro`-defined macro a second name sharing its expander, so both names expand identically from then on. Anything else -- an arbitrary expander function, or a name that is not a user macro -- signals an error, because there is no macro function object to store.

```lisp
(defmacro greet (x) `(list :hello ,x))
(setf (macro-function 'hi) (macro-function 'greet))
(hi "world") ; => (:HELLO "world")
```
