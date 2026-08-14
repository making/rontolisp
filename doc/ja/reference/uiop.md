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
| `uiop/package` | シンボルとパッケージの操作 (`find-symbol*`、`intern*`、`define-package`) | 2 / 31 |
| `uiop/package-local-nicknames` | パッケージローカルニックネーム API | 1 / 3 |
| `uiop/package*` | `uiop/package` が定義するがエクスポートしない 3 つのコンディション・型名 | 0 / 3 |
| `uiop/utility` | 移植性のあるヘルパ (`emptyp`、`split-string`、`if-let`、`not-implemented-error`) | 7 / 68 |
| `uiop/version` | バージョン比較と非推奨コンディション | 1 / 15 |
| `uiop/os` | ホストの識別、環境変数、作業ディレクトリ | 1 / 22 |
| `uiop/pathname` | パス名の代数 (`merge-pathnames*`、`ensure-directory-pathname`) | 2 / 50 |
| `uiop/filesystem` | ファイルシステムの探索・走査・変更 | 7 / 32 |
| `uiop/stream` | ファイル内容、一時ファイル、エンコーディング、標準ストリーム | 3 / 66 |
| `uiop/image` | コマンドライン、終了、ダンプフック | 1 / 30 |
| `uiop/launch-program` | 非同期のサブプロセス | 0 / 19 |
| `uiop/run-program` | 同期のサブプロセス | 0 / 7 |
| `uiop/lisp-build` | `compile-file*` と遅延警告 | 0 / 44 |
| `uiop/configuration` | XDG パスと設定ファイルの探索 | 0 / 38 |
| `uiop/backward-driver` | 非推奨の別名 | 0 / 7 |

エクスポートの完全な一覧は
`src/main/resources/am/ik/rontolisp/uiop-exports.txt` としてチェックインされています
(1 行 1 エクスポート: サブパッケージ、シンボル、本家での定義形式)。上の数値はこの一覧に
対して測定されるので、両者は常に一緒に動きます。

## 実装済みのもの

各名前は個別のページにリンクしています。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `uiop:getenv` | `(uiop:getenv "PATH")` | 環境変数の値を文字列として、未設定の場合は `nil` を返します。Common Lisp に `getenv` がないためここに置かれています。すべてのバックエンドで動作します。WASM は Preview 1 では実際のホスト環境を、`--component` モードでは `wasi:cli/environment@0.3.0` を読みます (wasmtime に `--env`/`-S inherit-env` を渡してください) |
| `uiop:file-exists-p` | `(uiop:file-exists-p "f.txt")` | ファイルが存在すればそのパス名、存在しなければ `nil` — `probe-file` と同じ契約であり、すべてのバックエンドでその基本操作へ落とされます |
| `uiop:directory-exists-p` | `(uiop:directory-exists-p "src/")` | ディレクトリが存在すれば（末尾に `/` を付けた）そのパス名、存在しなければ `nil` — `file-exists-p` のディレクトリ版であり、空のディレクトリと存在しないディレクトリを区別できる唯一の手段です |
| `uiop:directory-files` | `(uiop:directory-files "db/" "*.up.sql")` | ディレクトリのうちディレクトリでないエントリ — `(directory "db/*.*")` からサブディレクトリを除いたものです。UIOP の省略可能な第 2 引数 (名前と型のみのワイルドカードのパス名文字列) は `directory` とまったく同じ規則で絞り込みます。省略するとすべてを一覧し、ディレクトリ部分を含むパターンはエラーです |
| `uiop:subdirectories` | `(uiop:subdirectories "src/")` | ディレクトリのサブディレクトリを、それぞれ末尾に `/` を付けて返します |
| `uiop:collect-sub*directories` | `(uiop:collect-sub*directories "src/" (constantly t) (constantly t) #'print)` | ディレクトリツリーを走査します。`collectp` が `collector` へ渡すものを、`recursep` が降りていく先を決めます。渡されるディレクトリはルートも含めてすべてディレクトリ形式です |
| `uiop:read-file-string` | `(uiop:read-file-string "db/up.sql")` | ファイルの内容全体を 1 つの文字列として返します。ファイルを入力用に開けるすべてのバックエンドで動きます。lite 版: 本家 UIOP の `&rest` キーワードは受け付けて無視します (`:external-format` は rontolisp には存在せず、どのバックエンドも UTF-8 で読みます) |
| `uiop:merge-pathnames*` | `(uiop:merge-pathnames* "b.txt" "/tmp/")` | `#P"/tmp/b.txt"` — デフォルトを考慮したパス名のマージ。4 つのバックエンドすべてで動作します |
| `uiop:ensure-directory-pathname` | `(uiop:ensure-directory-pathname "src")` | `#P"src/"` — ディレクトリ形式のパス名。これに対してマージすると末尾に追加されます |
| `uiop:default-temporary-directory` | `(uiop:default-temporary-directory)` | `$TMPDIR` をディレクトリ形式で。環境変数が空の場合 (`--env` なしの 2 つの WASM バックエンド) は `#P"/tmp/"` |
| `uiop:delete-file-if-exists` | `(uiop:delete-file-if-exists "scratch.txt")` | ファイルを削除します。存在しない場合はシグナルではなく `nil` を返します — UIOP がこれをエクスポートしている理由そのものです |
| `uiop:native-namestring` | `(uiop:native-namestring #P"/tmp/x")` | `"/tmp/x"` — パス名のホスト OS の綴り。ここでは名前文字列そのものなので `namestring` と同じです |
| `uiop:add-package-local-nickname` | `(uiop:add-package-local-nickname '#:j '#:com.example.pkg)` | パッケージ短縮名を登録 (lite: グローバル、パッケージごとのスコープなし)。リテラルなトップレベル呼び出しはコンパイル時ディレクティブなので、すべてのバックエンドで動作します |
| `uiop:emptyp` | `(uiop:emptyp "")` | `nil` および長さ 0 のベクタ・文字列に対して `t`、それ以外は `nil` |
| `uiop:first-char` | `(uiop:first-char "hello")` | `#\h` — 空でない文字列の最初の文字。空文字列や文字列以外では `nil` |
| `uiop:last-char` | `(uiop:last-char "hello")` | `#\o` — 空でない文字列の最後の文字。空文字列や文字列以外では `nil` |
| `uiop:split-string` | `(uiop:split-string "a.b.c" :separator ".")` | `("a" "b" "c")` — `:separator` のいずれかの文字で分割(上流のセマンティクス: 右から左へ走査し、`:max` は分割されなかった先頭部を残します) |
| `uiop:symbol-call` | `(uiop:symbol-call :cl :+ 1 2)` | 実行時にパッケージから名前を引いて適用します — 依存関係に持たないシステムを呼ぶための UIOP の遅延束縛呼び出しです |
| `uiop:not-implemented-error` | `(uiop:not-implemented-error "chdir")` | 同名のコンディションを、対象の操作名とともにシグナルします。以下の未実装メンバがすべてシグナルするのがこれです |
| `uiop:parameter-error` | `(uiop:parameter-error "~S: bad ~S" 'f 1)` | `uiop:parameter-error` をシグナルします: 操作は存在するが、そのパラメータ (の組み合わせ) は受け付けない、という意味です |
| `uiop/image:print-condition-backtrace` | `(uiop/image:print-condition-backtrace c :stream s)` | コンディションのレポートを出力します (ライト版: どのバックエンドも Lisp レベルのコールスタックを持たないため、出力されるのはコンディション自体だけです) |

