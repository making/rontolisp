# パッケージ

rontolispには、一連の組み込みパッケージと[`defpackage` によるユーザー定義パッケージ](#user-defined-packages-defpackage)を持つ小さな名前空間(パッケージ)システムがあります:

- **`cl`** — 標準パッケージ。すべての組み込み関数、マクロ、特殊形式、および `*package*` 変数がここに属します。
- **`cl-user`** — デフォルトの作業パッケージ。`cl` を *使用* するため、標準シンボルを修飾なしで利用できます。プログラム開始時のカレントパッケージです。ユーザ定義はここに置かれます。
- **`rontolisp`** — 実装固有のシンボルのためのパッケージ。`rl` は組み込みのニックネームです。`cl` を **使用しません**。`version` 関数を所有します。
- **`linalg`** — numpy スタイルのベクトル・行列演算(`linalg:zeros`、`linalg:matmul`、`linalg:solve` など)。Lisp ソースで一度だけ実装され、すべてのバックエンドで利用できます。`la` は組み込みのニックネームです。`cl` を **使用しません**。[ベクトルと行列ガイド](../guides/linear-algebra.md)を参照してください。
- **`torch`** — `linalg` カーネル上の、逆方向自動微分、`nn` スタイルのモジュール層、オプティマイザ、学習ループの補助を備えた PyTorch スタイルのテンソル (`torch:tensor`、`torch:matmul`、`torch:backward`、`torch:linear`、`torch:cross-entropy-loss`、`torch:adam`、`torch:step` など)。Lisp ソースで一度だけ実装され、すべてのバックエンドで利用できます。`cl` を **使用しません**。[ニューラルネットワークガイド](../guides/neural-networks.md)を参照してください。
- **`java`** — リフレクションによる Java 連携。JVM インタプリタ (`java -jar rontolisp.jar`) でのみ使え、コンパイラやネイティブバイナリでは使えません。`cl` を **使用しません**。`new`、`call`、`static`、`field`、`proxy` を所有します。[Java 連携ガイド](../guides/java-interop.md)を参照してください。
- **`objc`** — Foreign Function API による Objective-C ランタイムと AppKit へのバインディング。macOS のインタプリタ (`java -jar rontolisp.jar` と `rontolisp` ネイティブバイナリ) で使え、コンパイラでは使えません。`cl` を **使用しません**。`class`、`send`、`define-class`、`on-main`、`string`、`address`、`objectp` を所有します。[macOS GUI ガイド](../guides/objc-appkit.md)を参照してください。
- **`appkit`** — `objc` の上の Cocoa ウィジェット層 (`appkit:window`、`appkit:label`、`appkit:button`、...)。`linalg` と同様に Lisp ソースで一度だけ実装され、初回使用時に読み込まれます。`cl` を **使用しません**。同じガイドを参照してください。
- **`asdf`** — ASDF の限定的な API 互換サブセット(システム定義): `defsystem` と `load-system`。`cl` を **使用しません**。[システムガイド](../guides/asdf-systems.md)を参照してください。
- **`ql`** — Quicklisp の限定的な API 互換サブセット: `quickload` は本物の Quicklisp ディストリビューションからシステムをダウンロードし、`asdf` サブセットを経由してロードします。`update-dist` は dist の index を更新します。`quicklisp` は組み込みのニックネームです。`cl` を **使用しません**。[システムガイド](../guides/asdf-systems.md#downloading-with-quickload)を参照してください。
- **`ql-dist`** — Quicklisp のディストリビューション管理パッケージ。プログラムが書くメンバーは `install-dist` の 1 つで、Quicklisp 形式の別のディストリビューション ([Ultralisp](https://ultralisp.org/) や任意の distinfo URL) を `ql:quickload` の検索対象に加えます。`cl` を **使用しません**。[システムガイド](../guides/asdf-systems.md#adding-a-dist-ultralisp)を参照してください。
- **`uiop`** — ASDF の移植性レイヤ。15 個のサブパッケージ (`uiop/os`、`uiop/pathname` など) として登録され、`uiop` がそれらを再エクスポートするので、メンバのどちらの綴りも同じシンボルを指します。`cl` を **use しません**。[uiop パッケージ](uiop.md) を参照してください。
- **`usocket`** — `rontolisp:tcp-*` ソケット組み込みの上に載った [usocket](https://github.com/usocket/usocket) 互換シム(`usocket:socket-connect`、`usocket:socket-listen` など)。Lisp ソースで一度だけ実装され、組み込み ASDF システム `"usocket"` としても登録されています。`cl` を **使用しません**。[TCPソケットガイド](../guides/tcp-sockets.md#the-usocket-compatible-shim)を参照してください。

シンボルはパッケージ修飾子で参照できます: `package:symbol`(例: `cl:car`、`rontolisp:version`)はパッケージの
external(export 済み)シンボルに届き、`package::symbol` は internal を含む任意のシンボルに届きます —
Common Lisp と同じシングル/ダブルコロンの区別です([external シンボルと internal シンボル](#external-and-internal-symbols)を参照)。`*package*`
はカレントパッケージを保持し(`find-package` が返すパッケージキーワードなので `(eq *package* (find-package ...))` が成り立ちます)、`(in-package name)`
はそれを切り替えます(名前はキーワード、シンボル、または文字列です: `:rontolisp`、`rontolisp`、`"rontolisp"`)。Common Lisp と同様に `*package*` はフォームの実行時に読まれる動的変数です: 関数は呼び出し時点のカレントパッケージを読み、`(let ((*package* ...)) ...)` はその範囲で束縛し、`with-standard-io-syntax` は `cl-user` に束縛し、`setq` で代入できます。標準の Common Lisp 名
`common-lisp` と `common-lisp-user` は `cl` と `cl-user` の組み込み **ニックネーム** なので、ポータブルな
`(:use #:common-lisp)` clause や `common-lisp:car` の参照も解決されます。短縮名 `rl` と `la` は `rontolisp` と `linalg`
の、`quicklisp` は `ql` の組み込みニックネームです。ユーザーパッケージは `defpackage` の
`:nicknames` clause で独自のニックネームを登録できます。

```lisp
(print *package*)              ; => :CL-USER
(print (rontolisp:version))    ; そのビルド自身のバージョン plist
(print (gethash "n" (rl:json-parse "{\"n\": 41}")))  ; => 41
(print (la:to-list (la:from-list '(1 2 3))))        ; => (1.0 2.0 3.0)
```

[`rontolisp:version`](functions/rontolisp-version.md) は `rontolisp --version` と同じ情報を、プロパティリスト `(:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")` として返します。タイムスタンプとリビジョンは実行中のビルドに由来するため、ここでは固定の結果を示していません。

`rontolisp` パッケージは `cl` を使用しないため、その中では標準シンボルを `cl:` で修飾する必要がありますが、(所有している)`version` は修飾なしで利用できます:

```console
(in-package rontolisp)
(cl:print (version))           ; the rontolisp package owns version
(cl:print (cl:car '(1 2)))     ; standard symbols need the cl: prefix here
;; (car '(1 2)) would be an error: Undefined symbol: car (use cl:car)
```

デフォルトパッケージ `cl-user` は空で `cl` を使用するため、通常のプログラムでは修飾子は不要です。

## external シンボルと internal シンボル

Common Lisp と同様、各パッケージは external(export 済み)シンボルと
internal シンボルを区別し、2 つの修飾子の綴りで届く範囲が異なります:

- `package:symbol`(シングルコロン)は **external** シンボルのみを参照します。
- `package::symbol`(ダブルコロン)は internal を含むパッケージの **任意の**
  シンボルを参照します。

組み込みパッケージはドキュメント化された API 全体を export しています:
標準の `cl` シンボルはすべて external で、本マニュアルに載っている
`rontolisp`・`java` の関数もすべて external です(そのためダブルコロンが
*必須* になることはありませんが、`rontolisp::version` も受け付けられ、
`rontolisp:version` と同じ意味になります)。internal シンボルは `%`
プレフィックス規約に従います — 例えば
[`rontolisp:json-parse`](functions/rontolisp-json-parse.md) の背後にある
固定引数ヘルパー `rontolisp::%json-parse` — これらは実装詳細であり、予告なく
変わることがあります。`cl-user` は Common Lisp の `COMMON-LISP-USER`
パッケージと同じく何も export しないため、まれに `cl-user` のシンボルに
修飾子が必要な場合は `cl-user::name` と書きます。

external でないシンボルへのシングルコロンでの参照は read/コンパイル時に
エラーになります:

```console
CL-USER> (rontolisp:%json-parse "1")
Error: The symbol %json-parse is not external in the rontolisp package (use rontolisp::%json-parse)
```

パッケージの export セットは定義時に決まり — 組み込みパッケージはドキュメント化
された API を、ユーザー定義パッケージは `(:export ...)` clause の内容を export
します — その後は [`export`](functions/export.md)/[`unexport`](functions/unexport.md)
で調整できます。`(in-package rontolisp)` が有効な間に定義されたシンボルは
`rontolisp` パッケージに internal シンボルとして intern されるため、他の
パッケージからはダブルコロンで参照する必要があります。

export が変えるのはシンボルに*到達できる*修飾子であって、どのシンボルであるかは
変わりません。そのため `export` は公開する定義の前でも後でも構いません。Common
Lisp との相違が1点あります: 最初に名前が現れた後で export されたシンボルは、
*表示*時にダブルコロンのままになります — ここでは修飾子は表示時に計算されるので
はなくシンボルに保持されているためです — が、どちらの綴りも同じシンボルを指します。

## ユーザー定義パッケージ(defpackage)

新しいパッケージは [`defpackage`](special-forms/defpackage.md) で定義します:

```lisp
(defpackage :mypkg (:use :cl) (:export :greet))
(in-package :mypkg)
(defun greet (name) (concatenate 'string "hello, " name))
(defun helper () 42)                  ; not exported: internal
(in-package :cl-user)
(print (mypkg:greet "world"))         ; => "hello, world"
(print (mypkg::helper))               ; => 42
```

`in-package` と同様に、`defpackage` は **read/コンパイル時に消費されるリテラルな
トップレベルディレクティブ** であり、パッケージは使用より前に、ソース順に
定義されます。サポートされる clause は `(:use package...)`、
`(:export symbol...)`、`(:nicknames name...)`、
`(:import-from package symbol...)`、`(:shadow symbol...)`、および受理されるが
無視される `(:documentation "...")`/`(:size n)` です。名前と clause の引数は
キーワード、裸のシンボル、文字列、または uninterned シンボル(`#:name`、
ポータブルな defpackage の慣用形)です。`:shadow` された名前はパッケージ内では
常にそのパッケージ自身のシンボルに解決され、`cl`(や使用パッケージ)の同名
シンボルには決して解決されません — これによりライブラリは独自の
`digit-char-p` や `defconstant` を定義できます。`:shadowing-import-from` は
エラーで、それ以外の clause、既存パッケージの再定義、まだ存在しないパッケージの
使用もエラーです。

- `:use` は、使用するパッケージの **external** シンボルを修飾なしで見えるように
  します(Common Lisp と同様) — 使用先パッケージの internal シンボルには
  依然としてダブルコロンが必要です。`:use` clause がなければ何も継承されない
  (SBCL と同様)ため、`cl` シンボルには `cl:` プレフィックスが必要になります。
  通常のパッケージでは `(:use :cl)`(ポータブルには `(:use #:common-lisp)`)と
  書いてください。複数の使用先パッケージが
  同じ名前を export している場合、`:use` 順で最初のパッケージが優先されます
  (Common Lisp はコンフリクトをシグナルします)。
- `:export` はパッケージの external シンボルを宣言します。後から intern される
  シンボル(`(in-package name)` の下で定義され `:export` clause に含まれない
  `defun` や自由変数)は、組み込みパッケージとまったく同様に internal です。
- `:nicknames` は、正規名が解決されるすべての場所(修飾子、`in-package`、`:use`
  など)で解決される別名を登録します。既存のパッケージやニックネームと衝突する
  ニックネームはエラーです — 組み込みニックネーム(`common-lisp`、
  `common-lisp-user`、`rl`、`la`、`quicklisp`)も組み込みパッケージ名と同様に
  予約されています。
- `:import-from` は、パッケージ全体を use せずに、1 つのパッケージの指定シンボル
  だけを修飾なしで見えるようにします。解決はテキストベースです: import された
  名前はソースパッケージの正規表記に解決されるため、import して re-export した
  シンボルの `mypkg:name` は元の定義を参照します。

[`use-package`](functions/use-package.md) は `:use` clause の実行時版で、
`in-package` と同じ読み込み/コンパイル時のルールに従います: リテラルな
トップレベルの `(use-package :mypkg)` は、それ以降のフォームに対して現在の
パッケージの use リストを広げます(すべてのバックエンドで動作します)。

[`export`](functions/export.md)、[`unexport`](functions/unexport.md)、
[`import`](functions/import.md) も同じルールに従います。`make-package` と
`rename-package` は利用できず、(トップレベルでない)他のフォームの中の
`defpackage` はエラーです。

パッケージは読み込み/コンパイル時に(ソース順で)解決されるため、`in-package`
はトップレベルのディレクティブです: ソース中のシンボルがどのパッケージに属するかは、その上にある `in-package` で決まり、実行時の `*package*` への `setq` では決まりません(コンパイル出力ではファイル全体が実行前に解決されます。インタプリタはトップレベルフォームに到達するたびに解決するため、そこでは実行時の代入が後続のフォームに影響します)。コンパイル出力では、実行時に読み込まれたファイルのパッケージディレクティブは処理されません。`rontolisp`
パッケージの関数(`version` ...)は第一級の値として利用できません(`mapcar`/`funcall`
に渡せません)。また `cl` を使用しないパッケージ内では、`cl`
シンボル名をローカル変数としてシャドウしてはいけません。

## rontolisp パッケージの拡張

`rontolisp` パッケージが所有するシンボルは **実装固有であり、Common Lispの一部ではありません**。`rontolisp:`
修飾子で参照する(または `(in-package rontolisp)`
の後に修飾なしで使用する)必要があります。`version` に加え、このパッケージは
`rontolisp:fetch` (future を返す) と、`rontolisp:await` (解決)、
`rontolisp:futurep` (型述語) を通じて非同期の外向きHTTPを提供し、さらに
[`rontolisp:json-parse`](functions/rontolisp-json-parse.md) /
[`rontolisp:json-stringify`](functions/rontolisp-json-stringify.md)
によるJSON変換(JavaScriptの `JSON.parse`/`JSON.stringify` 相当)を提供します。これらはすべて
[関数](functions.md#rontolisp-package-functions)
リファレンスに独自のページを持ち、完全な
[`rontolisp:fetch`](functions/rontolisp-fetch.md) /
[`rontolisp:await`](special-forms/rontolisp-await.md) /
[`rontolisp:futurep`](functions/rontolisp-futurep.md) ドキュメントも含まれます。

このパッケージのメンバーのうち2つは関数でもマクロでもなく、read 時リテラルです:
`rontolisp:current-file` と `rontolisp:current-line` で、リーダがそのシンボルの位置に置換します。`in-package`
ディレクティブの解釈より前に解決されるため、これらは上記の規則の例外で、常に修飾付きで書く必要があります。
[ソース位置リテラル](data-types.md#source-position-literals-rontolispcurrent-file-rontolispcurrent-line)を参照してください。
