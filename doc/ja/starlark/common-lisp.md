# Common Lisp との混在

言語はファイルごとに選ばれるので、1 つのプログラムに Starlark のファイルと Common Lisp の
ファイルを含められます。両者の間で値は変換されません。どちらの側でも同じオブジェクトです。

| Starlark | Common Lisp |
|---|---|
| `None` | `:none` |
| `True`、`False` | `t`、`nil` |
| 整数、浮動小数点数、文字列 | 整数、倍精度浮動小数点数、文字列 |
| リスト、タプル、辞書、range、関数 | 中身を見られない構造体。Starlark にそのまま渡し返します |
| 型が `"unknown"` の値。真であり、自分自身とだけ等しく、辞書のキーにできません | そのほかの値（コンス、シンボル、ハッシュテーブル） |
| 組み込み関数 | 関数 |

`nil` は `False` なので、Common Lisp の空リストは Starlark のリストではありません。

## Common Lisp から Starlark を呼ぶ

`(load "rules.star")` はファイルを実行します。ファイルのグローバル変数は、それぞれ同じ名前の
変数になります。Starlark の名前は大文字小文字を保つので、縦棒で囲んで書きます。
[`rontolisp:starlark-call`](../reference/functions/rontolisp-starlark-call.md) は、関数の値
（`def`、`lambda`、組み込み関数、メソッド）を位置引数で呼び出します。引数は Starlark の
呼び出しと同じ規則で束縛されます。Common Lisp のリストを引数に展開するには `apply` を
使います。

```console
$ cat rules.star
def greet(name, greeting = "Hello"):
    return greeting + ", " + name
def longest(*words):
    return max(words, key = len)
$ cat main.lisp
(load "rules.star")
(write-line (rontolisp:starlark-call |greet| "Lisp"))
(write-line (rontolisp:starlark-call |greet| "Lisp" "Hi"))
(write-line (apply #'rontolisp:starlark-call |longest| '("a" "abc" "ab")))
$ rontolisp main.lisp
Hello, Lisp
Hi, Lisp
abc
```

`load` から戻った時点で、ファイルが作ったリスト、辞書、集合は[凍結](index.md#modules)されて
います。呼び出しがエラーで終わり、そのエラーをハンドラ（`handler-case`）が処理した場合、
ループが走査中だったリストは再び変更できるようになります。

一部の `def` は `defun` にも変換されますが、これはフロントエンドの内部のもので、変わる
ことがあります。呼び出しには値を使ってください。

## Starlark から Common Lisp を呼ぶ

モジュール名を `@` とパッケージ名にした `load` は、そのパッケージの関数を束縛します。
`load("@cl-user", "emit", note = "add-note")` は、`emit` を関数 `emit` に、`note` を
`add-note` に束縛します。文字列はシンボルと同じ規則で読まれます。大文字に変換されますが、
縦棒で囲むと書いたままになります（`"|twice|"`）。関数は、この文の実行時に定義されている
必要があります。

束縛された関数は組み込み関数の値です。位置引数をそのまま受け取り、値をそのまま返します。
名前付き引数を渡すとエラーになります。`*` 引数は Starlark のリストを関数の引数に展開する
ので、下の `cl_list(*xs)` は Common Lisp のリストを作ります。

```starlark
load("@cl", "gcd", upcase = "string-upcase", cl_list = "list")
print(gcd(12, 18), upcase("abc"), upcase, type(cl_list(*[1, 2])))
```

```
6 ABC <built-in function cl:string-upcase> unknown
```

Common Lisp が引数として渡した関数も、同じ規則で呼び出せます。プログラム自身の関数を
Starlark から呼ぶ場合は、ファイルを読み込む前にその関数を定義しておきます:

```console
$ cat rules.star
load("@cl-user", "emit")
emit("app", 2)
emit("lib", 0)
$ cat main.lisp
(defun emit (name deps) (format t "~a has ~a deps~%" name deps))
(load "rules.star")
$ rontolisp main.lisp
app has 2 deps
lib has 0 deps
```
