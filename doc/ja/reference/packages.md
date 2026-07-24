# パッケージ

rontolispには、一連の組み込みパッケージと[`defpackage` によるユーザー定義パッケージ](#ユーザー定義パッケージdefpackage)を持つ小さな名前空間(パッケージ)システムがあります:

- **`cl`** — 標準パッケージ。すべての組み込み関数、マクロ、特殊形式、および `*package*` 変数がここに属します。
- **`cl-user`** — デフォルトの作業パッケージ。`cl` を *使用* するため、標準シンボルを修飾なしで利用できます。プログラム開始時のカレントパッケージです。ユーザ定義はここに置かれます。
- **`rontolisp`** — 実装固有のシンボルのためのパッケージ。`rl` は組み込みのニックネームです。`cl` を **使用しません**。`version`、`list-functions`、`list-macros`、`list-special-forms` の各関数を所有します。
- **`linalg`** — numpy スタイルのベクトル・行列演算(`linalg:zeros`、`linalg:matmul`、`linalg:solve` など)。Lisp ソースで一度だけ実装され、すべてのバックエンドで利用できます。`la` は組み込みのニックネームです。`cl` を **使用しません**。[ベクトルと行列ガイド](../guides/linear-algebra.md)を参照してください。
- **`java`** — リフレクションによる Java 連携。JVM インタプリタ (`java -jar rontolisp.jar`) でのみ使え、コンパイラやネイティブバイナリでは使えません。`cl` を **使用しません**。`new`、`call`、`static`、`field`、`proxy` を所有します。[Java 連携ガイド](../guides/java-interop.md)を参照してください。
- **`asdf`** — ASDF の限定的な API 互換サブセット(システム定義): `defsystem` と `load-system`。`cl` を **使用しません**。[システムガイド](../guides/asdf-systems.md)を参照してください。
- **`ql`** — Quicklisp の限定的な API 互換サブセット: `quickload` は本物の Quicklisp ディストリビューションからシステムをダウンロードし、`asdf` サブセットを経由してロードします。`quicklisp` は組み込みのニックネームです。`cl` を **使用しません**。[システムガイド](../guides/asdf-systems.md#quickload-でダウンロードする)を参照してください。
- **`usocket`** — `rontolisp:tcp-*` ソケット組み込みの上に載った [usocket](https://github.com/usocket/usocket) 互換シム(`usocket:socket-connect`、`usocket:socket-listen` など)。Lisp ソースで一度だけ実装され、組み込み ASDF システム `"usocket"` としても登録されています。`cl` を **使用しません**。[TCPソケットガイド](../guides/tcp-sockets.md#usocket-互換シム)を参照してください。

シンボルはパッケージ修飾子で参照できます: `package:symbol`(例: `cl:car`、`rontolisp:version`)はパッケージの
external(export 済み)シンボルに届き、`package::symbol` は internal を含む任意のシンボルに届きます —
Common Lisp と同じシングル/ダブルコロンの区別です([external シンボルと internal シンボル](#external-シンボルと-internal-シンボル)を参照)。`*package*`
はカレントパッケージの名前に評価され、`(in-package name)`
はそれを切り替えます(名前はキーワード、シンボル、または文字列です: `:rontolisp`、`rontolisp`、`"rontolisp"`)。標準の Common Lisp 名
`common-lisp` と `common-lisp-user` は `cl` と `cl-user` の組み込み **ニックネーム** なので、ポータブルな
`(:use #:common-lisp)` clause や `common-lisp:car` の参照も解決されます。短縮名 `rl` と `la` は `rontolisp` と `linalg`
の、`quicklisp` は `ql` の組み込みニックネームです。ユーザーパッケージは `defpackage` の
`:nicknames` clause で独自のニックネームを登録できます。

```lisp
(print *package*)              ; => cl-user
(print (rontolisp:version))    ; => (:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")
(print (gethash "n" (rl:json-parse "{\"n\": 41}")))  ; => 41
(print (la:to-list (la:from-list '(1 2 3))))        ; => (1.0 2.0 3.0)
```

`rontolisp:version` は `rontolisp --version` と同じ情報をプロパティリストとして返します。

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
> (rontolisp:%json-parse "1")
Error: The symbol %json-parse is not external in the rontolisp package (use rontolisp::%json-parse)
```

ランタイムの `export` 関数はありません — パッケージの export セットは定義時に
固定されます: 組み込みパッケージはドキュメント化された API を export し、
ユーザー定義パッケージは `(:export ...)` clause の内容を export します
([未対応の機能](../guides/missing-features.md)を参照)。`(in-package rontolisp)`
が有効な間に定義されたシンボルは `rontolisp` パッケージに internal
シンボルとして intern されるため、他のパッケージからはダブルコロンで参照する
必要があります。

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

ランタイムのパッケージ操作はありません: `make-package`、`export`、`import`、
`use-package`、`find-package` などは利用できず、(トップレベルでない)他の
フォームの中の `defpackage` はエラーです。

## パッケージのイントロスペクション

`rontolisp:list-functions`、`rontolisp:list-macros`、`rontolisp:list-special-forms`
はパッケージのシンボルをカテゴリ別に、アルファベット順にソートして返します。省略可能な引数はパッケージ指定子(キーワード、裸のシンボル、引用されたシンボル、または文字列:
`:cl`、`cl`、`'cl`、`"cl"`)で、デフォルトは `:cl` です。未知のパッケージはエラーです(`No such package: foo`)。

```lisp
(print (rontolisp:list-macros))
; => (and assert block case ccase cerror check-type complement complex cond decf declaim declare define-compiler-macro define-condition define-modify-macro define-setf-expander defsetf deftype destructuring-bind do do* documentation dolist dotimes ecase error etypecase eval-when flet format incf labels let* locally loop macrolet make-condition make-instance make-sequence multiple-value-bind multiple-value-call multiple-value-list multiple-value-setq nth-value or pop proclaim prog1 prog2 psetf psetq push pushnew remf restart-case return-from rotatef setf slot-value the time typecase unless warn when with-input-from-string with-open-file with-output-to-string write-char)
(print (rontolisp:list-special-forms))
; => (defclass defconstant defgeneric defmacro defmethod defpackage defparameter defstruct defun defvar function if in-package lambda let progn quote return setq while)
(print (length (rontolisp:list-functions)))
; => 302
(defun square (x) (* x x))
(print (rontolisp:list-functions :cl-user))
; => (square)
(print (rontolisp:list-functions :rontolisp))
; => (await fetch http-handler json-parse json-stringify list-functions list-macros list-special-forms query-param query-params tcp-accept tcp-connect tcp-listen tcp-local-address tcp-local-port tcp-peer-address tcp-peer-port tls-connect tls-listen tls-listen-pem url-decode url-encode url-path url-query version wit-error-payload wit-provide)
(print (rontolisp:list-functions :java))
; => (call field new proxy static)
```

この分類は関数名前空間に従います。ある名前が関数として列挙されるのは、`#'name`
を通じて関数値として使用できる場合に限られます(そのため `first`、`length`、`1+`
... はインライン展開でコンパイルされるにもかかわらず関数です)。一方
`list-macros`/`list-special-forms` は関数値を持たない演算子を列挙します。注記:

- `cl-user` の `list-functions` はユーザ定義関数(`defun`)を列挙します。パッケージ修飾された名前、`%`
  プレフィックスの内部用、または `cl` シンボルをシャドウする名前は除外されます。コンパイル出力では、これはプログラムの
  `defun` の **コンパイル時スナップショット** です。`load`/`eval` を通じて実行時に定義された関数(`--dynamic`
  を使っても)は含まれず、`(in-package :rontolisp)`
  が有効な間に定義された関数はどのパッケージにも列挙されません。
- [ユーザー定義パッケージ](#ユーザー定義パッケージdefpackage)の `list-functions` は、そのパッケージの
  `defun` を正規の修飾名で列挙します — export された関数は `mypkg:fn`、internal な関数は
  `mypkg::fn` です。ユーザーパッケージの `list-macros` と `list-special-forms` は `nil` です。
- car/cdrの合成(`cadr`、`caddr` ...)はパターンで認識され列挙されないため、`list-functions`
  には現れません。
- パッケージ指定子はリテラルでなければなりません。計算された指定子は読み込み/コンパイル時に拒否されます(インタプリタはさらに
  `funcall` を通じて計算された指定子を受け付けますが、その場合、未知のパッケージはエラーではなく
  `nil` になります — ユーザーパッケージは read/コンパイル時にのみ知られているためです)。
- `version` と同様に、これらの関数はコンパイルされたランタイムの `eval`/`load` 内ではサポートされません。

パッケージは読み込み/コンパイル時に(ソース順で)解決されるため、`in-package`
はトップレベルのディレクティブであり、`*package*`
は可変のランタイム変数ではなくカレントパッケージを反映します。コンパイル出力では、実行時に読み込まれたファイルのパッケージディレクティブは処理されません。`rontolisp`
パッケージの関数(`version`、`list-functions` ...)は第一級の値として利用できません(`mapcar`/`funcall`
に渡せません)。また `cl` を使用しないパッケージ内では、`cl`
シンボル名をローカル変数としてシャドウしてはいけません。

## rontolisp パッケージの拡張

`rontolisp` パッケージが所有するシンボルは **実装固有であり、Common Lispの一部ではありません**。`rontolisp:`
修飾子で参照する(または `(in-package rontolisp)`
の後に修飾なしで使用する)必要があります。上記のイントロスペクションヘルパー(`version`、`list-functions`、`list-macros`、`list-special-forms`)に加え、このパッケージは
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
