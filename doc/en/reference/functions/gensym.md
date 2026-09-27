# gensym

`(gensym [prefix])`

Returns a fresh symbol named `#:<prefix><n>`, where `prefix` defaults to `G` and `n` is a program-wide counter starting at 1. rontolisp has no uninterned symbols: the result is an ordinary symbol whose uniqueness rests on the `#:` prefix (which no user-written name normally carries) and the ever-increasing counter. Its main use is generating capture-safe temporaries inside [`defmacro`](../special-forms/defmacro.md) bodies, replacing the older `__`-prefixed naming convention.

Deviations from Common Lisp: the prefix must be a **literal** string on the compilation path (JVM/WASM), so the symbol text is known at compile time — a computed prefix is a compile error (the interpreter accepts any string). There is no `*gensym-counter*` variable, and because the symbol is interned like any other, `read`ing the same printed name twice yields `eq` symbols.

The optional argument may also be a non-negative integer: it is used as the suffix directly, under the default `G` prefix, and does **not** advance the counter — the next plain `(gensym)` still gets the number it would have gotten anyway.

```lisp
(list (gensym) (gensym)) ; => (#:G1 #:G2)
```

```lisp
(gensym "tmp") ; => #:|tmp3|
```

```lisp
(gensym 5) ; => #:G5
```

```lisp
(eq (gensym) (gensym)) ; => NIL
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
