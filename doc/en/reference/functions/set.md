# set

`(set symbol value)`

Sets the **global** variable named by `symbol` to `value`, creating the binding
when the name is unbound -- the computed-name counterpart of `setq`. Use
[`boundp`](boundp.md) to test first when the name may be new, and
[`intern`](intern.md) to build it at runtime. `(setf (symbol-value symbol)
value)` is the same store. An already-active dynamic binding is left alone on
every backend alike: `set` targets the global namespace, `setq` the current
dynamic binding. `nil`, `t`, keywords and other constants cannot be set, and a
non-symbol signals an error.

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
