# ql / ql-dist パッケージの関数

`ql` パッケージは Quicklisp の限定的な API 互換サブセットです。`quickload` は本物の
Quicklisp ディストリビューションからシステムをローカルキャッシュにダウンロードし、
`asdf` サブセットを経由してロードします (`quicklisp` は組み込みのニックネーム)。
`ql-dist` はディストリビューション管理のパッケージで、プログラムが書くメンバーは
`install-dist` の 1 つです: Quicklisp 形式の別のディストリビューション
([Ultralisp](https://ultralisp.org/) や任意の distinfo URL) を、`quickload` が
追加順に検索する dist 群に加えます。どちらも **Common Lisp の一部ではありません**。
シンボルは `ql:` / `ql-dist:` 修飾子付きで参照します。
下記の名前は個別のページにリンクしています。キャッシュのレイアウトと制約については
[システムガイド](../../guides/asdf-systems.md#downloading-with-quickload)を参照してください。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `ql:quickload` | `(ql:quickload "split-sequence")` | 追加済みの dist からシステム (とその依存) をダウンロードし、`~/.rontolisp/<dist>` にキャッシュしてロードする。ロードしたシステム名のリストを返す |
| `ql-dist:install-dist` | `(ql-dist:install-dist "ultralisp")` | Quicklisp 形式のディストリビューション (既知の名前か distinfo URL) を `quickload` の検索対象に加える。dist 名を返す |
| `ql:update-dist` | `(ql:update-dist "ultralisp")` | dist のキャッシュ済み index を破棄し、次の `quickload` が最新のリリースを見えるようにする。dist 名を返す |

