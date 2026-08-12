# find-symbol

`(find-symbol string [package])`

Like [`intern`](intern.md) but never creates: returns the symbol when the name is already known to the image, nil otherwise. "Known" means a `cl` symbol (function, macro, or special form), a keyword, or a user definition. With a `package` designator the name is looked up in that package instead of the current one; a package that does not exist provides no symbol, so the answer is `nil` rather than the `package-error` Common Lisp signals — that keeps probes for optional systems (`(find-symbol "TIMESTAMP" :simple-date)`) working the same way on every backend.

Deviations from Common Lisp: on the compiled backends (JVM/WASM) only a **literal** string can answer `nil` — the check is folded at compile time against the compile-time view (cl symbols plus the program's own `defun`s), so runtime-defined variables and macros are not visible there (the interpreter checks the live image, including global variables and `defmacro` macros). A computed name is interned instead, so it always yields a symbol, and its status is read off the spelling that lowering builds (`:external` for a qualified one, `:internal` for a bare one) rather than from the image.

A second value reports the ANSI accessibility status of the name in that package — `:external`, `:inherited`, `:internal`, or `nil` when the package does not provide it, so the two values are `nil` together:

```lisp
(multiple-value-list (find-symbol "CAR" 'common-lisp)) ; => (CAR :EXTERNAL)
```

```lisp
(multiple-value-list (find-symbol "CAR")) ; => (CAR :INHERITED)
```

```lisp
(find-symbol "car") ; => NIL
```

```lisp
(find-symbol "cond") ; => NIL
```

```lisp
(find-symbol "no-such-name") ; => NIL
```

```lisp
(defun greet (n) n)
(find-symbol "greet") ; => NIL
```

```lisp
(find-symbol "TIMESTAMP" :simple-date) ; => NIL
```
