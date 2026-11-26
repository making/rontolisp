# rontolisp:alist-hash-table

`(rontolisp:alist-hash-table alist &rest hash-table-initargs)`

Builds a hash table from an association list — each `(key . value)` cons becomes
an entry, and the first occurrence of a key wins — passing any trailing
arguments on to `make-hash-table`. A lightweight subset of
`alexandria:alist-hash-table`, so a program can switch to alexandria unchanged.
It pairs with [`rontolisp:json-stringify`](rontolisp-json-stringify.md) for
turning an alist (like a
[`rontolisp:query-params`](rontolisp-query-params.md) result or the request
headers) into a JSON object.

```lisp
(rontolisp:json-stringify (rontolisp:alist-hash-table '(("n" . 1))))   ; => "{\"n\":1}"
```

The default hash-table test is `eql`, like `alexandria:alist-hash-table`; pass
`:test 'equal` for string keys that should dedup by content:

```lisp
(hash-table-count (rontolisp:alist-hash-table '(("a" . 1) ("a" . 2)) :test 'equal))   ; => 1
```

The inverse is [`rontolisp:hash-table-alist`](rontolisp-hash-table-alist.md).

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the function
is written in rontolisp itself (part of the prelude) and is compiled into the
program when used.
