# rontolisp:starlark-call

`(rontolisp:starlark-call function argument*)`

実験的な [Starlark](../../starlark/index.md) フロントエンドの関数の値（`def`、`lambda`、
組み込み関数、レシーバに束縛されたメソッド）を位置引数で呼び出し、その値を返します。
引数と値は変換されずにそのまま渡ります
（[Common Lisp との混在](../../starlark/common-lisp.md)）。Common Lisp の関数を渡すと、
`apply` と同じように呼び出します。

```lisp
(rontolisp:starlark-call #'+ 1 2)   ; => 3
```

Starlark のファイルのグローバル変数は、同じ名前の変数です:

```console
$ cat rules.star
def greet(name, greeting = "Hello"):
    return greeting + ", " + name
$ cat main.lisp
(load "rules.star")
(write-line (rontolisp:starlark-call |greet| "Lisp"))
$ rontolisp main.lisp
Hello, Lisp
```

引数は Starlark の呼び出しと同じ規則で束縛されます。デフォルト値を持つパラメータは省略でき、
`*args` は余った引数を受け取ります。合わない呼び出しは、Starlark のメッセージを持つエラーに
なります。呼び出しがエラーで終わり、そのエラーをハンドラが処理した場合、ループが走査中だった
リストと辞書は再び変更できるようになります。`--starlark-standard spec` では、実行中だった
関数も再び呼び出せるようになります。

## バックエンドサポート

すべてのバックエンド・すべての WASM モードで動作します。Starlark のランタイムライブラリは
rontolisp 自身で書かれており、使用時にプログラムへコンパイルされます。
