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
| `uiop/package` | シンボルとパッケージの操作 (検索・インターンと `define-package` 支援は実装、ホットアップグレード用の移動系はシグナル) | 31 / 31 |
| `uiop/package-local-nicknames` | パッケージローカルニックネーム API | 3 / 3 |
| `uiop/package*` | `uiop/package` が定義するがエクスポートしない 3 つのコンディション・型名 | 3 / 3 |
| [`uiop/utility`](uiop/utility.md) | 移植性のあるヘルパ (`strcat`、`split-string`、`if-let`、`not-implemented-error`) | 68 / 68 |
 | `uiop/version` | バージョン比較と非推奨コンディション | 15 / 15 |
| [`uiop/os`](uiop/os.md) | ホストの識別、環境変数、作業ディレクトリ | 22 / 22 |
| [`uiop/pathname`](uiop/pathname.md) | パス名の代数 (`subpathname`、`parse-unix-namestring`、`enough-pathname`) | 50 / 50 |
| [`uiop/filesystem`](uiop/filesystem.md) | ファイルシステムの探索・走査・変更 | 32 / 32 |
| `uiop/stream` | ファイル内容、一時ファイル、エンコーディング、標準ストリーム | 38 / 66 |
| [`uiop/image`](uiop/image.md) | 終了、致命的コンディション、ダンプフック、コマンドライン | 30 / 30 |
| `uiop/launch-program` | 非同期のサブプロセス | 0 / 19 |
| `uiop/run-program` | 同期のサブプロセス | 0 / 7 |
| `uiop/lisp-build` | `compile-file*` と遅延警告 | 1 / 44 |
| `uiop/configuration` | XDG パスと設定ファイルの探索 | 0 / 38 |
 | `uiop/backward-driver` | 非推奨の別名 (`coerce-pathname`、`version-compatible-p`) | 2 / 7 |

エクスポートの完全な一覧は
`src/main/resources/am/ik/rontolisp/uiop-exports.txt` としてチェックインされています
(1 行 1 エクスポート: サブパッケージ、シンボル、本家での定義形式)。上の数値はこの一覧に
対して測定されるので、両者は常に一緒に動きます。

## 実装済みのもの

