# A JSON object's member order is documented as backend-specific, and every backend keeps insertion order

Difficulty: Low

Pages still say what a hash table's walk order no longer is. `maphash`'s page and
`.kb/hash-tables.md` say every backend walks the entries in the order their keys were first stored,
and `json-stringify` follows it: `json.lisp`'s `%json-out-hash` walks with `maphash`, and
`WasmJsonRuntimeBuilder` walks a table's entries in insertion order. `ci-spec.yaml` pins it on every
backend (`{"a":3,"b":2}`, and the `small` table's `{"a":8,"b":2,...,"h":9,"i":10}`). But:

- `doc/en/reference/functions/rontolisp-json-stringify.md` ("Hash-table key order in the output is
  backend-specific (unspecified), like `maphash`") and its `doc/ja` twin;
- `doc/en/reference/functions/rontolisp-plist-hash-table.md` ("the key order in the JSON output is
  backend-specific, like `maphash`") and its `doc/ja` twin;
- `rontolisp-alist-plist.md` and `rontolisp-plist-alist.md` (en and ja) contrast themselves with the
  hash-table pairs as "deterministic on every backend", which the hash-table pairs are too (newest
  first, the reverse of `maphash`'s order);
- `ci-spec.yaml`'s comments on `json-object-building-and-clos` ("Single-key hash objects keep the
  output backend-stable") and `alist-plist-conversions` ("multi-key output is backend-stable").

Found replacing a hand-written JSON-object helper with `plist-hash-table` in
[counter-api](https://github.com/categolj/counter-api-lisp), whose responses and log lines rely on
the members coming out in the plist's order.

## Goal

The pages say what `maphash`'s says: a hash table's members come out in the order its keys were
first stored, on every backend; `plist-hash-table`'s page shows a multi-key object in the plist's
order. `json-object-building-and-clos` builds its `plist-hash-table` and `alist-hash-table` objects
with more than one key, so the order the pages state is pinned on every backend, and the stale
comments go.

## Direction

- The four reference pages above, en and ja alike; `plist-hash-table`'s first example can become the
  multi-key one (`DocExamplesTest` asserts every `; =>`).
- `ci-spec.yaml`: `(list :msg "hi" :ok t)` and `(list (cons "x" 1) (cons "y" 2))` in
  `json-object-building-and-clos`, their expected lines, and the two comments.
- `with-hash-table-iterator`'s page says "The order is unspecified." It lowers to the same `maphash`
  walk (`.kb/hash-tables.md`), but no case pins its order with more than one key: decide whether it
  joins the others (with a case) or stays unspecified.
