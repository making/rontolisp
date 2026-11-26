# (scheme cxr)

3 段と 4 段の `car`/`cdr` の合成すべてです。2 段のもの（`caar`、`cadr`、`cdar`、`cddr`）は `(scheme base)` にあります。

| 名前 | 例 | 結果 |
|---|---|---|
| `caaar` | `(caaar '(((1 2) 3) 4))` | `1` |
| `caadr` | `(caadr '(1 (2 3) 4))` | `2` |
| `cadar` | `(cadar '((1 2) 3))` | `2` |
| `caddr` | `(caddr '(1 2 3 4))` | `3` |
| `cdaar` | `(cdaar '(((1 2) 3) 4))` | `(2)` |
| `cdadr` | `(cdadr '(1 (2 3) 4))` | `(3)` |
| `cddar` | `(cddar '((1 2 3) 4))` | `(3)` |
| `cdddr` | `(cdddr '(1 2 3 4))` | `(4)` |
| `caaaar` | `(caaaar '((((1 2))) 3))` | `1` |
| `caaadr` | `(caaadr '(0 ((1 2)) 3))` | `1` |
| `caadar` | `(caadar '((0 (1 2)) 3))` | `1` |
| `caaddr` | `(caaddr '(0 1 (2 3)))` | `2` |
| `cadaar` | `(cadaar '(((0 1 2)) 3))` | `1` |
| `cadadr` | `(cadadr '(0 (1 2 3)))` | `2` |
| `caddar` | `(caddar '((0 1 2 3)))` | `2` |
| `cadddr` | `(cadddr '(1 2 3 4 5))` | `4` |
| `cdaaar` | `(cdaaar '((((1 2 3)))))` | `(2 3)` |
| `cdaadr` | `(cdaadr '(0 ((1 2 3))))` | `(2 3)` |
| `cdadar` | `(cdadar '((0 (1 2 3))))` | `(2 3)` |
| `cdaddr` | `(cdaddr '(0 1 (2 3 4)))` | `(3 4)` |
| `cddaar` | `(cddaar '(((1 2 3 4))))` | `(3 4)` |
| `cddadr` | `(cddadr '(0 (1 2 3 4)))` | `(3 4)` |
| `cdddar` | `(cdddar '((1 2 3 4 5)))` | `(4 5)` |
| `cddddr` | `(cddddr '(1 2 3 4 5 6))` | `(5 6)` |
