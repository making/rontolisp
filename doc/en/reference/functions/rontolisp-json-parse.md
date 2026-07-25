# rontolisp:json-parse

`(rontolisp:json-parse string)`

Parses a JSON document string into Lisp values, following the defaults of the
[`com.inuoe.jzon`](../../guides/asdf-systems.md) library: a JSON object becomes a
hash table with string keys, an array a vector, and `true`/`false`/`null` become
`t`, `nil` and the symbol `null`. `rontolisp:json-parse` is a lightweight subset
of jzon, so a program can start here and later switch to jzon without changing
shape — with a single deliberate exception, the wide-integer rule
[noted below](#the-one-incompatibility-with-jzon).

Switch to `com.inuoe.jzon` when you outgrow the subset: for its richer features
(pretty-printing, a streaming writer, a `:replacer`, custom serialization), or
to make the JSON code portable to other Common Lisp implementations —
`com.inuoe.jzon` is a standard library, while `rontolisp:json-*` runs only on
rontolisp.

```lisp
(gethash "name" (rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}"))   ; => "rontolisp"
(gethash "b" (gethash "a" (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}")))   ; => #(1 T NULL)
```

## Value mapping

| JSON | Lisp |
|------|------|
| object | hash table with string keys (`equal` test) |
| array | vector |
| string | string (`\uXXXX` escapes and surrogate pairs are decoded) |
| number | integer, or float when it has a fraction, an exponent or more than 18 digits |
| `true` | `t` |
| `false` | `nil` |
| `null` | the symbol `null` |

```lisp
(rontolisp:json-parse "[1, 2.5, \"x\", false, null]")   ; => #(1 2.5 "x" nil null)
(rontolisp:json-parse "1e3")   ; => 1000.0
(rontolisp:json-parse "\"a\\u3042b\"")   ; => "aあb"
```

### The one incompatibility with jzon

Integers wider than 18 digits become floats on every backend -- a shared
library rule that keeps the parse identical across all backends. jzon instead keeps them as exact integers
of any width, so this is the single point where `rontolisp:json-parse` and
`jzon:parse` disagree — a 13-digit millisecond timestamp parses exactly on
both, but a 19-digit integer parses as a float here and as an exact integer
under jzon. Everything else round-trips identically.

```lisp
(rontolisp:json-parse "1234567890123")   ; => 1234567890123
(floatp (rontolisp:json-parse "1234567890123456789"))   ; => T
```

## Errors

Invalid JSON and trailing characters after the value signal an error when
`json-parse` is called:

```console
> (rontolisp:json-parse "{\"a\": ")
Error: json-parse: unexpected end of input
> (rontolisp:json-parse "1 2")
Error: json-parse: unexpected trailing characters
```

## Limitations

- A JSON object always parses to a hash table, so `{}` (an empty hash table) is
  distinct from `false`/`nil`, from an empty array `#()`, and from the `null`
  symbol — unlike JavaScript, the four are never conflated.
- On the WASM backends a float with magnitude 2³¹ or larger parses correctly
  but cannot be *printed* (`print`/`princ-to-string` trap); see the
  [WASM guide](../../compiling/wasm.md).

## Backend support

Works on every backend and in every WASM mode (Preview 1 included): the
parser is written in rontolisp itself and is compiled into the program when
used. The typical use is parsing a [`rontolisp:fetch`](rontolisp-fetch.md)
response body:

```console
(print (gethash "url"
                (rontolisp:json-parse
                 (getf (rontolisp:await (rontolisp:fetch "https://httpbin.ik.am/get")) :body))))   ; "https://httpbin.ik.am/get"
```

The inverse operation is [`rontolisp:json-stringify`](rontolisp-json-stringify.md).
