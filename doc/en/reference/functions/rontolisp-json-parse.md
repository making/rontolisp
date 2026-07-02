# rontolisp:json-parse

`(rontolisp:json-parse string &optional representation)`

Parses a JSON document string into Lisp values, modeled on JavaScript's
`JSON.parse`. By default a JSON object becomes a property list whose keys are
keywords, so the result reads with `getf` — the same shape as a
[`rontolisp:fetch`](rontolisp-fetch.md) result. Passing `:hash-table` as the
second argument returns hash tables instead, with the object keys kept as
strings (the representation applies to nested objects too).

```lisp
(rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}")   ; => (:name "rontolisp" :n 2)
(getf (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}") :a)   ; => (:b (1 t nil))
(let ((h (rontolisp:json-parse "{\"content-type\": \"text/html\"}" :hash-table)))
  (gethash "content-type" h))   ; => "text/html"
```

## Value mapping

| JSON | Lisp |
|------|------|
| object | plist with keyword keys (default) or hash table with string keys (`:hash-table`) |
| array | list |
| string | string (`\uXXXX` escapes and surrogate pairs are decoded) |
| number | integer, or float when it has a fraction, an exponent or more than 9 digits |
| `true` | `t` |
| `false` | `nil` |
| `null` | `nil` |

```lisp
(rontolisp:json-parse "[1, 2.5, \"x\", false, null]")   ; => (1 2.5 "x" nil nil)
(rontolisp:json-parse "1e3")   ; => 1000.0
(rontolisp:json-parse "\"a\\u3042b\"")   ; => "aあb"
```

Integers wider than 9 digits become floats on every backend, keeping the value
inside the WASM backend's `i31` integer range (a 13-digit millisecond
timestamp parses as `1.234567890123E12` everywhere):

```lisp
(floatp (rontolisp:json-parse "1234567890123"))   ; => t
```

## Errors

Invalid JSON, trailing characters after the value, and an unknown
representation argument signal an error when `json-parse` is called:

```console
> (rontolisp:json-parse "{\"a\": ")
Error: json-parse: unexpected end of input
> (rontolisp:json-parse "1" :alist)
Error: json-parse: the object representation must be :plist or :hash-table
```

In the plist representation an object key must read back as a single keyword;
a key containing spaces or other symbol-breaking characters signals an error
suggesting `:hash-table`, where any key string works:

```lisp
(let ((h (rontolisp:json-parse "{\"a b\": 1}" :hash-table)))
  (gethash "a b" h))   ; => 1
```

## Limitations

- `{}`, `false` and `null` all parse to `nil` in the plist representation (an
  empty JSON array `[]` is the empty list, which is also `nil`). Use
  `:hash-table` when an empty object must stay distinguishable.
- `nil`, `t` and keywords are also valid plist values, so a parsed plist is
  unambiguous only as long as the document's shape is known — like
  JavaScript objects, JSON round-trips by shape, not by type.
- On the WASM backends a float with magnitude 2³¹ or larger parses correctly
  but cannot be *printed* (`print`/`princ-to-string` trap); see the
  [WASM guide](../../compiling/wasm.md).

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
parser is written in rontolisp itself and is compiled into the program when
used. The typical use is parsing a [`rontolisp:fetch`](rontolisp-fetch.md)
response body:

```console
(print (getf (rontolisp:json-parse
              (getf (rontolisp:await (rontolisp:fetch "https://httpbin.org/get")) :body))
             :url))   ; "https://httpbin.org/get"
```

The inverse operation is [`rontolisp:json-stringify`](rontolisp-json-stringify.md).
