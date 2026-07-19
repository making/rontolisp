# rontolisp:json-stringify

`(rontolisp:json-stringify value)`

Serializes a Lisp value into a JSON document string, following the defaults of
the [`com.inuoe.jzon`](../../guides/asdf-systems.md) library and inverting
[`rontolisp:json-parse`](rontolisp-json-parse.md): a hash table becomes an
object, a vector or list an array, and `nil`, `t` and the symbol `null` become
`false`, `true` and `null`. It is a lightweight subset of jzon, so a program can
switch to jzon without changing shape.

Switch to `com.inuoe.jzon` when you outgrow the subset: for its richer features
(pretty-printing, a streaming writer, a `:replacer`, custom serialization), or
to make the JSON code portable to other Common Lisp implementations —
`com.inuoe.jzon` is a standard library, while `rontolisp:json-*` runs only on
rontolisp.

```lisp
(rontolisp:json-stringify (vector 1 2 3))   ; => "[1,2,3]"
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],false]"
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "name" h) "rontolisp")
  (rontolisp:json-stringify h))   ; => "{"name":"rontolisp"}"
```

## Value mapping

| Lisp | JSON |
|------|------|
| `nil` | `false` |
| `t` | `true` |
| the symbol `null` | `null` |
| integer, float | number |
| ratio | number (converted with `float`) |
| string | string (quote, backslash and control characters are escaped) |
| vector, list | array |
| hash table | object (a symbol key is down-cased unless it has a lower-case letter) |
| CLOS instance (`standard-object`) | object (each slot name → its value, in definition order) |
| keyword, symbol, character | string |

Anything else (functions, streams, multidimensional arrays) signals an error.

A hash table and a CLOS instance both serialize as objects, so there are two
ways to build one — a hash table (often via
[`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md)) for dynamic keys,
and a class for a fixed shape. A slot may itself hold a hash table (a nested
object), a list or vector (an array), or another instance:

```lisp
(defclass response () ((status :initarg :status) (body :initarg :body)))
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "content-type" h) "text/plain")
  (rontolisp:json-stringify (make-instance 'response :status 200 :body h)))   ; => "{"status":200,"body":{"content-type":"text/plain"}}"
```

```lisp
(rontolisp:json-stringify :key)   ; => ""key""
(rontolisp:json-stringify 3/2)   ; => "1.5"
(rontolisp:json-stringify "a\"b")   ; => ""a\"b""
```

A value parsed from JSON round-trips structurally:

```lisp
(rontolisp:json-stringify
 (rontolisp:json-parse "{\"deep\": {\"list\": [{\"k\": \"v\"}, 2.5, true]}}"))   ; => "{"deep":{"list":[{"k":"v"},2.5,true]}}"
```

## Limitations

- `nil` serializes as `false` and the empty list is `nil`, so use `#()` (an
  empty vector) for an empty array and an empty hash table for an empty object
  `{}`.
- A list is always an array — build a hash table for a JSON object (jzon dropped
  alist/plist detection, and so does this subset).
  [`rontolisp:plist-hash-table`](rontolisp-plist-hash-table.md) turns a keyword
  property list, and [`rontolisp:alist-hash-table`](rontolisp-alist-hash-table.md)
  an association list, into that hash table.
- Hash-table key order in the output is backend-specific (unspecified), like
  `maphash`.
- Non-ASCII characters are emitted verbatim (never `\uXXXX`-escaped), which is
  valid JSON.
- On the WASM backends a float with magnitude 2³¹ or larger cannot be
  serialized (the float formatter traps); see the
  [WASM guide](../../compiling/wasm.md).

## Backend support

Works on every backend and in every WASM mode (Preview 1 included), like
[`rontolisp:json-parse`](rontolisp-json-parse.md): the serializer is written
in rontolisp itself and is compiled into the program when used.
