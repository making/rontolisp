# rontolisp:hash-table-plist

`(rontolisp:hash-table-plist table)`

Returns a property list of the hash table's key/value pairs — the inverse of
[`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md). A lightweight
subset of `alexandria:hash-table-plist`.

```lisp
(rontolisp:hash-table-plist (rontolisp:plist-hash-table (list :a 1)))   ; => (:A 1)
```

The pair order follows the table's iteration order (backend-specific, like
`maphash`), so it is well defined for a single-entry table; for a
[`rontolisp:json-parse`](rontolisp-json-parse.md) object the keys are strings,
which `getf` cannot look up (it compares with `eq`), so read those with
`gethash` instead.

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the function
is written in rontolisp itself (part of the prelude) and is compiled into the
program when used.
