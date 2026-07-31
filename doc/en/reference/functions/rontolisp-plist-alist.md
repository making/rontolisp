# rontolisp:plist-alist

`(rontolisp:plist-alist plist)`

Returns an association list holding the same keys and values as the property
list `plist` — the odd elements are keys, the even elements values — in the same
order. The inverse of [`rontolisp:alist-plist`](rontolisp-alist-plist.md), and a
lightweight subset of `alexandria:plist-alist`, so a program can switch to
alexandria unchanged.

```lisp
(rontolisp:plist-alist '(:a 1 :b 2))   ; => ((:A . 1) (:B . 2))
```

Unlike [`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md) there is no
hash table in between, so the order is the input's — deterministic on every
backend — and duplicate keys are kept rather than collapsed:

```lisp
(rontolisp:plist-alist '(:a 1 :a 9))   ; => ((:A . 1) (:A . 9))
```

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the function
is written in rontolisp itself (part of the prelude) and is compiled into the
program when used.
