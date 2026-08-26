# ql:quickload

`(ql:quickload name-or-names &rest options)`

システム (とその依存) を本物の [Quicklisp](https://www.quicklisp.org/) ディストリビューションからローカルキャッシュにダウンロードし、[`asdf:load-system`](asdf-load-system.md) と全く同じようにロードします。引数は単一のシステム名指定子 (文字列・キーワード・シンボル) か、それらのリストです。戻り値はロードしたシステム名のシンボルのリストです。`quicklisp` は `ql` パッケージのニックネームです。キーワードオプション (`:silent t` など) は [`asdf:load-system`](asdf-load-system.md) と同様に受理され、無視されます。

ダウンロードには Quicklisp の dist メタデータを使います: `quicklisp.txt` (distinfo) が `systems.txt` (依存解決) と `releases.txt` (プロジェクトごとの tarball URL) を指し示します。各リリースの tarball を取得・展開し、`~/.rontolisp/quicklisp/` 以下にキャッシュします (場所は環境変数 `RONTOLISP_QUICKLISP_HOME` で変更可能)。そのため、同じシステムを 2 回目に `quickload` してもネットワーク I/O は発生しません。デフォルトで有効な dist は Quicklisp だけで、[`ql-dist:install-dist`](ql-dist-install-dist.md) (または CLI の `--dist`) で [Ultralisp](https://ultralisp.org/) などの Quicklisp 形式のディストリビューションを追加できます。追加後は、各システム (と各依存) を、それを列挙している最初の dist から取得します。ソースが揃うと、展開された `.asd` ディレクトリがシステム探索パスに追加され、あとは `asdf` サブセットのロード処理に委譲されます — つまり `ql:quickload` は「自動ダウンロードを前段に付けた `asdf:load-system`」です。したがって制約も同じで、多くのライブラリは rontolisp が未実装の Common Lisp 機能 (完全な CLOS プロトコル、コンディションシステム) を使っており、ダウンロードできてもロードできません。

ダウンロードは **インタプリタ実行時またはコンパイル時** に (コンパイル済みプログラムの中ではなく Java 側で) 行われます。インタプリタでは `quickload` は通常のランタイム関数なので、計算されたシステム名も使えます。コンパイルパス (JVM/WASM) では、**リテラルかつトップレベル**の `(ql:quickload NAME)` がコンパイル時にダウンロードされ、そのコンポーネントファイルがプログラムにスプライスされます — そのためコンパイル済みプログラムはソースを内包しており、実行時にフェッチしません (WASM の `fetch` 制約は当てはまりません)。別のフォームにネストされた `quickload` や、計算された引数を持つ `quickload` はコンパイルエラーになります。

```console
$ rontolisp
CL-USER> (ql:quickload "split-sequence")
(split-sequence)
CL-USER> (split-sequence:split-sequence #\, "a,b,c")
("a" "b" "c")
```

最初の呼び出しで `split-sequence` をダウンロードしてキャッシュし、2 回目の呼び出しでロード済みのシステムを実行します。キャッシュのレイアウトと、実際にロードできるライブラリの一覧については [システムガイド](../../guides/asdf-systems.md) を参照してください。
