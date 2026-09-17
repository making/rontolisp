# Scheme（実験的）

**実験的機能です。** rontolisp は R7RS-small の一部 -- `(scheme base)`、
`(scheme write)`、`(scheme inexact)`、`(scheme cxr)`、`(scheme lazy)`、`(scheme process-context)` の `exit`、
`(scheme eval)`、`(scheme repl)`
-- を、Scheme プログラムを全バックエンドで動かせる最小限の範囲で読みます。
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
値は `write` の表記でエコーされます。定義は何もエコーせず、未規定値 -- `display`、`set!`、
`for-each`、どの分岐も選ばれなかった `if` が返すもの。自作の手続きの末尾がそれらでも同じ --
もエコーしません。フォームは複数行にまたがれます。エラーはスタックオーバーフローも含めて
報告され、定義を保ったままセッションが続きます。`(exit)` で終了します。
入力をパイプで与えると、[Common Lisp の REPL](../getting-started/repl.md) と同じく
スクリプト実行器になります: プロンプトを出さず、エラーは標準エラーへ、失敗したフォームが
あれば終了ステータスは 1 です。

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
scheme> (define (show x) (display x) (newline))
scheme> (show 'done)
done
scheme> (exit)
```

これら 8 ライブラリがエクスポートする名前 -- それに加えて、後述の
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
  `let-values`、`let*-values`、`define-record-type`（トップレベルのみ）、`delay`、
  `delay-force`、および
  `(import (scheme base) (scheme write) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))`（`only` / `except` /
  `prefix` / `rename` 可）。
- **手続き**: `eq? eqv? equal?`; `+ - * / = < > <= >= quotient remainder modulo
  floor-quotient floor-remainder truncate-quotient truncate-remainder abs min max gcd lcm
  expt square floor ceiling round truncate zero? positive? negative? odd? even? number?
  real? rational? integer? exact? inexact? exact-integer? exact inexact exact-integer-sqrt
  number->string string->number`; `(scheme inexact)`: `sqrt exp log sin cos tan asin acos
  atan finite? infinite? nan?`; `(scheme lazy)`: `force make-promise promise?`; `not boolean?`; `cons car cdr set-car! set-cdr! caar cadr
  cdar cddr list length append reverse list-tail list-ref list-copy memq memv member assq
  assv assoc null? pair? list?`; `symbol? symbol->string string->symbol`; `char? char->integer integer->char
  char=? char<? char>? char<=? char>=?`; `string? make-string string string-length
  string-ref string-set! string=? string<? string>? string<=? string>=? substring
  string-append string-copy string->list list->string`; `vector? make-vector vector
  vector-length vector-ref vector-set! vector->list list->vector vector-fill!`;
  `procedure? apply map for-each call/cc call-with-current-continuation dynamic-wind
  values call-with-values error`; `display write newline write-char write-string`
  （現在の出力ポートのみ）; `exit emergency-exit`（`#t` または引数なしはステータス 0、
  `#f` は 1、整数はその下位 8 ビット）; `(scheme eval)`: `eval environment`;
  `(scheme repl)`: `interaction-environment`。`write` と `display` は循環するリストやベクタを
  データラベル付きで
  `#0=(a b c . #0#)` のように書きます。循環のない共有構造は出現のたびに書き出します。
- **SICP 互換、R7RS ではない**: `true false nil`（リテラルではなく普通の変数）、
  `user-initial-environment system-global-environment` と R5RS の
  `scheme-report-environment`（どれも唯一の大域環境を指す。後述の `eval` を参照）、
  `filter reduce fold-left fold-right delete last-pair append! list-index 1+ -1+ random
  runtime parallel-execute test-and-set!`、ストリーム: `cons-stream`（構文）、
  `the-empty-stream stream-car stream-cdr
  stream-first stream-rest stream-pair? stream-null? empty-stream? stream list->stream
  stream->list stream-head stream-tail stream-ref stream-map stream-for-each stream-filter
  stream-append`。ストリームは `'()` か、cdr がプロミスであるペアなので、
  `the-empty-stream` は `'()`、`stream-null?` は `null?` です。これらは `(import ...)` を
  一切書かないプログラムでのみ見える -- 6 ライブラリと同じ扱いだが、どの import もこれらを
  名指しできないため、明示的な import リストがあると届かない。

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

