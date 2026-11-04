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
| `asdf:operate` | `(asdf:operate 'asdf:load-op :my-lib)` | システムに operation を perform する: `load-op` は `load-system`、`test-op` は `test-system` と同じ。それ以外の operation はエラーを通知する |
| `asdf:perform` | `(defmethod asdf:perform :around ((o asdf:load-op) (c asdf:cl-source-file)) ...)` | 総称関数: システムのロードを構成する各動作 (各ファイルとシステムへの `prepare-op`・`compile-op`・`load-op`)。ライブラリのメソッドはコンポーネントのロードごとにその周りで実行される |
| `asdf:oos` | `(asdf:oos 'asdf:load-op :my-lib)` | `operate` の旧名。`load-systems`、`load-systems*`、`require-system`、`make` もロードの別の書き方 |
| `asdf:load-asd` | `(asdf:load-asd "vendor/old/my-lib.asd")` | 1 つの `.asd` ファイルの定義をロードする |
| `asdf:clear-system` | `(asdf:clear-system :my-lib)` | システムを忘れる。次の `find-system` は探索し直す |
| `asdf:map-systems` | `(asdf:map-systems #'print)` | 登録済みの各システムを引数に関数を呼ぶ。`already-loaded-systems`、`component-loaded-p`、`registered-system` で状態を問い合わせる |
| `asdf:find-component` | `(asdf:find-component :my-lib "main")` | コンポーネントを名前、または木をたどる名前のリストで探す。なければ nil。`module-components` は `component-children` と同じ |
| `asdf:system-description` | `(asdf:system-description (asdf:find-system :my-lib))` | `defsystem` の宣言を読む。他の `system-` リーダー、`component-property`、`system-source-file` も同様 |
| `asdf:*central-registry*` | `(push "vendor/my-lib/" asdf:*central-registry*)` | 探索パスより先に `NAME.asd` を探すディレクトリ |
| `asdf:initialize-source-registry` | `(asdf:initialize-source-registry '(:source-registry (:tree :here) :inherit-configuration))` | 探索先を設定する (`:directory`、`:tree`、`:here`、継承する設定)。`clear-source-registry` で取り消す |
| `asdf:*system-definition-search-functions*` | `(push 'vendor-search asdf:*system-definition-search-functions*)` | 探索で順に呼ぶ関数。名前を受け取り、パス名かシステムを返す |
| `asdf:find-system` | `(asdf:find-system :my-lib nil)` | システムのメタオブジェクト。名前ごとにメモ化された本物の `asdf:system` CLOS インスタンス (呼び出し間で `eq`)。`error-p` が nil なら未知の名前に nil |
| `asdf:registered-systems` | `(asdf:registered-systems)` | 登録済みのすべてのシステムの小文字化された名前 (登録順) |
| `asdf:system-relative-pathname` | `(asdf:system-relative-pathname :my-lib "data/tlds.dat")` | システムのソースディレクトリを基準に解決した名前文字列 (コンパイルパスではリテラルへ畳み込まれる) |
| `asdf:component-pathname` | `(asdf:component-pathname (asdf:find-system :my-lib))` | システムまたはモジュールのディレクトリ (末尾に `/`)、またはファイルの解決済みパス。メタオブジェクトも名前指示子も受け付ける |
| `asdf:component-name` | `(asdf:component-name (asdf:find-system :my-lib))` | リーダー: 定義に書かれたコンポーネントの名前 (シンボルは小文字化) |
| `asdf:component-version` | `(asdf:component-version (asdf:find-system :my-lib))` | リーダー: 宣言された `:version` 文字列。素の文字列で宣言されていなければ nil (計算された書き方は評価されません) |
| `asdf:component-children` | `(asdf:component-children (asdf:find-system :my-lib))` | リーダー: システムまたはモジュールの子 (定義順)。`asdf:module`、`asdf:cl-source-file`、`asdf:static-file` などのインスタンス |
| `asdf:component-sideway-dependencies` | `(asdf:component-sideway-dependencies (asdf:find-system :my-lib))` | リーダー: システムの `:depends-on` の名前 (package-inferred のサブシステム名を含む) |
| `asdf:component-parent` | `(asdf:component-parent child)` | リーダー: 親コンポーネント — その子を持つシステムまたはモジュール、システムでは nil |
| `asdf:component-system` | `(asdf:component-system child)` | コンポーネントが属するシステム (`component-parent` をたどる) |

本物の ASDF 3.3.1 がエクスポートする他の名前も、すべて読み込めてどのバックエンドにも定義があるので、
それを書いたライブラリはロードできます。クラスはクラスとして、総称関数はメソッドを受け付け、変数は
`nil` で、関数の呼び出しはその名前を示す `uiop:not-implemented-error` を通知します。バンドルと
プログラムの operation はクラスで、`asdf:operate` が名前を示して拒否します。
