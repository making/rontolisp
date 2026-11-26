# ql:update-dist

`(ql:update-dist name &rest options)`

dist のキャッシュ済み index を破棄し、次の [`ql:quickload`](ql-quickload.md) がそのディストリビューションの `systems.txt` と `releases.txt` を読み直して、キャッシュ作成後に公開されたリリースを見えるようにします。引数は追加済みの dist 名 (`"quicklisp"`、`"ultralisp"` など) を文字列・キーワード・シンボルで指定し、戻り値はその名前です。追加されていない dist を指定するとエラーになります。キーワードオプションは受理され、無視されます。

これがないと dist の index は永久にキャッシュされます。それが `quickload` の 2 回目をタダにしている仕組みですが、数分ごとに再構築されるディストリビューション ([Ultralisp](https://ultralisp.org/)) では、キャッシュされた index が知らないリリースが次々に公開されます。展開済みのソースはそのまま残ります: リリースのディレクトリ名はバージョンを含むので、新しいリリースは古いものを置き換えるのではなく隣に展開されます。

タイミングは `ql:quickload` と同じで、インタプリタ実行時か、**リテラルかつトップレベル**の呼び出しならコンパイル時です (その場合フォームは消費され、コンパイル済みプログラムは実行時に何もダウンロードしません)。ネストされた呼び出しや計算された呼び出しは JVM/WASM バックエンドではコンパイルエラーです。

```console
$ rontolisp
CL-USER> (ql:update-dist "ultralisp")
"ultralisp"
CL-USER> (ql:quickload "split-sequence")
(split-sequence)
```

[システムガイド](../../guides/asdf-systems.md#adding-a-dist-ultralisp)と [`ql-dist:install-dist`](ql-dist-install-dist.md) も参照してください。
