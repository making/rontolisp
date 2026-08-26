# ql-dist:install-dist

`(ql-dist:install-dist name-or-url &rest options)`

[`ql:quickload`](ql-quickload.md) がダウンロードする Quicklisp dist の隣に、Quicklisp 形式のディストリビューションを追加します。引数は既知の dist 名 (`"quicklisp"` または `"ultralisp"`)、あるいは distinfo の URL です ([Ultralisp](https://ultralisp.org/) 自身が案内している `"http://dist.ultralisp.org/"` は distinfo をそのまま返します)。戻り値は追加した dist 名の文字列で、同じ dist を 2 回追加しても何も起きません。キーワードオプション (`:prompt nil` など) は受理され、無視されます — ここではダウンロード前に確認を求めることはありません。

デフォルトで有効なのは Quicklisp だけなので、Ultralisp は **オプトイン**です。この呼び出しか、フォームを書く場所がない起動のための CLI オプション `--dist` / 環境変数 `RONTOLISP_DISTS` で有効化します。dist は **追加した順**にシステム単位で検索されます: `ql:quickload` は各システム (と各依存) を、それを列挙している最初の dist から取得します。つまり dist を追加しても、Quicklisp にない名前が解決できるようになるだけで、他のライブラリの取得元は変わりません。dist の index は、実際にその dist まで検索が到達したときにだけダウンロードされます。各 dist は `~/.rontolisp/<dist>/` 以下にキャッシュします (ベースは `RONTOLISP_DIST_HOME` で変更でき、quicklisp については従来どおり `RONTOLISP_QUICKLISP_HOME` が優先されます)。

`ql:quickload` と同様に、効果が及ぶのは **インタプリタ実行時またはコンパイル時** です (コンパイル済みプログラムの中ではなく Java 側)。コンパイルパス (JVM/WASM) では、**リテラルかつトップレベル**の呼び出しが、それ以降の `quickload` がどの dist からダウンロードするかをスプライス中に設定し、その後フォーム自体は消費されます。別のフォームにネストされた呼び出しや、計算された引数を持つ呼び出しはコンパイルエラーです。インタプリタでは通常のランタイム関数なので、計算された URL も使えます。

```console
$ rontolisp
CL-USER> (ql-dist:install-dist "http://dist.ultralisp.org/" :prompt nil)
"ultralisp"
CL-USER> (ql:quickload "split-sequence")
(split-sequence)
```

検索順・キャッシュのレイアウト・index を更新する [`ql:update-dist`](ql-update-dist.md) については [システムガイド](../../guides/asdf-systems.md#adding-a-dist-ultralisp)を参照してください。
