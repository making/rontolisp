# gensym

`(gensym [prefix])`

Returns a fresh symbol named `#:<prefix><n>`, where `prefix` defaults to `g` and `n` is a program-wide counter starting at 1. rontolisp has no uninterned symbols: the result is an ordinary symbol whose uniqueness rests on the `#:` prefix (which no user-written name normally carries) and the ever-increasing counter. Its main use is generating capture-safe temporaries inside [`defmacro`](../special-forms/defmacro.md) bodies, replacing the older `__`-prefixed naming convention.

Deviations from Common Lisp: the prefix must be a **literal** string on the compilation path (JVM/WASM), so the symbol text is known at compile time — a computed prefix is a compile error (the interpreter accepts any string). There is no `*gensym-counter*` variable, and because the symbol is interned like any other, `read`ing the same printed name twice yields `eq` symbols.

```lisp
(list (gensym) (gensym)) ; => (#:g1 #:g2)
```

```lisp
(gensym "tmp") ; => #:tmp3
```

```lisp
(eq (gensym) (gensym)) ; => nil
```

A macro temporary generated with `gensym` cannot collide with the caller's variables:

```lisp
(defmacro swap! (a b)
  (let ((tmp (gensym)))
    `(let ((,tmp ,a)) (setq ,a ,b) (setq ,b ,tmp))))
(setq tmp 1)
(setq other 2)
(swap! tmp other)
(list tmp other) ; => (2 1)
```
