# uiop:read-file-string

`(uiop:read-file-string file &rest keys)`

ファイルの内容全体を 1 つの文字列として返します。開いて最後まで読んで閉じる、という
一連の操作を 1 回の呼び出しで書ける形です。存在しないファイルは [`open`](open.md) と
同じくシグナルを発生させます。

lite 版: 本家 UIOP は `&rest` のキーワードを open へそのまま渡しますが、こちらでは
受け付けて無視します。唯一影響しうる `:external-format` は rontolisp には存在しません。
どのバックエンドも UTF-8 で読みます。

```console
(let ((sql (uiop:read-file-string "db/20260101.up.sql")))
  (print (length sql)))
```

## バックエンドサポート

4 バックエンドすべてです。`with-open-file` とチャンク単位の
[`read-sequence`](read-sequence.md) ループの上に rontolisp ソースで 1 つだけ定義されて
いるので、ファイルを入力用に開けるところならどこでも動きます。バッファのサイズを
[`file-length`](file-length.md) から決めることは意図的に避けています。2つのWASM
バックエンドではこれが `nil` を返すためです。
