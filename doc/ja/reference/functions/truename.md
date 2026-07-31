# truename

`(truename pathname)`

ファイルが存在すればそのパス名を返し、存在しなければエラーを通知します。この通知こそが
要点です。`(ignore-errors (truename path))` は「あればそのパス、無ければ `nil`」を表す
Common Lisp の定型句であり、ライブラリは任意指定のファイルやディレクトリの有無を調べる
のにこれを使います。

rontolisp はシンボリックリンクを解決せず、パスを絶対パスにもしません（パス名はその名前
文字列そのものです）。したがって成功時の値は引数の文字列そのものです。条件を通知させずに
答えだけが欲しい場合は [`probe-file`](probe-file.md) を使ってください。同じ問いに対して
通知の代わりに `nil` を返します。

```lisp
(ignore-errors (truename "definitely-missing.txt"))   ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。`probe-file` の上に書かれた rontolisp ソースによる
1 つの定義があり、参照されたときにプログラムへ差し込まれます。
