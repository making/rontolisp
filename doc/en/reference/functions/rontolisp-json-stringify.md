# rontolisp:json-stringify

`(rontolisp:json-stringify value)`

Serializes a Lisp value into a JSON document string, modeled on JavaScript's
`JSON.stringify`. Both object representations produced by
[`rontolisp:json-parse`](rontolisp-json-parse.md) serialize back to JSON
objects: a property list whose keys are keywords, and a hash table (keys may
be strings, symbols, keywords or numbers).

```lisp
(rontolisp:json-stringify (list :name "rontolisp" :ok t :ver 1.5))   ; => "{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}"
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],null]"
(let ((h (make-hash-table)))
  (setf (gethash "x" h) (list 1 2))
  (rontolisp:json-stringify h))   ; => "{"x":[1,2]}"
```

## Value mapping

| Lisp | JSON |
|------|------|
| `nil` | `null` |
| `t` | `true` |
| integer, float | number |
| ratio | number (converted with `float`) |
| string | string (quote, backslash and control characters are escaped) |
| keyword, symbol, character | string (a keyword drops its leading colon) |
| non-empty list of alternating keyword/value pairs | object |
| any other list | array |
| hash table | object (iteration order is unspecified) |

Anything else (functions, streams, promises, arrays) signals an error.

```lisp
(rontolisp:json-stringify :key)   ; => "\"key\""
(rontolisp:json-stringify 3/2)   ; => "1.5"
(rontolisp:json-stringify "a\"b")   ; => ""a\"b""
```

A value parsed from JSON round-trips structurally:

```lisp
(rontolisp:json-stringify
 (rontolisp:json-parse "{\"deep\": {\"list\": [{\"k\": \"v\"}, 2.5, true]}}"))   ; => "{"deep":{"list":[{"k":"v"},2.5,true]}}"
```

## Limitations

- A list is serialized as an object exactly when it looks like a keyword
  property list (`(:a 1 :b 2)`); a genuine array whose elements alternate
  keywords and values is indistinguishable and becomes an object.
- `nil` always serializes as `null` — there is no way to produce an empty
  array `[]` or (from a plist) an empty object `{}`; build an empty hash table
  for the latter.
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