4 つのメンバは**マクロ**で、呼び出されるのではなくコンパイラが展開します:
[`uiop:if-let`](macros/uiop-if-let.md)、
`uiop:with-temporary-file`、
[`uiop:with-deprecation`](macros/uiop-with-deprecation.md)、
`uiop:define-package` (リテラルなトップレベル呼び出しは `defpackage` と同様に処理されます)。

## 未実装のもの

これ以外のエクスポートは**解決はされ、操作名とともに `uiop:not-implemented-error`
をシグナルします**。一覧を登録している理由はまさにここにあります: uiop の未実装部分に
到達したプログラムは、ライブラリの奥から出てくる `undefined function` ではなく 1 つの
明確な答えを受け取り、ハンドラで捕捉できます:

```console
$ rontolisp -e '(uiop:chdir "/tmp")'
Unhandled condition: Not (currently) implemented on rontolisp: UIOP/OS:CHDIR
```

```lisp
(handler-case (uiop:chdir "/tmp")
  (uiop:not-implemented-error () :cannot))   ; => :CANNOT
```

この振る舞いは 4 つのバックエンドすべてで同一です — インタプリタ、JVM、2 つの WASM
出力のいずれも同じコンディションを同じレポートでシグナルします。

## rontolisp の追加分

本家がそこにエクスポートしていない名前が 3 つ `uiop` にあります:

- `uiop:namestring` — 本家は Common Lisp のものを*継承*しているだけですが、ここでは
  エクスポートされており、[`namestring`](functions/namestring.md) そのものです。
  どちらの綴りも 1 つの関数を指します。
- [`uiop:when-let`](macros/uiop-when-let.md) と
  [`uiop:when-let*`](macros/uiop-when-let-star.md) — alexandria の名前ですが、
  すでに使っているプログラムがあるため残しています。本家 UIOP は `if-let` だけを
  エクスポートしています。
- `uiop::get-pathname-defaults` — 本家 UIOP でも内部シンボルです (だから二重コロン)。
  すべてのバックエンドで `""` を返します。相対パスはホストの作業ディレクトリを基準に
  解決され、`""` はまさにそれを指す名前文字列なので、
  `(merge-pathnames x (uiop::get-pathname-defaults))` は `x` になります。
