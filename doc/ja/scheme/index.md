# Scheme（実験的）

**実験的機能です。** rontolisp は R7RS-small の一部 -- `(scheme base)`、
`(scheme write)`、`(scheme read)`、`(scheme char)`、`(scheme inexact)`、`(scheme cxr)`、`(scheme lazy)`、`(scheme case-lambda)`、`(scheme process-context)` の `exit`、
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

## このセクション

- [REPL](repl.md) -- 対話セッションとスクリプト実行モード。
- [規格](standards.md) -- `--scheme-standard`: この処理系の方言か、厳密な R7RS か。
- [構文](syntax.md) -- リーダー、特殊形式、`import`。
- [ライブラリ](libraries.md) -- import できる 11 の R7RS ライブラリと、それぞれが
  提供するもの。
- [*Structure and Interpretation of Computer Programs*（SICP）互換](sicp.md) -- `import`
  なしで見える MIT/SICP 由来の名前。
- [eval](eval.md) -- `(eval datum env)` と、それが受け付ける環境。
- [仕様との差異](deviations.md) -- この実装が R7RS から外れている点と、まだ未実装のもの。
- [Common Lisp との混在](common-lisp.md) -- Common Lisp のファイルから Scheme の手続きを
  呼ぶ方法、およびその逆。
- [リファレンス](reference.md) -- 手続き、定数、構文キーワードごとに 1 ページ。
