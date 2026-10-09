# uiop/filesystem

`uiop/filesystem` はファイルシステムの検査、ディレクトリツリーの走査、ツリーの変更を行います。**32 個のエクスポートはすべて実装済み**であり、読み取り側は 4 つすべてのバックエンドで同一に動作します — 各バックエンドが既に持つ唯一の存在検査 (`probe-file`) と唯一のディレクトリ列挙 (`directory`) の上に構築されています。

すべての名前はどちらの綴りでも到達できます。`uiop:probe-file*` と `uiop/filesystem:probe-file*` は同じ関数です ([The uiop Package](../uiop.md#sub-packages))。

## 検査と走査

| 関数 | 答え |
|----------|--------|
| `uiop:file-exists-p` | ファイルが存在すればそのパス名、存在しなければ `nil` — すべてのバックエンドで `probe-file` に落とされる、`probe-file` と同じ契約 |
| `uiop:directory-exists-p` | ディレクトリが存在すれば（末尾に `/` を付けた）そのパス名、存在しなければ `nil` — `file-exists-p` のディレクトリ版であり、空のディレクトリと存在しないディレクトリを区別できる唯一の手段 |
| `uiop:probe-file*` | 指定子を解析して検査する。何か存在すれば解析したパス名（`:truename t` では真名 (truename)）、存在しなければ `nil` — ワイルドカードのような `ensure-pathname` が拒否する指定子も `nil` |
| `uiop:truename*` | `nil` を許す `truename`。ファイルが存在すれば真名（ディレクトリ形でも試す。一部の処理系の `truename` は末尾の区切り文字がないと失敗するため）、存在しなければ `nil` — `nil` には `nil` |
| `uiop:directory*` | `directory` そのもの — 処理系ごとのシンボリックリンク用キーは受け付けて捨てられる。`directory` は各エントリを解決せず名前のまま返し、本家がそれらのキーで求めるのもその答えであるため |
| `uiop:directory-files` | ディレクトリの非ディレクトリエントリ — サブディレクトリを除いた `(directory "db/*.*")`。省略可能な第 2 引数は名前と型だけのワイルドカードの名前文字列で、`directory` のマッチとまったく同じく絞り込む。省略時はすべてを列挙し、ディレクトリ成分を持つパターンはエラー |
| `uiop:subdirectories` | ディレクトリのサブディレクトリ。各々末尾に `/` を付ける |
| `uiop:collect-sub*directories` | ディレクトリツリーを走査する。`collectp` が `collector` に渡すものを決め、`recursep` が降りるものを決める。渡されるディレクトリはルートを含めすべてディレクトリ形 |
| `uiop:filter-logical-directory-results` | エントリをそのまま返す — 論理パス名はここには存在できない（`logical-pathname-p` はすべてのバックエンドで `nil`）ため、取り除くものがない |
| `uiop:safe-file-write-date` | 存在しないファイルの `file-error` を飲み込む `file-write-date` |
| `uiop:native-namestring` | `"/tmp/x"` — OS 側のパス名綴り。ここでは名前文字列そのものなので `namestring` と同じ |
| `uiop:parse-native-namestring` | `parse-unix-namestring` に `ensure-pathname` の制約を加えたもの — `os-unix-p` は無条件に真なので、ネイティブ綴りは Unix 綴りそのもの |
| `uiop:get-pathname-defaults` | 相対名が解決される基準のデフォルト — 絶対なデフォルト引数が与えられない限り `*default-pathname-defaults*` (初期値 `#P""`、ホストの作業ディレクトリを指すパス名) を返す |

```lisp
(print (uiop:probe-file* "definitely-missing.txt"))
(print (uiop:truename* nil))
(print (uiop:parse-native-namestring "/tmp/x" :ensure-directory t))
(print (string (uiop:inter-directory-separator)))
```

```
NIL
NIL
#P"/tmp/x/"
":"
```

## 環境パス名

| 関数 | 答え |
|----------|--------|
| `uiop:inter-directory-separator` | `#\:` — すべてのバックエンドで Unix の区切り文字 |
| `uiop:split-native-pathnames-string` | `:` 区切りのネイティブ文字列を分割し各片を解析する。空片は `nil` を表す |
| `uiop:getenv-pathname` | 環境変数の値をネイティブパス名として解析し、`ensure-pathname` の制約で検査したもの |
| `uiop:getenv-pathnames` | 変数の `:` 区切り値を同様に解析したリスト。空エントリは `nil` になる |
| `uiop:getenv-absolute-directory` | `:want-absolute t :ensure-directory t` 付きの `getenv-pathname` |
| `uiop:getenv-absolute-directories` | `:want-absolute t :ensure-directory t` 付きの `getenv-pathnames` |

```lisp
(progn (setf (uiop:getenv "UIOP_FS_DEMO") "/tmp:/var")
       (print (uiop:getenv-pathnames "UIOP_FS_DEMO"))
       (print (uiop:getenv-pathname "UIOP_FS_UNSET")))
```

```
(#P"/tmp" #P"/var")
NIL
```

## シンボリックリンクと処理系ディレクトリ

どのバックエンドも [`truename`](../functions/truename.md) と同じようにシンボリックリンクを解決するため、`uiop:*resolve-symlinks*` の既定値は upstream と同じく `t` です。

| 関数 | 答え |
|----------|--------|
| `uiop:*resolve-symlinks*` | `t` |
| `uiop:resolve-symlinks` / `uiop:truenamize` | 存在する部分にあるシンボリックリンクをすべて解決し、残りは綴りのまま残したパス名 (相対パスは相対パスのまま) |
| `uiop:resolve-symlinks*` | フラグが真なら `resolve-symlinks`、そうでなければ引数をそのまま |
| `uiop:lisp-implementation-directory` | `nil` — 名付けるべきインストールディレクトリが存在しない。`compile-file` も fasl キャッシュもない |
| `uiop:lisp-implementation-pathname-p` | `nil` — 存在しないディレクトリの下にあるものはない |

## 作業ディレクトリ

| 関数 | 答え |
|----------|--------|
| `uiop:call-with-current-directory` | `*default-pathname-defaults*` をそのディレクトリに束縛し、プロセスの作業ディレクトリを移してサンクを実行する。`chdir` の決定を継承するため、`nil` でないディレクトリはすべてのバックエンドで `not-implemented-error` を通知し、`nil` はサンクをそのまま実行する |
| `uiop:with-current-directory` | その上のマクロ。`(uiop:with-current-directory (dir) body...)` は本体を `call-with-current-directory` の下で実行する。ディレクトリ省略時は `nil` であり、本体をそのまま実行する |

## ツリーの変更

| 関数 | 答え |
|----------|--------|
| `uiop:ensure-all-directories-exist` | 各パス名の親ディレクトリを作成する（各々に `ensure-directories-exist`） |
| `uiop:rename-file-overwriting-target` | `rename-file` — 移動自体が対象を置き換えるため、上書きは基本操作自身のもの |
| `uiop:delete-file-if-exists` | ファイルを削除し、存在しない場合は通知せず `nil` を答える — UIOP がこれをエクスポートする理由そのもの |
| `uiop:delete-empty-directory` | 空ディレクトリを削除する。同じファイル基本操作の上にある（空ディレクトリもファイルと同様に削除できる） |
| `uiop:delete-directory-tree` | 可搬な再帰走査による `rm -rf`。ディレクトリは `:validate` 述語を通過しなければならない（述語なしも失敗も `parameter-error`）。存在しないディレクトリは、`:if-does-not-exist` が `:ignore` でない限り通知する |

4 つの変更操作は、基本操作が実在するすべてのバックエンドで実際に動作します。`ensure-all-directories-exist`（`%make-directories` 上）、`rename-file-overwriting-target`（`%rename-file` 上）、`delete-file-if-exists`（`%delete-file` 上）はどこでも動作し、ci-spec の `filesystem-write-create-rename-delete-and-probe` ケースが全バックエンドで固定しています。`delete-empty-directory`（とそれを使う `delete-directory-tree`）も全バックエンドで空のディレクトリを削除し、空でないディレクトリには `file-error` を通知します。
