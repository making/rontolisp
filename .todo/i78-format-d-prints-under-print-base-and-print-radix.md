# i78. `format`'s `~D` prints under `*print-base*` and `*print-radix*`

Difficulty: Medium

Found 2026-11-06 while making json-stringify write integers in decimal (i76): on all four
backends

```lisp
(let ((*print-base* 16) (*print-radix* t)) (format nil "~D" 255))   ; => "#xFF", SBCL "255"
```

CLHS 22.3.2.2 prints a `~D` integer in decimal without a radix, and a non-integer as by `~A` in
decimal base. Both of rontolisp's paths print through `%princ-piece`, which honours the
variables: the compile-time lowering (`LispMacroExpander`, plain `~D` is `%princ-piece`, `~:D`
and `~@D` the `decimalIntegerExpr` digits) and the runtime renderer (`%fmt-dec` in
`src/main/resources/am/ik/rontolisp/macro/format-render.lisp`). `~B`/`~O`/`~X`/`~R` build their
own digits, so only their non-integer arm reads the variables.

Plan:
- Print `~D`'s integer independently of the variables on both paths, on all four backends.
- Do not bind or read them to do it: a program naming either routes its printing through the
  case-aware renderer (`.kb/pretty-printer.md`), 18 KB on a proxy-wasm filter. json.lisp's
  `%json-decimal` probes instead -- `(%princ-piece 10)` is `"10"` exactly when the printer is
  decimal without a radix -- and builds the digits itself otherwise (`.kb/json.md`).
- A ci-spec case beside `with-standard-io-syntax-binds-the-standard-table`.
