# asdf:load-asd

`(asdf:load-asd pathname &key name)`

1 つの `.asd` ファイルの定義をロードし、レジストリの同名の定義を置き換えます。ファイル名は
問いません。ファイルがなければ、本物の ASDF と同じく何も定義しません。ファイルのロード中の
エラーは `asdf:load-system-definition-error` になります。`asdf:define-op` のインスタンスを
返します。

```console
(asdf:load-asd "vendor/old/my-lib.asd")
(asdf:load-system :my-lib)
```

## バックエンドサポート

コンパイル時にパスがわかる場合は 4 つすべてのバックエンドで動作します。パスがリテラル、または
ファイルのロードコンテキストから組み立てたトップレベルの呼び出しは、スプライス中に読み込みます。
それ以外の呼び出しは、実行時に何もロードしないコンパイルパスではエラーを通知します。
