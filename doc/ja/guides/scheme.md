# Scheme（実験的）

**実験的機能です。** rontolisp は R7RS-small の一部 -- `(scheme base)` と
`(scheme write)` -- を、Scheme プログラムを全バックエンドで動かせる最小限の範囲で読みます。
準拠は意図的に部分的で、互換性の約束はありません。Scheme プログラムを JVM や WebAssembly で
試す用途に使い、動き続けてほしいものは Common Lisp で書いてください。

`.scm` ファイルは Scheme として読まれます。それ以外のファイルは `--source-language scheme`
で指定します。言語はファイル単位で決まるので、1 つのプログラムに両方を混在できます。

```bash
rontolisp hello.scm                                # interpreter
rontolisp hello.scm -o Hello.class && java Hello   # JVM
rontolisp hello.scm -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.scm -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language scheme        # any extension
```

`--no-gc` は拒否されます。このバックエンドにはペア・シンボル・クロージャがありません。

```scheme
(import (scheme base) (scheme write))

(define (count-up n)
  (let loop ((i 0) (acc '()))
    (if (= i n)
        (reverse acc)
        (loop (+ i 1) (cons (* i i) acc)))))

(define-record-type point (make-point x y) point? (x point-x) (y point-y))

(display (count-up 5)) (newline)
(write (list (point-x (make-point 3 4)) (if '() 'true 'false) #f "s")) (newline)
```

```
(0 1 4 9 16)
(3 true #f "s")
```

## REPL

ファイルを指定せずに `--source-language scheme` を付けると Scheme の REPL が起動します。
値は `write` の表記でエコーされます。定義、`set!`、副作用のために呼ぶ手続き（`display`）は
何もエコーしません。フォームは複数行にまたがれます。

```console
$ rontolisp --source-language scheme
scheme> (define (square x) (* x x))
scheme> (map square '(1 2 3))
(1 4 9)
scheme> (set! square -)
scheme> (square 5)
-5
scheme> (list #t #f '() 'Sym)
(#t #f () Sym)
scheme> (quit)
```

`(scheme base)` と `(scheme write)` がエクスポートする名前 -- それに加えて、後述の
どの `(import ...)` にも属さない SICP 互換名 -- は最初からすべて見えており、
プロンプトで入力した `(import ...)` は名前を追加するだけです。別々のプロンプトで入力した
定義は、1 つのファイルに書いた場合と同じく、順序によらず互いを参照できます。フォームは
入力時点で確定するため、ファイルとの違いが 2 点あります: 組み込み手続き（`square`）を再定義しても、
それ以前に入力したフォームには及びません。また、末尾位置で自分自身を呼ぶ手続きは、後から `set!` で
置き換えても、保持されている古いコピーは自分自身へのループを続けます。

## 対応範囲

- **リーダー**（大文字小文字を区別）: `#t` `#f` `#true` `#false`、整数、小数、有理数、
  `#x` `#b` `#o` `#d`、`#\a` `#\space` `#\newline` `#\x41`、
  `\n \t \" \\ \xHH;` を含む文字列、`#( )` ベクタ、ドット対、`'` `` ` `` `,` `,@`、`;`、`#;`、
  `#| |#`。
- **構文**: `define`（両形式。内部定義は `letrec*`）、`define-values`、
  `lambda`、`if`、`cond`（`else`、`=>`）、`case`、`and`、`or`、`when`、`unless`、`let`、
  `let*`、`letrec`、`letrec*`、名前付き `let`、`do`、`begin`、`set!`、`quote`、`quasiquote`、
  `let-values`、`let*-values`、`define-record-type`（トップレベルのみ）、および
  `(import (scheme base) (scheme write))`（`only` / `except` / `prefix` / `rename` 可）。
