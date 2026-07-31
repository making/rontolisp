# rontolisp:alist-plist

`(rontolisp:alist-plist alist)`

Returns a property list holding the same keys and values as the association list
`alist`, in the same order — the inverse of
[`rontolisp:plist-alist`](rontolisp-plist-alist.md). A lightweight subset of
`alexandria:alist-plist`, so a program can switch to alexandria unchanged.

```lisp
(rontolisp:alist-plist '((:a . 1) (:b . 2)))   ; => (:A 1 :B 2)
```

Unlike [`rontolisp:hash-table-plist`](rontolisp-hash-table-plist.md) there is no
hash table in between, so the order is the input's — deterministic on every
backend — and duplicate keys are kept rather than collapsed. An empty list
returns `nil`.

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the function
is written in rontolisp itself (part of the prelude) and is compiled into the
program when used.