`parallel-execute` はインタプリタと JVM では各サンクをそれぞれのスレッドで実行し、すべてが
終わってから戻ります。サンク内のエラーはそのときに通知されます。WebAssembly にはスレッドが
ないため、サンクは引数の順に一つずつ実行されます。これはスレッド実行でも起こりうる実行順序の
一つで、`test-and-set!` の上に作ったシリアライザが待たされることはありません。`test-and-set!`
はどのバックエンドでもアトミックです。

```scheme
(define cell (list false))
(display (list (test-and-set! cell) (test-and-set! cell))) (newline)
(define finished '())
(define (finish name) (lambda () (set! finished (cons name finished))))
(parallel-execute (finish 'only))
(display finished) (newline)
```

```
(#f #t)
(only)
```

プロミスは一度だけ評価され、その値を覚えています。ストリームをたどると各セルは一度だけ評価されます。

```scheme
(define (integers-from n) (cons-stream n (integers-from (+ n 1))))
(define (sieve s)
  (cons-stream (stream-car s)
               (sieve (stream-filter (lambda (x) (not (= 0 (remainder x (stream-car s)))))
                                     (stream-cdr s)))))
(display (stream-head (sieve (integers-from 2)) 10)) (newline)
(define p (delay (begin (display "once ") 42)))
(display (list (force p) (force p))) (newline)
```

```
(2 3 5 7 11 13 17 19 23 29)
once (42 42)
```

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

## eval

`(eval datum env)` はデータを実行時に評価します。どのバックエンドでも動きます。環境指定子は
どれも唯一の大域環境です: `(interaction-environment)`、`(scheme-report-environment 5)`、
`(environment '(scheme base) ...)` -- その import 集合は上記のライブラリに照らして検査されます --
および MIT Scheme の `user-initial-environment` と `system-global-environment` はすべて
これを指し、引数は省略できます。大域環境が持つのは、プログラムが定義したもの、`eval` 自身が
定義したもの、組み込み手続きで、この順に探されます。`eval` の中の `define` は後の `eval`
からだけ見え、プログラムの変数への `set!` は `eval` 自身のコピーを変えます: プログラムは自分の
値を読み続けます。

```scheme
(define (execute exp) (apply (eval (car exp) user-initial-environment) (cdr exp)))
(display (execute '(> 5 3))) (newline)
(eval '(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (interaction-environment))
(display (list (eval '(fact 10) (interaction-environment))
               (eval '(let loop ((i 0)) (if (= i 100000) i (loop (+ i 1))))
                     (interaction-environment))))
(newline)
```

```
#t
(3628800 100000)
```

`eval` の中では、名前付き `let`、`do`、自分自身を呼ぶ手続きは一定のスタックで動きます。
それ以外の呼び出しはスタックを消費します。`define-record-type`、`define-values`、
`let-values`、`import`、およびリーダーが拒否する構文は `eval` の中でも名前を挙げて拒否されます。
コンパイルされたプログラムの `eval` が組み込み手続きを解決できるのは、プログラムがその名前を
どこかに綴っている場合 -- シンボルとして（クォートされたデータを含む）、または文字列の中に --
だけです。インタプリタはすべてを解決します。

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
- 第一級の `values` -- `(apply values '(1 2))`、変数経由の `values`、`eval` の中の `values`
  -- は、コンパイルされたバックエンドでは最初の値だけを返します。インタプリタはすべてを
  返します。呼び出しとして書いた `(values 1 2)` はどこでもすべてを返します。
- 捕捉されない `error` は、メッセージと irritant を表示してプログラムを終了します。
  捕捉する `guard` はありません。
- レコードは Common Lisp の `#S(...)` 構文で表示されます。`equal?` はレコードを同一性で
  比較します。
- `write` は `'x` を `(quote x)` と、未規定値を `#!unspecific` と表示します。未規定値は
  1 つのオブジェクトで、条件としては真です。
- `exit` は `emergency-exit` と同じくその場でプロセスを終了します: 囲んでいる
  `dynamic-wind` の `after` は実行されません。
- 複素数はありません: `(sqrt -4)`、`(log -1)`、`(asin 2)` は手続き名を挙げたエラーで
  プログラムを終了します。
- エラーメッセージには Common Lisp の名前（`CAR`）が出ます。
- **未対応**: `define-syntax` / `syntax-rules`、`define-library`、`guard` / `raise`、
  `parameterize`、`case-lambda`、バイトベクタ、現在の出力ポート以外のポート、
  `(scheme char)` などのライブラリ、`|...|` 識別子、
  `+inf.0` / `+nan.0` の読み取り。構文に関するものは、ファイルを読む時点で名前を挙げて拒否されます。

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
