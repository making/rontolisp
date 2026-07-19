# rontolisp:hash-table-alist

`(rontolisp:hash-table-alist table)`

Returns an association list of the hash table's key/value pairs — the inverse of
[`rontolisp:alist-hash-table`](rontolisp-alist-hash-table.md). A lightweight
subset of `alexandria:hash-table-alist`.

```lisp
(rontolisp:hash-table-alist (rontolisp:alist-hash-table (list (cons "k" 7))))   ; => (("k" . 7))
```

The pair order follows the table's iteration order (backend-specific, like
`maphash`), so it is well defined for a single-entry table.

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the function
is written in rontolisp itself (part of the prelude) and is compiled into the
program when used.