専用のページを持つサブパッケージは 5 つで、いずれも完全に実装済みです。uiop の
他のすべてがその上に書かれている移植性ヘルパ群 `uiop/utility` の 68 個
([uiop/utility](uiop/utility.md))、パス名の代数 `uiop/pathname` の 50 個
([uiop/pathname](uiop/pathname.md))、そしてホストの識別・環境変数・作業ディレクトリ
の 22 個 `uiop/os` ([uiop/os](uiop/os.md)。[`uiop:getenv`](functions/uiop-getenv.md)
もここにあります) です。4 つめは [uiop/image](uiop/image.md) で、
[`uiop:quit`](uiop/image.md#exiting) が 4 つのバックエンドすべてでステータスコード
付きのプロセス終了を行い、[`uiop:command-line-arguments`](uiop/image.md#the-command-line)
が 4 つすべてで起動時の引数を読みます。致命的コンディション・バックトレース・
イメージフックの各族もここにあります。5 つめは
[`uiop/filesystem`](uiop/filesystem.md) で、`probe-file*`・`truename*`・`directory*`
が 4 つのバックエンドすべてで検査と走査を行い、`getenv-*` 一族が環境変数からパス名を
読み、シンボリックリンクは正直な恒等関数であり、4 つの変更操作は基本操作のあるところで
動作します (2 つの WASM バックエンドでは基本操作自身の呼び出し時エラーを通知)。
残りは以下のとおりです。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `uiop:read-file-string` | `(uiop:read-file-string "db/up.sql")` | ファイルの内容全体を 1 つの文字列として返します。ファイルを入力用に開けるすべてのバックエンドで動きます。lite 版: 本家 UIOP の `&rest` キーワードは受け付けて無視します (`:external-format` は rontolisp には存在せず、どのバックエンドも UTF-8 で読みます) |
| `uiop:read-file-lines`, `uiop:read-file-line`, `uiop:read-file-forms`, `uiop:read-file-form` | `(uiop:read-file-lines "db/seed.sql")` | ファイルを行のリスト・1 行 (`:at`)・フォームのリスト・1 フォーム (`:at`) として読みます — いずれも `call-with-input-file` と対応する `slurp-stream-*` 読み取り経由です |
| `uiop:safe-read-file-line`, `uiop:safe-read-file-form`, `uiop:safe-read-from-string` | `(uiop:safe-read-from-string "(+ 1 2)")` | 安全構文の読み取り群: `with-safe-io-syntax` (`*read-eval*` は nil) の下でファイルや文字列を読みます。`safe-read-from-string` はオブジェクトのみを返します |
| `uiop:with-input-file`, `uiop:call-with-input-file`, `uiop:with-output-file`, `uiop:call-with-output-file` | `(uiop:with-output-file (out "x.txt") (write-line "hi" out))` | ファイルを開きストリームを本体・thunk に渡して実行します。lite 版: `:element-type` の既定は `'character`、`:external-format` は `:utf-8`、`:if-exists` は `:supersede` です |
| `uiop:with-input`, `uiop:input-string`, `uiop:with-output`, `uiop:output-string` | `(uiop:with-output (o nil) (write-string "x" o))` | ストリーム指示子 (`nil`・`t`・ストリーム・文字列・パス名) をストリームに強制します。`nil` 出力は文字列に集めます。文字列への書き込みはシグナルします |
| `uiop:copy-file`, `uiop:concatenate-files`, `uiop:copy-stream-to-stream` | `(uiop:copy-file "a" "b")` | ファイル複写・連結・ストリーム複写 — 双方向バイナリで、出力先は切り詰めます。`:linewise` 複写は常に行末に改行を付けます |
| `uiop:eval-input`, `uiop:eval-thunk`, `uiop:standard-eval-thunk` | `(uiop:eval-input "(+ 1 2) (* 3 4)")` | ストリーム指示子や文字列からフォームを読み評価します。最後のフォームの値が答えです |
| `uiop:println`, `uiop:writeln`, `uiop:format!`, `uiop:safe-format!`, `uiop:finish-outputs` | `(uiop:println "hi")` | 末尾改行付き出力 (`println` は `princ`・`writeln` は `write` 経由)、前後でフラッシュする `format`、フラッシュ本体。`safe-format!` は決してシグナルしません |
| `uiop:file-stream-p`, `uiop:file-or-synonym-stream-p` | `(uiop:file-stream-p s)` | ストリーム値に対する厳密な種別判定で、同義ストリームを再帰的にたどります |
| `uiop:compile-file-type` | `(uiop:compile-file-type)` | `nil` — コンパイル済みファイルが持つパス名の型。ここには `compile-file` が存在せずそのような型もないため、「このパスは fasl か?」を問う呼び出し側はソースパスに対して「いいえ」を得ます |
| `uiop:default-temporary-directory` | `(uiop:default-temporary-directory)` | `$TMPDIR` をディレクトリ形式で。環境変数が空の場合 (`--env` なしの 2 つの WASM バックエンド) は `#P"/tmp/"` |
| `uiop:add-package-local-nickname` | `(uiop:add-package-local-nickname '#:j '#:com.example.pkg)` | パッケージ短縮名を登録 (lite: グローバル、パッケージごとのスコープなし)。リテラルなトップレベル呼び出しはコンパイル時ディレクティブなので、すべてのバックエンドで動作します |
| `uiop:symbol-call` | `(uiop:symbol-call :cl :+ 1 2)` | 実行時にパッケージから名前を引いて適用します — 依存関係に持たないシステムを呼ぶための UIOP の遅延束縛呼び出しです |
| `uiop:find-package*` | `(uiop:find-package* :cl)` | パッケージを返すか、存在しなければ `uiop:no-such-package-error` (第 2 引数 nil で nil) |
| `uiop:find-symbol*` | `(uiop:find-symbol* "CAR" :cl)` | `find-symbol` と同様にシンボルと状態を返す (エラーなし指定で見つからなければ nil, nil) |
| `uiop:intern*` | `(uiop:intern* "NAME" :my-pkg)` | 文字列化した名前をインターン (エラーなし指定でパッケージがなければ nil) |
| `uiop:export*`、`uiop:import*` | `(uiop:export* "NAME" :my-pkg)` | インターンしてエクスポート、およびインポート — レジストリが変更可能な範囲で実装 (コンパイル済みバックエンドでは CL 自身の実行時操作と同様に引数の評価と `t` のみ) |
| `uiop:make-symbol*` | `(uiop:make-symbol* "X")` | 文字列からはインターンされていないシンボル、シンボルからはコピー |
| `uiop:home-package-p` | `(uiop:home-package-p s p)` | シンボルのホームパッケージがそのパッケージかどうか |
| `uiop:symbol-package-name` | `(uiop:symbol-package-name 'car)` | `"CL"` (インターンされていないシンボルは nil) |
| `uiop:standard-common-lisp-symbol-p` | `(uiop:standard-common-lisp-symbol-p 'car)` | エクスポートされた `cl` シンボルかどうか |
| `uiop:symbol-shadowing-p` | `(uiop:symbol-shadowing-p s p)` | 常に nil — ここに実行時のシャドウイングは存在しません |
| `uiop:package-names` | `(uiop:package-names :cl)` | 名前とニックネーム |
| `uiop:packages-from-names` | `(uiop:packages-from-names '(:a :b))` | 名前が指すパッケージ。重複除去・欠番除外つき |
| `uiop:fresh-package-name` | `(uiop:fresh-package-name)` | どのパッケージも使っていないパッケージ名 |
| `uiop:rename-package-away` | `(uiop:rename-package-away p)` | `rename-package` が効く範囲で新名へリネーム |
| `uiop:package-definition-form` | `(uiop:package-definition-form :my-pkg)` | 宣言済みメンバを再現する `defpackage` フォーム (`:error nil` で欠番は nil) |
| `uiop:parse-define-package-form` | `(uiop:parse-define-package-form pkg clauses)` | `define-package` ヘッダが解析される `ensure-package` 引数 |
| `uiop:package-designator` | `(typep x 'uiop:package-designator)` | デザイネータの型、および `uiop:no-such-package-error` の datum リーダー |
| `uiop:no-such-package-error` | `(handler-case ... (uiop:no-such-package-error (c) ...))` | パッケージ欠番時に `find-package*` がシグナルする `type-error` コンディション |
| `uiop:define-package-style-warning` | — | パッケージ再定義がシグナルするはずの `style-warning` |
| `uiop:package-local-nicknames` | `(uiop:package-local-nicknames :my-pkg)` | そのパッケージを指すグローバルニックネームの一覧 (lite: ニックネームはグローバルでパッケージごとのスコープなし) |
| `uiop:remove-package-local-nickname` | `(uiop:remove-package-local-nickname '#:nick)` | ニックネームの登録解除 (`t`)、なければ nil。リテラルなトップレベル呼び出しはすべてのバックエンドで動作します |

完全実装済みサブパッケージ以外の 8 つのメンバは**マクロ**で、呼び出されるのではなく
コンパイラが展開します: `uiop:with-temporary-file`、
[`uiop:with-deprecation`](macros/uiop-with-deprecation.md)、
`uiop:define-package` (リテラルなトップレベル呼び出しは `defpackage` と同様に処理されます)。
`uiop/stream` の 5 つ — `uiop:with-input-file`、`uiop:with-output-file`、
`uiop:with-input`、`uiop:with-output`、`uiop:with-safe-io-syntax` — は対応する
`call-with-*` 関数への展開です。
`uiop/pathname` の 2 つのマクロ — `uiop:with-pathname-defaults` と
`uiop:with-enough-pathname` — は[そのページ](uiop/pathname.md)にあります。
`uiop/filesystem` のマクロ — `uiop:with-current-directory` — は
[そのページ](uiop/filesystem.md)にあります。
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
