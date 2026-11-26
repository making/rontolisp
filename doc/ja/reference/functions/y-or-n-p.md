# y-or-n-p

`(y-or-n-p &optional format-control &rest format-arguments)`

はい/いいえの質問をして `t` または `nil` を返します。省略可能な
[`format`](../macros/format.md) の制御文字列とその引数をまず出力し、続けて
`" (y or n) "` を出力してから、標準入力から 1 行読み込みます。`y` または `Y` で始まる
行は `t`、`n` または `N` で始まる行は `nil` を返し、それ以外 (空行を含む) は
プロンプト全体を再出力して聞き直します。

lite 版: Common Lisp はエコーなしで 1 **文字**を読みますが、こちらは 1 行を読むので、
ユーザーが改行を入力するまで答えは確定しません。入力の終端では、シグナルを発生させる
代わりに `nil` を返します。対話的なユーザーがいないバックエンドでは聞き直しようが
ないためです。

```console
(if (y-or-n-p "Delete ~A?" "old.sql")
    (delete-file "old.sql")
    (print "kept"))
```

`maybe` と答えると `Delete old.sql? (y or n) ` がもう一度出力され、続けて `yes`
(あるいは単に `y`) と答えると `t` が返ってファイルが削除されます。

## バックエンドサポート

4 バックエンドすべてです。[`format`](../macros/format.md) と
[`read-line`](read-line.md) の上に、rontolisp ソースで 1 つだけ定義されています。
