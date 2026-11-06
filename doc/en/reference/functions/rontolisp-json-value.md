# rontolisp:json-value

`(rontolisp:json-value document &rest path)` / `(rontolisp:json-value address size &rest path)`

Reads one value out of a JSON document: what
[`rontolisp:json-parse`](rontolisp-json-parse.md) answers for the value
[`rontolisp:json-span`](rontolisp-json-span.md) finds at the path, and `t` when
there is one. Only that value becomes Lisp values: the rest of the document is
checked and passed over, so a few fields of a large body cost what the scan
costs and the memory of those fields.

The document and the path are `json-span`'s: a string, an `(unsigned-byte 8)`
vector or, on the wasm-GC backends, an address and a size of linear memory; keys
and indexes. Porting a filter between the two forms is changing the first
arguments only.

```lisp
(rontolisp:json-value "{\"usage\": {\"input_tokens\": 12, \"output_tokens\": 34}}" "usage" "output_tokens")   ; => 34, T
(gethash "input_tokens" (rontolisp:json-value "{\"usage\": {\"input_tokens\": 12, \"output_tokens\": 34}}" "usage"))   ; => 12
(rontolisp:json-value "{\"usage\": null}" "usage")   ; => NULL, T
(rontolisp:json-value "{\"usage\": null}" "model")   ; => NIL, NIL
```

The second value tells a `false` (`nil`, `t`) from a value that is not there
(`nil`, `nil`), as `gethash`'s does.

## Errors

A document `json-span` refuses signals its error, `json-span: ...`; see
[its page](rontolisp-json-span.md#errors).

## Backend support

Works on every backend and in every WASM mode: `json-span`'s scan, then
`json-parse` over the value's bytes alone.
