# rontolisp:plist-hash-table

`(rontolisp:plist-hash-table plist &rest hash-table-initargs)`

Builds a hash table from a property list — the odd elements are keys, the even
elements values — passing any trailing arguments on to `make-hash-table`. A
lightweight subset of `alexandria:plist-hash-table`, so a program can switch to
alexandria unchanged. It pairs with
[`rontolisp:json-stringify`](rontolisp-json-stringify.md) for building JSON
objects: keyword keys are down-cased, so `:name` becomes `"name"`.

```lisp
(rontolisp:json-stringify (rontolisp:plist-hash-table (list :name "rontolisp")))   ; => "{"name":"rontolisp"}"
```

Objects with several keys work the same way (the key order in the JSON output is
backend-specific, like `maphash`); the table is a real hash table, so its values
read back with `gethash`:

```lisp
(gethash :ok (rontolisp:plist-hash-table (list :name "x" :ok t)))   ; => T
```

The default hash-table test is `eql`, like `alexandria:plist-hash-table`; pass
`:test 'equal` (or any `make-hash-table` argument) to change it:

```lisp
(gethash "k" (rontolisp:plist-hash-table (list "k" 9) :test 'equal))   ; => 9
```

The inverse is [`rontolisp:hash-table-plist`](rontolisp-hash-table-plist.md).

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the function
is written in rontolisp itself (part of the prelude) and is compiled into the
program when used.
