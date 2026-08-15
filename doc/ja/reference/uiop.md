# uiop パッケージ

`uiop` は ASDF の移植性レイヤであり、Common Lisp が標準化しなかった操作 — 環境変数の
読み取り、ファイルの存在確認、ディレクトリの走査、文字列の分割 — に対して処理系非依存の
ライブラリがすでに使っている綴りです。**Common Lisp の一部ではありません**。シンボルは
修飾子付き (`uiop:getenv`) で参照し、修飾なしの綴りはありません。

カバレッジの目標は **uiop 3.3.7** — 組み込みの
[`ql:quickload`](../guides/asdf-systems.md#downloading-with-quickload)
クライアントが取得するリリースです。このリリースは **429 個のシンボル**をエクスポート
しており、rontolisp はそのうちの一部を実装しています。残りは解決だけされてシグナルを
上げるので、`(:import-from #:uiop)` 句で名前を*挙げているだけ*のライブラリは読み込め、
コンパイルでき、実行できます。

## サブパッケージ

本家の `uiop` は `uiop/driver` であり、15 個のサブパッケージの再エクスポートです。
ライブラリはどちらの綴りでも名指しできます — `lack-middleware-backtrace` は
`(:import-from :uiop/image :print-condition-backtrace)` と書きます。rontolisp は 15 個
すべてを登録し、各サブパッケージが自分の定義するメンバを所有して `uiop` がそれらを
インポートします。したがって**どちらの綴りも同じシンボルを指し**、メンバ名が同じ 2 つの
関数にはなりません:

```lisp
(list (uiop:emptyp "") (uiop/utility:emptyp ""))   ; => (T T)
```

| サブパッケージ | 内容 | 実装済み |
|-------------|------|---------|
| `uiop/package` | シンボルとパッケージの操作 (`find-symbol*`、`intern*`、`define-package`) | 4 / 31 |
| `uiop/package-local-nicknames` | パッケージローカルニックネーム API | 1 / 3 |
| `uiop/package*` | `uiop/package` が定義するがエクスポートしない 3 つのコンディション・型名 | 0 / 3 |
| [`uiop/utility`](uiop/utility.md) | 移植性のあるヘルパ (`strcat`、`split-string`、`if-let`、`not-implemented-error`) | 68 / 68 |
| `uiop/version` | バージョン比較と非推奨コンディション | 1 / 15 |
| [`uiop/os`](uiop/os.md) | ホストの識別、環境変数、作業ディレクトリ | 22 / 22 |
| [`uiop/pathname`](uiop/pathname.md) | パス名の代数 (`subpathname`、`parse-unix-namestring`、`enough-pathname`) | 50 / 50 |
| `uiop/filesystem` | ファイルシステムの探索・走査・変更 | 8 / 32 |
| `uiop/stream` | ファイル内容、一時ファイル、エンコーディング、標準ストリーム | 3 / 66 |
| [`uiop/image`](uiop/image.md) | 終了、致命的コンディション、ダンプフック（コマンドラインは未実装） | 25 / 30 |
| `uiop/launch-program` | 非同期のサブプロセス | 0 / 19 |
| `uiop/run-program` | 同期のサブプロセス | 0 / 7 |
| `uiop/lisp-build` | `compile-file*` と遅延警告 | 1 / 44 |
| `uiop/configuration` | XDG パスと設定ファイルの探索 | 0 / 38 |
| `uiop/backward-driver` | 非推奨の別名 | 0 / 7 |

エクスポートの完全な一覧は
`src/main/resources/am/ik/rontolisp/uiop-exports.txt` としてチェックインされています
(1 行 1 エクスポート: サブパッケージ、シンボル、本家での定義形式)。上の数値はこの一覧に
対して測定されるので、両者は常に一緒に動きます。

## 実装済みのもの

専用のページを持つサブパッケージは 4 つです。うち 3 つは完全に実装済みで、uiop の
他のすべてがその上に書かれている移植性ヘルパ群 `uiop/utility` の 68 個
([uiop/utility](uiop/utility.md))、パス名の代数 `uiop/pathname` の 50 個
([uiop/pathname](uiop/pathname.md))、そしてホストの識別・環境変数・作業ディレクトリ
の 22 個 `uiop/os` ([uiop/os](uiop/os.md)。[`uiop:getenv`](functions/uiop-getenv.md)
もここにあります) です。4 つめは [uiop/image](uiop/image.md) で、
[`uiop:quit`](uiop/image.md#exiting) が 4 つのバックエンドすべてでステータスコード
付きのプロセス終了を行い、致命的コンディション・バックトレース・イメージフックの
各族もここにあります。残りは以下のとおりです。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `uiop:file-exists-p` | `(uiop:file-exists-p "f.txt")` | ファイルが存在すればそのパス名、存在しなければ `nil` — `probe-file` と同じ契約であり、すべてのバックエンドでその基本操作へ落とされます |
| `uiop:directory-exists-p` | `(uiop:directory-exists-p "src/")` | ディレクトリが存在すれば（末尾に `/` を付けた）そのパス名、存在しなければ `nil` — `file-exists-p` のディレクトリ版であり、空のディレクトリと存在しないディレクトリを区別できる唯一の手段です |
| `uiop:directory-files` | `(uiop:directory-files "db/" "*.up.sql")` | ディレクトリのうちディレクトリでないエントリ — `(directory "db/*.*")` からサブディレクトリを除いたものです。UIOP の省略可能な第 2 引数 (名前と型のみのワイルドカードのパス名文字列) は `directory` とまったく同じ規則で絞り込みます。省略するとすべてを一覧し、ディレクトリ部分を含むパターンはエラーです |
| `uiop:subdirectories` | `(uiop:subdirectories "src/")` | ディレクトリのサブディレクトリを、それぞれ末尾に `/` を付けて返します |
| `uiop:collect-sub*directories` | `(uiop:collect-sub*directories "src/" (constantly t) (constantly t) #'print)` | ディレクトリツリーを走査します。`collectp` が `collector` へ渡すものを、`recursep` が降りていく先を決めます。渡されるディレクトリはルートも含めてすべてディレクトリ形式です |
| `uiop:read-file-string` | `(uiop:read-file-string "db/up.sql")` | ファイルの内容全体を 1 つの文字列として返します。ファイルを入力用に開けるすべてのバックエンドで動きます。lite 版: 本家 UIOP の `&rest` キーワードは受け付けて無視します (`:external-format` は rontolisp には存在せず、どのバックエンドも UTF-8 で読みます) |
| `uiop:compile-file-type` | `(uiop:compile-file-type)` | `nil` — コンパイル済みファイルが持つパス名の型。ここには `compile-file` が存在せずそのような型もないため、「このパスは fasl か?」を問う呼び出し側はソースパスに対して「いいえ」を得ます |
| `uiop:default-temporary-directory` | `(uiop:default-temporary-directory)` | `$TMPDIR` をディレクトリ形式で。環境変数が空の場合 (`--env` なしの 2 つの WASM バックエンド) は `#P"/tmp/"` |
| `uiop:delete-file-if-exists` | `(uiop:delete-file-if-exists "scratch.txt")` | ファイルを削除します。存在しない場合はシグナルではなく `nil` を返します — UIOP がこれをエクスポートしている理由そのものです |
| `uiop:get-pathname-defaults` | `(uiop:get-pathname-defaults)` | 相対名が解決される基準のデフォルト — 絶対なデフォルト引数が与えられない限り `*default-pathname-defaults*` (初期値 `#P""`、ホストの作業ディレクトリを指すパス名) を返します |
| `uiop:native-namestring` | `(uiop:native-namestring #P"/tmp/x")` | `"/tmp/x"` — パス名のホスト OS の綴り。ここでは名前文字列そのものなので `namestring` と同じです |
| `uiop:add-package-local-nickname` | `(uiop:add-package-local-nickname '#:j '#:com.example.pkg)` | パッケージ短縮名を登録 (lite: グローバル、パッケージごとのスコープなし)。リテラルなトップレベル呼び出しはコンパイル時ディレクティブなので、すべてのバックエンドで動作します |
| `uiop:symbol-call` | `(uiop:symbol-call :cl :+ 1 2)` | 実行時にパッケージから名前を引いて適用します — 依存関係に持たないシステムを呼ぶための UIOP の遅延束縛呼び出しです |

完全実装済みサブパッケージ以外の 3 つのメンバは**マクロ**で、呼び出されるのではなく
コンパイラが展開します: `uiop:with-temporary-file`、
[`uiop:with-deprecation`](macros/uiop-with-deprecation.md)、
`uiop:define-package` (リテラルなトップレベル呼び出しは `defpackage` と同様に処理されます)。
`uiop/pathname` の 2 つのマクロ — `uiop:with-pathname-defaults` と
`uiop:with-enough-pathname` — は[そのページ](uiop/pathname.md)にあります。
`uiop/utility` 自身のマクロ — [`uiop:if-let`](macros/uiop-if-let.md)、
`uiop:nest`、`uiop:while-collecting`、`uiop:with-upgradability` など — は
[そのページ](uiop/utility.md#macros)にあります。

## 未実装のもの

これ以外のエクスポートは**解決はされ、操作名とともに `uiop:not-implemented-error`
をシグナルします**。一覧を登録している理由はまさにここにあります: uiop の未実装部分に
到達したプログラムは、ライブラリの奥から出てくる `undefined function` ではなく 1 つの
明確な答えを受け取り、ハンドラで捕捉できます:

```console
$ rontolisp -e '(uiop:run-program "ls")'
Unhandled condition: Not (currently) implemented on rontolisp: UIOP/RUN-PROGRAM:RUN-PROGRAM
```

```lisp
(handler-case (uiop:run-program "ls")
  (uiop:not-implemented-error () :cannot))   ; => :CANNOT
```

この振る舞いは 4 つのバックエンドすべてで同一です — インタプリタ、JVM、2 つの WASM
出力のいずれも同じコンディションを同じレポートでシグナルします。

## rontolisp の追加分

本家がそこにエクスポートしていない名前が 2 つ `uiop` にあります:

- `uiop:namestring` — 本家は Common Lisp のものを*継承*しているだけですが、ここでは
  エクスポートされており、[`namestring`](functions/namestring.md) そのものです。
  どちらの綴りも 1 つの関数を指します。
- [`uiop:when-let`](macros/uiop-when-let.md) と
  [`uiop:when-let*`](macros/uiop-when-let-star.md) — alexandria の名前ですが、
  すでに使っているプログラムがあるため残しています。本家 UIOP は `if-let` だけを
  エクスポートしています。
