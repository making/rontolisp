# ライブラリ

ファイルが `import` できる 11 の R7RS ライブラリと、それぞれが提供するものです。
名前ごとのページは[リファレンス](reference.md)にあります。`(import ...)` で始まるファイル
（[構文](syntax.md)参照）は、名指ししたライブラリだけを見ます。`import` を一切書かない
ファイルは、11 すべてに加えて
[*Structure and Interpretation of Computer Programs*（SICP）互換の名前](sicp.md)も見えます。

| ライブラリ | 提供するもの |
|---|---|
| [(scheme base)](reference/library-base.md) | 中核部分: 数値、真偽値、ペアとリスト、シンボル、文字、文字列、ベクタ、バイトベクタ、制御、例外、ポート（文字列ポート、バイトベクタポート、現在のポート）、入出力。構文は[構文](reference/syntax.md)にあります |
| [(scheme write)](reference/library-write.md) | `display` と `write` |
| [(scheme read)](reference/library-read.md) | `read` |
| [(scheme char)](reference/library-char.md) | Unicode に基づく文字の分類、大文字・小文字の変換、大文字と小文字を区別しない比較 |
| [(scheme inexact)](reference/library-inexact.md) | 超越関数と浮動小数点数の述語 |
| [(scheme cxr)](reference/library-cxr.md) | 3 段と 4 段の `car`/`cdr` の合成 |
| [(scheme lazy)](reference/library-lazy.md) | プロミス |
| [(scheme case-lambda)](reference/library-case-lambda.md) | `case-lambda` |
| [(scheme process-context)](reference/library-process-context.md) | `exit` と `emergency-exit` のみ |
| [(scheme eval)](reference/library-eval.md) | `eval` と `environment`。[eval](eval.md) を参照 |
| [(scheme repl)](reference/library-repl.md) | `interaction-environment` |

入出力の手続きはどれも省略可能なポート引数を取り、省くと現在のポートを使います。現在の
ポートはパラメータオブジェクトなので、`parameterize` で差し替えられます。ファイルポートは
ありません。

```scheme
(define out (open-output-string))
(parameterize ((current-output-port out))
  (display "captured ")
  (write '(1 "two")))
(write (get-output-string out)) (newline)

(define in (open-input-string "(a b) 42"))
(write (list (read in) (read in) (eof-object? (read in)))) (newline)
```

```
"captured (1 \"two\")"
((a b) 42 #t)
```

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

## 自分のライブラリ

プログラムは [define-library](reference/define-library.md) でライブラリに分けられます:
ファイルの先頭、その `import` より前に書くか、専用のファイルに置けば
`(import (shapes circle))` がプログラムと同じ場所の `shapes/circle.sld` として見つけます。
ライブラリの名前は、エクスポートしない限りライブラリの中に閉じます。
[include](reference/include.md) はファイルの内容をその位置に置きます。どちらもプログラムと
同時に読まれるので、コンパイルしたプログラムは実行時にそれらのファイルを必要としません。

```scheme
; file: shapes/circle.sld
(define-library (shapes circle)
  (export area)
  (import (scheme base))
  (begin
    (define pi 314/100)
    (define (area r) (* pi r r))))
```

```scheme
(import (scheme base) (scheme write) (shapes circle))
(define pi 3)
(write (list (area 10) pi))
(newline)
```

```
(314 3)
```

## 機能

[cond-expand](reference/cond-expand.md) は、処理系の[機能](reference/features.md)と
処理系にあるライブラリによって、プログラムを読む時点でコードを選びます: 複数の Scheme
処理系向けに書いたプログラムがここで動き、選ばれなかった分岐はコンパイルされません。

```scheme
(import (scheme base) (scheme write))
(cond-expand
  (gauche (define (implementation) "Gauche"))
  (rontolisp (define (implementation) "rontolisp"))
  (else (define (implementation) "some Scheme")))
(display (implementation))
(newline)
```

```
rontolisp
```
