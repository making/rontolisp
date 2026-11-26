# set

`(set symbol value)`

Sets the variable named by `symbol` to `value` -- the computed-name counterpart
of `setq`. An active dynamic binding of the name (made by `let`, a parameter or
`progv`) takes the value, and is undone as usual when its extent ends; with none
active the global is set, and the binding is created when the name is unbound.
Use [`boundp`](boundp.md) to test first when the name may be new, and
[`intern`](intern.md) to build it at runtime. `(setf (symbol-value symbol)
value)` is the same store. `nil`, `t`, keywords and other constants cannot be
set, and a non-symbol signals an error.

```lisp
(defvar *level* 7)
(set '*level* 8)
*level* ; => 8
```

```lisp
(set (intern "*BONUS*") 3)
(symbol-value '*bonus*) ; => 3
```

```lisp
(setf (symbol-value '*level*) 9)
*level* ; => 9
```

```lisp
(defun bump (name) (set name 99))
(let ((*level* 1))
  (bump '*level*)
  *level*) ; => 99
*level* ; => 9
```
