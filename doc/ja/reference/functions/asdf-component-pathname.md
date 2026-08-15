# asdf:component-pathname

`(asdf:component-pathname component)`

コンポーネントのパス名を名前文字列で返します。**システム**では、見つかった
ディレクトリを末尾に `/` を付けて。**ソースファイル**
（[`asdf:component-children`](asdf-component-children.md) の要素）では、その
ファイル自身の解決済みパスを返します。`component` には
[`asdf:find-system`](asdf-find-system.md) が返すメタオブジェクトも、名前の指示子
（文字列・キーワード・シンボル）も渡せます。指示子はシステムを指し、そのシステムは
登録済み（読み込み済み、または読み込み中）でなければなりません。

ライブラリが自分のソースの隣に同梱したデータファイルを見つけるのはこの方法です。local-time
は `(asdf:component-pathname (asdf:find-system :local-time nil))` で自分の `zoneinfo/`
リポジトリを見つけます。
[`asdf:system-relative-pathname`](asdf-system-relative-pathname.md) はこれと相対名の
合成を 1 回の呼び出しで行います。

```console
(asdf:load-system "my-lib")
(print (asdf:component-pathname (asdf:find-system "my-lib")))
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。インタプリタは実行時に自身のシステムレジストリから
答えます。コンパイルパスはシステム名がリテラルのとき（リテラルな `find-system` で包んだ
形も含めて）呼び出しをリテラルの名前文字列へ畳み込みます。これはどのライブラリも使う形で、
それ以外の形はコンパイルされたプログラムが持ち運ぶレジストリを読みます。
