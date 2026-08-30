# asdf パッケージの関数

`asdf` パッケージは、`.asd` 定義から複数ファイルのシステムをロードするための、ASDF の
限定的な API 互換サブセットです。**Common Lisp の一部ではありません**。シンボルは
`asdf:` 修飾子付きで参照します。各名前は個別のページにリンクしています。プロジェクトの
全体像と探索パスの詳細は [システムガイド](../../guides/asdf-systems.md)を参照してください。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `asdf:defsystem` | `(asdf:defsystem :my-lib :components ((:file "main")))` | システムを定義する (名前・`:depends-on`・`:serial`・`:components`)。後続の `load-system` 用 |
| `asdf:load-system` | `(asdf:load-system :my-lib)` | システムをロードする: まず依存システム、次にコンポーネントファイルを順に (コンパイルパスではリテラルかつトップレベルのフォーム) |
| `asdf:test-system` | `(asdf:test-system "my-app")` | システムをロードし、`:in-order-to` の test-op 連鎖をたどって、記録された `:perform (test-op ...)` 本体を実行する — `.asd` の標準テストエントリポイント |
| `asdf:find-system` | `(asdf:find-system :my-lib nil)` | システムのメタオブジェクト。名前ごとにメモ化された本物の `asdf:system` CLOS インスタンス (呼び出し間で `eq`)。`error-p` が nil なら未知の名前に nil |
| `asdf:registered-systems` | `(asdf:registered-systems)` | 登録済みのすべてのシステムの小文字化された名前 (登録順) |
| `asdf:system-relative-pathname` | `(asdf:system-relative-pathname :my-lib "data/tlds.dat")` | システムのソースディレクトリを基準に解決した名前文字列 (コンパイルパスではリテラルへ畳み込まれる) |
| `asdf:component-pathname` | `(asdf:component-pathname (asdf:find-system :my-lib))` | システムのソースディレクトリ (末尾に `/`)、またはソースファイルの子の解決済みパス。メタオブジェクトも名前指示子も受け付ける |
| `asdf:component-name` | `(asdf:component-name (asdf:find-system :my-lib))` | リーダー: コンポーネントの小文字正規形の名前 |
| `asdf:component-version` | `(asdf:component-version (asdf:find-system :my-lib))` | リーダー: 宣言された `:version` 文字列。素の文字列で宣言されていなければ nil (計算された書き方は評価されません) |
| `asdf:component-children` | `(asdf:component-children (asdf:find-system :my-lib))` | リーダー: システムのコンポーネントファイル (ロード順、ファイルごとに 1 つの `asdf:cl-source-file`) |
| `asdf:component-sideway-dependencies` | `(asdf:component-sideway-dependencies (asdf:find-system :my-lib))` | リーダー: システムの `:depends-on` の名前 (package-inferred のサブシステム名を含む) |
| `asdf:component-parent` | `(asdf:component-parent child)` | リーダー: 親コンポーネント — ソースファイルではシステム、システムでは nil |
| `asdf:component-system` | `(asdf:component-system child)` | コンポーネントが属するシステム (`component-parent` をたどる) |

