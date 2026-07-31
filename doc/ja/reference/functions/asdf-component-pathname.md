# asdf:component-pathname

`(asdf:component-pathname system)`

読み込まれたシステムが見つかったディレクトリを、末尾に `/` を付けて返します。本物の ASDF
ではこれは任意のコンポーネントのベースパス名ですが、rontolisp がコンポーネントオブジェクト
として実体化するのは*システム*だけであり（小文字正規形の名前という文字列で、
`asdf:find-system` が返すものです）、ここではライブラリが実際に呼び出す名前で表された
システムのソースディレクトリになります。

ライブラリが自分のソースの隣に同梱したデータファイルを見つけるのはこの方法です。local-time
は `(asdf:component-pathname (asdf:find-system :local-time nil))` で自分の `zoneinfo/`
リポジトリを見つけます。システムは登録済み（読み込み済み、または読み込み中）でなければ
なりません。未知の名前はエラーです。
[`asdf:system-relative-pathname`](asdf-system-relative-pathname.md) はこれと相対名の
合成を 1 回の呼び出しで行います。

```console
(asdf:load-system "my-lib")
(print (asdf:component-pathname (asdf:find-system "my-lib")))
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。インタプリタは実行時に自身のシステムレジストリから
答え、コンパイルパスはシステム名がリテラルのときに呼び出しをリテラルの名前文字列へ畳み
込みます。これはどのライブラリも使う形です。