- **手続き**: `eq? eqv? equal?`; `+ - * / = < > <= >= quotient remainder modulo
  floor-quotient floor-remainder truncate-quotient truncate-remainder abs min max gcd lcm
  expt square floor ceiling round truncate zero? positive? negative? odd? even? number?
  real? rational? integer? exact? inexact? exact-integer? exact inexact number->string
  string->number`; `not boolean?`; `cons car cdr set-car! set-cdr! caar cadr cdar cddr list
  length append reverse list-tail list-ref list-copy memq memv member assq assv assoc null?
  pair? list?`; `symbol? symbol->string string->symbol`; `char? char->integer integer->char
  char=? char<? char>? char<=? char>=?`; `string? make-string string string-length
  string-ref string-set! string=? string<? string>? string<=? string>=? substring
  string-append string-copy string->list list->string`; `vector? make-vector vector
  vector-length vector-ref vector-set! vector->list list->vector vector-fill!`;
  `procedure? apply map for-each call/cc call-with-current-continuation dynamic-wind
  values call-with-values error`; `display write newline write-char write-string`
  （現在の出力ポートのみ）。
- **SICP 互換、R7RS ではない**: `true false nil`（リテラルではなく普通の変数）、
  `(scheme cxr)` 一式（`caaar` から `cddddr` まで）、`filter reduce fold-left fold-right
  delete last-pair append! list-index 1+ -1+ random runtime`。これらは `(import ...)` を
  一切書かないプログラムでのみ見える -- `(scheme base)` / `(scheme write)` と同じ扱いで、
  明示的な import リストがあるとこれらの名前には届かない。

```scheme
(display (list true false nil (cadddr '(1 2 3 4)))) (newline)
(display (filter odd? '(1 2 3 4 5))) (newline)
(display (fold-left cons '() '(1 2 3))) (newline)
```

```
(#t #f () 4)
(1 3 5)
(((() . 1) . 2) . 3)
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

## 仕様との差異

- **末尾呼び出しが真に末尾になるのはループに変換できる場合だけです**: 名前付き `let`、`do`、
  および末尾位置での自己呼び出し。相互再帰や高階の末尾呼び出しはスタックを消費します。
  互いを呼び合う 2 手続きは、JVM の既定スタックでは深さ 2,000 から 5,000 の間で、
  インタプリタと WebAssembly では 10,000 から 100,000 の間でオーバーフローします。
- **`call/cc` は脱出専用です。** 継続は、その `call/cc` の実行中に 1 回だけ呼べます。
  再突入はないため、ジェネレータやコルーチンは作れず、`dynamic-wind` の `before` は
  ちょうど 1 回だけ実行されます。
- `call-with-values` は、両引数が `lambda` 式として書かれているとき直接束縛になります。
  それ以外の形はリストを経由します。
- 捕捉されない `error` は、メッセージと irritant を表示してプログラムを終了します。
  捕捉する `guard` はありません。
- レコードは Common Lisp の `#S(...)` 構文で表示されます。`equal?` はレコードを同一性で
  比較します。
- `write` は `'x` を `(quote x)` と表示します。
- エラーメッセージには Common Lisp の名前（`CAR`）が出ます。
- **未対応**: `define-syntax` / `syntax-rules`、`define-library`、`guard` / `raise`、
  `parameterize`、`case-lambda`、`delay`、バイトベクタ、現在の出力ポート以外のポート、
  `eval`、`(scheme char)` などのライブラリ、`|...|` 識別子、
  `+inf.0` / `+nan.0`。構文に関するものは、ファイルを読む時点で名前を挙げて拒否されます。

## Common Lisp との混在

Scheme の識別子は大文字小文字を保つので、Common Lisp 側からはエスケープした名前で
Scheme の手続きを呼びます。1 回だけ定義され `set!` されないトップレベル手続きは、
通常の関数です。

```console
$ cat lib.scm
(define (twice x) (* 2 x))
$ cat main.lisp
(load "lib.scm")
(print (|twice| 21))
$ rontolisp main.lisp
42
```

この方向で注意が要る値は 3 つだけです: `'()` は `NIL`、`#t` は `T`、`#f` は独自の値です --
Common Lisp のコードに渡した `#f` はそこでは真であり、Scheme に返った `NIL` は空リスト、
つまり真です。
