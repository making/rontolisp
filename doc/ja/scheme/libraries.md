# ライブラリ

この実装が知っているすべての手続きを、それをエクスポートする R7RS ライブラリごとに
分類したものです。`(import ...)` で始まるファイル（[構文](syntax.md)参照）は、
名指ししたライブラリだけを見ます。`import` を一切書かないファイルは、9 つすべてに加えて
[SICP 互換の名前](sicp.md)も見えます。

## (scheme base)

| 分類 | 手続き |
|---|---|
| 同値性 | `eq?` `eqv?` `equal?` |
| 数値 | `+` `-` `*` `/` `=` `<` `>` `<=` `>=` `quotient` `remainder` `modulo` `floor-quotient` `floor-remainder` `truncate-quotient` `truncate-remainder` `abs` `min` `max` `gcd` `lcm` `expt` `square` `floor` `ceiling` `round` `truncate` `zero?` `positive?` `negative?` `odd?` `even?` `number?` `real?` `rational?` `integer?` `exact?` `inexact?` `exact-integer?` `exact` `inexact` `exact-integer-sqrt` `number->string` `string->number` |
| 真偽値 | `not` `boolean?` |
| ペアとリスト | `cons` `car` `cdr` `set-car!` `set-cdr!` `caar` `cadr` `cdar` `cddr` `list` `length` `append` `reverse` `list-tail` `list-ref` `list-copy` `memq` `memv` `member` `assq` `assv` `assoc` `null?` `pair?` `list?` |
| シンボル | `symbol?` `symbol->string` `string->symbol` |
| 文字 | `char?` `char->integer` `integer->char` `char=?` `char<?` `char>?` `char<=?` `char>=?` |
| 文字列 | `string?` `make-string` `string` `string-length` `string-ref` `string-set!` `string=?` `string<?` `string>?` `string<=?` `string>=?` `substring` `string-append` `string-copy` `string->list` `list->string` |
| ベクタ | `vector?` `make-vector` `vector` `vector-length` `vector-ref` `vector-set!` `vector->list` `list->vector` `vector-fill!` |
| 制御 | `procedure?` `apply` `map` `for-each` `call/cc` `call-with-current-continuation` `dynamic-wind` `values` `call-with-values` `error` |
| 出力（現在の出力ポートのみ） | `newline` `write-char` `write-string` |
| 入力（現在の入力ポートのみ、ポート引数なし） | `read-char` `peek-char` `read-line` `char-ready?` `eof-object` `eof-object?` |

```scheme
(define (sum-to n)
  (do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((> i n) sum)))
(display (sum-to 1000000)) (newline)

(define (first-even items)
  (call/cc (lambda (return)
    (for-each (lambda (x) (if (even? x) (return x))) items)
    #f)))
(display (first-even '(1 3 4 5))) (newline)

(call-with-values (lambda () (values 1 2)) (lambda (a b) (display (+ a b)) (newline)))
```

```
500000500000
4
3
```

## (scheme write)

| 手続き |
|---|
| `display` `write` |

どちらも現在の出力ポートのみに書き込み、循環するリストやベクタをデータラベル付きで
書きます -- 詳細は下の[表示](#printing)を参照してください。

## (scheme read)

| 手続き |
|---|
| `read` |

現在の入力ポートのみから読み込み、ポート引数は取りません。

## (scheme inexact)

| 手続き |
|---|
| `sqrt` `exp` `log` `sin` `cos` `tan` `asin` `acos` `atan` `finite?` `infinite?` `nan?` |

## (scheme cxr)

3 段・4 段の `car`/`cdr` の組み合わせすべて。`caar`/`cadr`/`cdar`/`cddr` は
`(scheme base)` に属します。24 個すべてが標準の Common Lisp 関数なので、
それぞれ同名の関数へ転送するだけです。

| 手続き |
|---|
| `caaar` `caadr` `cadar` `caddr` `cdaar` `cdadr` `cddar` `cdddr` |
| `caaaar` `caaadr` `caadar` `caaddr` `cadaar` `cadadr` `caddar` `cadddr` |
| `cdaaar` `cdaadr` `cdadar` `cdaddr` `cddaar` `cddadr` `cdddar` `cddddr` |

## (scheme lazy)

| 手続き |
|---|
| `force` `make-promise` `promise?` |

`delay` と `delay-force` は特殊形式です（[構文](syntax.md)参照）。このライブラリの
手続きではありません。

## (scheme process-context)

| 手続き |
|---|
| `exit` `emergency-exit` |

`#t` または引数なしはステータス 0、`#f` は 1、整数はその下位 8 ビットです。`exit` が
`dynamic-wind` とどう関わるかは[仕様との差異](deviations.md)を参照してください。

## (scheme eval)

| 手続き |
|---|
| `eval` `environment` |

詳細な意味は[eval](eval.md)を参照してください。

## (scheme repl)

| 手続き |
|---|
| `interaction-environment` |

[eval](eval.md)を参照してください。

## 表示

`write` と `display` は循環するリストやベクタをデータラベル付きで
`#0=(a b c . #0#)` のように書きます。循環のない共有構造は出現のたびに書き出します。

正確な引数に正確な答えがあれば結果も正確なままです。浮動小数点数は `1e21` 未満では
位取り表記で、それ以上（および `1e-6` 未満）では指数付きで表示されます:

```scheme
(write (list (sqrt 16) (sqrt 1/4) (sqrt 2) (exp 0) (atan 0 1))) (newline)
(write (list 123456789.123 1e21 0.000001 1.5e-7 (/ 1.0 0.0))) (newline)
```

```
(4 1/2 1.4142135623730951 1 0)
(123456789.123 1e21 0.000001 1.5e-7 +inf.0)
```
