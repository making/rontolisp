# システム (asdf)

`asdf` パッケージは、Common Lisp のビルド機構である ASDF の**限定的な API 互換サブセット**を提供します:
複数ファイルのプロジェクトを `NAME.asd` ファイルに
[`asdf:defsystem`](../reference/functions/asdf-defsystem.md) で一度だけ記述すれば、
[`asdf:load-system`](../reference/functions/asdf-load-system.md) がファイルを依存順に —
すべてのバックエンドで — ロードします。本物の ASDF は移植されていません
(CLOS、コンディションシステム、パスネーム API に依存しており、いずれもここには存在しません)。
代わりに `.asd` ファイルはプレーンなデータとして解析され、サポートされる `defsystem`
サブセットが `load`/`require` と同じ機構を駆動します。サブセット内に収まる `.asd`
はそのまま動作します。

| オペレータ | 用途 |
|----------|---------|
| [`asdf:defsystem`](../reference/functions/asdf-defsystem.md) | システムの定義: `:depends-on`、`:serial`、`:components` |
| [`asdf:load-system`](../reference/functions/asdf-load-system.md) | システムのロード (依存が先、ファイルは順序どおり、冪等) |

## プロジェクトの全体像

```console
app/
  my-app.asd
  package.lisp
  main.lisp
  run.lisp
registry/base/
  base.asd
  base.lisp
```

```console
;; app/my-app.asd
(defsystem :my-app
  :version "0.1.0"
  :depends-on (:base)
  :serial t
  :components ((:file "package")
               (:file "main")))

;; app/package.lisp
(defpackage :my-app (:use :cl) (:export :run))

;; app/main.lisp
(in-package :my-app)
(defun run () (print (base:double 21)))

;; app/run.lisp
(asdf:load-system :my-app)
(my-app:run)
```

エントリファイルを実行またはコンパイルします。同じディレクティブが 4 つのバックエンドすべてで動作します:

```console
rontolisp app/run.lisp --system-path registry/base                 # interpret
rontolisp app/run.lisp --system-path registry/base -o Prog.class   # JVM
rontolisp app/run.lisp --system-path registry/base -o app.wasm     # WASM
```

`my-app.asd` は `run.lisp` の隣で見つかり、依存システム `:base` は `--system-path`
経由で見つかります。コンパイルパスではシステム全体 (依存が先) がコンパイル時にプログラムへ
継ぎ足されます。コンパイル時 `load` インクルードとまったく同じ仕組みなので、JVM と WASM
のコンパイラはすべての `defun` をネイティブに認識します。

## システム探索パス

`asdf:load-system` は `NAME.asd` を次の順で探します:

1. `load-system` を実行しているファイルのディレクトリ (`load` と同様)、
2. `--system-path` で指定したディレクトリ (`PATH` のようにプラットフォームのパス区切り文字で複数連結可)、
3. 環境変数 `RONTOLISP_SOURCE_REGISTRY` のディレクトリ (同じ形式)。

依存システムの `.asd` は、依存する側のシステムのディレクトリから探索が始まるため、
1 つのレジストリディレクトリに並んだ兄弟システムは互いを見つけられます。

## quickload でダウンロードする

手動ダウンロードを省くには、[`ql:quickload`](../reference/functions/ql-quickload.md)
を使います。システム (とその依存) を本物の
[Quicklisp](https://www.quicklisp.org/) ディストリビューションから取得し、上記の
仕組みでそのままロードします:

```console
$ rontolisp
> (ql:quickload "split-sequence")
(split-sequence)
> (split-sequence:split-sequence #\, "a,b,c")
("a" "b" "c")
```

ダウンロードは Quicklisp の dist メタデータ (依存解決の `systems.txt`、tarball URL の
`releases.txt`) に基づきます。各リリースは展開され `~/.rontolisp/quicklisp/` 以下に
キャッシュされる (`RONTOLISP_QUICKLISP_HOME` で変更可能) ため、2 回目以降の
`quickload` はネットワーク I/O を行いません。ダウンロードはインタプリタ実行時または
コンパイル時に (Java 側で) 行われ、コンパイル済みプログラムはソースを内包していて
実行時にはフェッチしないので、`ql:quickload` は 4 バックエンドすべてで動作します。
ロード自体は `asdf` サブセットを経由するため、同じ制約が当てはまります — ダウンロード
できたライブラリでも、そのソースが下記のサポート範囲に収まっている場合にのみロード
できます。

## サポート範囲 (と非サポート)

- `.asd` ファイルは**データ**として解析されます: `defsystem` (裸または `asdf:` 修飾)、
  `in-package`/`defpackage` フォーム (スキップ)、そして純粋なリテラル/条件値を持つ
  トップレベル `defparameter` (解析時環境に評価されます) を書けます。`#+`/`#-`
  フィーチャ条件は動作し (ターゲットバックエンドのフィーチャーに対して評価されます。
  [データ型](../reference/data-types.md#コメントフィーチャー条件features)を参照)、
  `#.` リード時評価フォームはその `defparameter` に対して解決されます
  (`(:file #.*string-file*)` の慣用形)。解決できないもの — ASDF バージョンガードなど —
  は警告付きでスキップされます。また `:depends-on` のエントリには
  `(:feature EXPR DEP)` を書け、フィーチャ式が成立するときだけ依存が追加されます。
- `defsystem` はメタデータオプション (無視)、`:depends-on`、`:serial`、
  `:file`/`:module`/`:static-file` エントリを持つ `:components` をサポートします。
  コンポーネントには `:if-feature expr` を付けられます。フィーチャー式が成立しない
  場合、そのコンポーネントのファイルは除外されますが (ライブラリが CLOS 専用
  ファイルを `(:or :sbcl ...)` の後ろにゲートする方法)、依存順序内の位置は
  維持されます。test-op 配線用のオプション `:in-order-to` と `:perform` は許容され
  無視されます (`test-op`/`operate` の機構はありません)。`:version` の値は ASDF の
  `(:read-file-form ...)` 間接参照を含む任意のリテラルフォームで構いません
  (検査されません)。それ以外 (`:defsystem-depends-on` など) は句を名指しする
  エラーです。
- 同じシステムの 2 回目のロードは no-op です。循環する `:depends-on` は検出して報告されます。
- コンパイルパスはリテラルなトップレベルの `(asdf:load-system NAME)` を要求します。
  インタプリタは実行時に計算された名前も受け付けます。
- **コンパイル時にシステムはツリーシェイキングされます。** ロードしたシステムが定義していても
  プログラムから到達しない関数・変数・定数は — クォートされたシンボルや文字列リテラル全体も
  含めてソース中の名前を辿った上で — `.class`/`.wasm` に含まれません。クラス・総称関数・
  メソッド・コンディション・構造体は常に残ります。すべての定義を残すには `--no-prune`
  (または `--dynamic`) を付けてコンパイルしてください。唯一の帰結については
  [JVM へのコンパイル](../compiling/jvm.md) を参照してください。

## 組み込みシムシステム

Quicklisp ライブラリの中には、rontolisp 側を知り得ない実装ごとのポータビリティレイヤに依存するものがあります。rontolisp
はそれらを**組み込み ASDF システム**として同梱します: `asdf:load-system`/`ql:quickload`
(および実ライブラリの `:depends-on`) は、その名前をダウンロードせずバンドルされたシムに解決します。

| システム | シムが提供するもの |
|--------|------------------------|
| `usocket` | `rontolisp:tcp-*` 上のソケット API ([TCP ガイド](tcp-sockets.md#the-usocket-compatible-shim)を参照) |
| `trivial-gray-streams` | ポータブルな Gray ストリームのクラス/総称関数。rontolisp 自身のプロトコル (`rontolisp:fundamental-character-output-stream`、`rontolisp:stream-write-char`/`-string` — CLOS インスタンスストリームに対して `write-string`/`write-char` がディスパッチする先) へのアダプタ |
| `closer-mop` | 実スロットメタデータを返す `class-slots` (クラスレジストリからの `(name declared-type)` ペア。「スロットメタオブジェクト」はこのペアで、`slot-definition-name`/`-type` がそれを読む) |
| `flexi-streams` | パススルーのストリーム (flexi ストリームは基底ストリームそのもの) |
| `float-features` | IEEE 754 ビットプリミティブ上の `single-float-bits`/`bits-single-float` と double 版 (インタープリタ + JVM。WASM の数値モデルは 64 ビットのビットパターンを保持できない) |
| `bordeaux-threads` (ニックネーム `bt`) | ロック関連のサブセット — `make-lock`、`acquire-lock`、`release-lock`、`with-lock-held`、`*supports-threads-p*` — を [`rontolisp:make-mutex`](../reference/functions/rontolisp-make-mutex.md) などの上に実装しています。スレッド生成は意図的にありません: Lisp からスレッドを生成できるバックエンドはないため、あるふりをするシムはビルドに失敗する代わりにプログラムを黙って逐次化してしまいます。`make-lock` は再入可能なロックを返し (上流のものは再入不可)、`acquire-lock` の `:wait-p` は無視されます — 獲得は常にブロックします |
| `uiop` | パッケージスタブと [`uiop:add-package-local-nickname`](../reference/functions/add-package-local-nickname.md) |

シムは意図的に薄く作られています: ロード可能なライブラリが実際に呼ぶものだけを満たし、上流の完全な
API は提供しません。

## 実際に何がロードできるか

現在、実世界の 12 のライブラリが無改変でロードできます。**バックエンド**列は
それぞれの検証済み範囲を示します — 「4 つ全て」はインタプリタ、JVM、WASM
Preview 1、`--component` を意味します。

| ライブラリ | バックエンド | 動作する範囲 | 簡易版の制限 |
|------------|--------------|--------------|--------------|
| [split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1 | 4 つ全て | `split-sequence`/`split-sequence-if`/`split-sequence-if-not` が文字列とリストに対して動作します — 関数境界を多値チャネル経由で越える第 2 戻り値 (再開インデックス) を含めて | なし — CLOS 専用の `extended-sequence.lisp` は `:if-feature (:or :sbcl :abcl)` でゲートされており自動的に除外されます |
| [parse-number](https://github.com/sharplispers/parse-number) v1.8 | 4 つ全て | `parse-number`/`parse-real-number`/`parse-positive-real-number` が整数、有理数、浮動小数点数、基数プレフィクス付きリテラル (`#xFF`、`#3r12`)、指数マーカーを扱います | なし — `(error 'invalid-number :value ... :reason ...)` イディオムは簡易コンディション代替を通じて意図した診断情報付きでシグナルされます |
| [cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4 | 4 つ全て | 公開 API 全体 — 独自の `split-sequence`、`extremum` ファミリー (`extremum`/`extremum-fastkey`/`extrema`/`n-most-extreme`)、`read-delimited`、`expt-mod`、`collecting`/`with-collectors`、自作マクロから使える `with-unique-names`/`with-gensyms`/`once-only` (3 段のネストバッククォート)、`rotate-byte`、`copy-array`、`compose` | なし |
| [cl-who](https://edicl.github.io/cl-who/) v1.1.5 | 4 つ全て | Edi Weitz による (X)HTML 生成マクロ。`with-html-output-to-string` (および `with-html-output`) が S 式の HTML を、属性・ネストしたタグ・ローカルな `str`/`esc`/`fmt`/`htm` 演算子とともにレンダリングします。エスケープと数値文字参照も動作します。マクロ展開は通常の defun 群**と総称関数** (`convert-tag-to-string-list`) をマクロ展開時に実行します — CLOS 静的サブセットと setf 関数定義 (`(defun (setf html-mode) ...)`) によりロードできます | **`:indent` (整形出力) は未対応**で、既定のコンパクトなレンダリングになります。また出力モードの切り替えは **`(setf (html-mode) :html5)`** を使います — cl-who はモードをマクロ展開 (コンパイル) 時に読み取るため、`*html-mode*` の実行時 `let` 再束縛は既に展開済みのマクロには反映されません (スペシャル変数束縛自体は動作します)。既定の `:xml` モードと `:html5` はどちらも正しくレンダリングされます |
| [assoc-utils](https://github.com/fukamachi/assoc-utils) | 4 つ全て | 深町英太郎による alist ユーティリティ — `aget` (デフォルト値付き、`setf` で設定可能な場所)、`alist-keys`/`alist-values`、`alist-plist`/`plist-alist`、`remove-from-alist` とその場所版 `delete-from-alistf`、`alist-hash`/`hash-alist`、`with-keys`、キーパス指定の `alist-get`、`alist=`、`alistp` (`mapl` のラムダからの早期 `return-from` が全バックエンドで真の非局所脱出になるため、インタプリタと同様に alist でない値へは `nil` を返します) | なし |
| [cl-base64](https://github.com/darabi/cl-base64) v3.4 | 4 つ全て | Kevin Rosenberg による Base64 エンコーダ/デコーダ。`string-to-base64-string`/`base64-string-to-string` (`:columns` の折り返しと `:uri` アルファベット付き)、`(unsigned-byte 8)` 配列ペア (`usb8-array-to-base64-string` とその逆)、整数ペアがすべて動作します。不正な入力文字は `bad-base64-character` を通知します | そのコンディションの `:input`/`:position`/`:code` スロットはインタプリタで読み取れます (コンパイル系バックエンドは簡易版の `#'error` ラッパを通して素のコンディションを通知しますが、同じ `handler-case` で捕捉できます) |
| [md5](https://github.com/pmai/md5) v2.0.4 | 4 つ全て | Pierre Mai による MD5 メッセージダイジェスト実装 (RFC 1321)。文字列と `(unsigned-byte 8)` ベクタに対する `md5sum-sequence`、`md5sum-string` (flexi-streams シムの `string-to-octets` による UTF-8)、インクリメンタルな `make-md5-state`/`update-md5-state`/`finalize-md5-state` API のすべてが RFC のテストベクタと一致します | 符号なし 32 ビットの作業状態は WASM バックエンドのボックス化 64 ビット整数パスに乗るため、ダイジェストは 4 バックエンドすべてで同一です |
| [cl-ppcre](https://github.com/edicl/cl-ppcre) v2.1.2 | 4 つ全て | Dr. Edmund Weitz による Perl 互換正規表現ライブラリを、実物の未改変ソースからロードします。`scan` (レジスタ境界付き)、`scan-to-strings`、`split`、`regex-replace`/`regex-replace-all`、`all-matches`(-as-strings)、`count-matches`、`do-scans`/`do-matches`(-as-strings) 系の反復マクロ、`register-groups-bind`、`quote-meta-chars`、パースツリー正規表現、`(?i)` などのインラインモディファイアがすべて動作します — 生成されるスキャナクロージャが依存する、ループを横断する名前付き `block`/`return-from` を、コンパイルバックエンドはレキシカルな名前付き脱出として実装しています | なし |
| [com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4 | 4 つ全て | README のウォークスルーを含む JSON のパースと文字列化 — ハッシュテーブル / ベクタのラウンドトリップ、`:key-fn`/`jzon:coerce-key`、可変文字列へ書き込む Gray ストリームクラス経由の `:stream` ライタ API、インクリメンタルな `jzon:writer`、`closer-mop` シムの実スロットリストによる CLOS インスタンスの文字列化。依存システム (`closer-mop`、`flexi-streams`、`float-features`、`trivial-gray-streams`、`uiop`) は上記の組み込みシムシステムに解決されます | 3 つの数値リーフコンポーネント (`eisel-lemire.lisp`/`ratio-to-double.lisp`/`schubfach.lisp` — eisel-lemire の float リーダと Schubfach の float プリンタ。u64/u128 ビット演算が WASM の数値モデルを超えます) はロード時に rontolisp ネイティブの float 演算・プリンタによる組み込みシムに置き換えられます。そのため float のテキストは Schubfach の最短ラウンドトリップ文字列ではなく rontolisp のバックエンド間で同一な形になり、極端な指数のパースは正確な丸めから数 ulp ずれることがあります (よく使う範囲である絶対値 22 以下の 10 進指数はちょうど 1 回の丸めです)。WASM バックエンドでは通常の WASM の注意点 (巨大 float の印字形、ハッシュテーブルの巡回順、非 ASCII `\u` エスケープ — `code-char` がバイト単位) が適用されます |
| [ironclad](https://github.com/sharplispers/ironclad) v0.61 (SHA-256 / HMAC / PBKDF2 / HKDF / SCRAM のスライス) | 4 つ全て | Nathan Froyd と Guillaume LE VAILLANT による暗号ツールキットを、実物の未改変ソースからロードします。`:sha256`/`:sha224` への `digest-sequence`、インクリメンタルな `make-digest`/`update-digest`/`produce-digest` API、`make-mac`/`update-mac`/`produce-mac` と旧 API の `make-hmac` 三点セットの双方による HMAC、`:pbkdf2` または `:hmac-kdf` (HKDF) を指定した `make-kdf` + `derive-key`、`pbkdf2-hash-password`、`octets-to-integer`/`integer-to-octets` の変換関数、`byte-array-to-hex-string`/`hex-string-to-byte-array`/`ascii-string-to-byte-array` の各ヘルパが、公開されている FIPS 180-2・RFC 4231・RFC 5869・RFC 7677 のテストベクタを再現します — PostgreSQL クライアントが認証に使う一連の流れ、RFC 7677 の SCRAM-SHA-256 クライアントプルーフを端から端まで含みます | ironclad 自身の `.asd` は実行可能なプログラム (コンポーネントクラス、defsystem を生成するマクロ) のため、rontolisp はロード可能なスライスを宣言した同梱の代替 `.asd` に差し替えます — ロードされるソースはライブラリ本物のものですが、そのスライスのみです: **暗号方式 (cipher)、公開鍵演算、AEAD モード、その他のダイジェストは利用できません**。存在しないアルゴリズムを要求すると呼び出し時に通知されます。2 つのファイルは限定的な差し替えとしてのみ存在します: `public-key.lisp` は `octets-to-integer`/`integer-to-octets` だけを (原文のまま) 提供し、`prng.lisp` は OS エントロピーの表面 (`*prng*`、`make-prng`、`random-data`、`random-bits`、`strong-random`) を `rontolisp:random-bytes` の上に提供します。そのためクライアントノンスや既定のソルトはどのバックエンドでも暗号論的に強くなりますが、`:fortuna` とシードファイル操作はありません |
| [uax-15](https://github.com/sabracrolleton/uax-15) v0.1.3 | 4 つ全て | Chris Bagley と Sabra Crolleton による Unicode 正規化 (UAX #15) を、実物の未改変ソースからロードします。4 つの形式すべて (`:nfc`/`:nfd`/`:nfkc`/`:nfkd`) の `normalize` — 正準・互換分解、結合クラスの並べ替え、ハングル字母の合成 — に加えて `get-canonical-combining-class-map`、`get-illegal-char-list`、`unicode-letter-p` が動作します。この表で唯一自身の依存を持つため、**`--system-path` には 3 ディレクトリが必要です** (uax-15、split-sequence、cl-ppcre を `:` で連結) | このライブラリはロード時に同梱の 2.7 MB の Unicode テキストを cl-ppcre で解析してテーブルを構築するため、インタプリタでは数分、WASM バックエンドでは実行あたり約 30 秒かかっていました。rontolisp はコンパイル/ロード時に**同じ同梱ファイルから同じテーブルを導出**してデータとして出力し、さらに**各テーブルを最初に読まれた時点で初めて構築**します。正規化を計算する関数はすべて上流のまま残ります。したがってシステムのロード自体はほぼ無償で、正規化を一度も行わないプログラムはテーブルの代金を一切払いません。行うプログラムは、その形式が必要とするテーブルの分だけを最初の呼び出し時に一度払います。テーブルの内容は同一ですが、1 点だけ意図的な差があり、それは修正です: `(uax-15:unicode-letter-p #\A)` は `T` を返しますが、実際のロードでは 9 つのハードコードされた CJK/ハングル/西夏文字範囲の外のすべての文字に `NIL` を返します (上流の letter ループは `#+utf-32` を読みますが、ファイル自身の `*features*` への `pushnew` はリーダに届かないため、キー計算が `nil` に潰れます)。別件として `uax-15:get-mapping` は全バックエンドで通知します — 分解マップの整数キーを `string` で変換しており、これは Common Lisp でも型エラーです。つまり上流の時点で壊れており、呼び出し元はどこにもありません |
| [s-sql](https://github.com/marijnh/Postmodern) (postmodern 20260101-git) | 4 つ全て | Marijn Haverbeke と Sabra Crolleton による Lisp 風 SQL DSL — Postmodern スタックの第 1 層で、`(ql:quickload "s-sql")` で無改変の上流ソースからロードされます（`:depends-on ("cl-postgres" "alexandria")` も同じ quickload 経路で解決されます）。`(sql (:select ...))` マクロは展開時にクエリを生成し、`sql-escape`/`sql-escape-string`（`*standard-output*` を束縛する `with-output-to-string` に基づく）、`to-sql-name`/`from-sql-name`（`readtable-case` と、見つけた keyword パッケージへの `intern` を経由）、そして約 230 個の `(eql :keyword)` 特化 `expand-sql-op` メソッド — 中置演算子、`:insert-into`、`~^` format エスケープによる `ARRAY[...]` 描画 — がすべてのバックエンドで同一の文字列を生成します | s-sql 自体はソケットを開かないため 4 つ全てのバックエンドで動作しますが、実際に PostgreSQL へ**接続**するには cl-postgres のソケット層が必要で、Preview 1 WASM にはありません（そこではソケット呼び出しはコール時エラーにコンパイルされます）。`enable-s-sql-syntax`（`#Q` リーダーマクロ）は no-op の登録のままです |

cl-ppcre のロードはこれまでで最大の機能バッチ — ローカル
`(declare (special ...))`、ジェネリック化された CLOS スロットアクセサ、
`initialize-instance :after`、`&environment` + `get-setf-expansion`、`psetf`、
`(setf (subseq ...))`、`subst`/`search`/`copy-tree`、降順・大文字小文字非区別の
文字比較 — を牽引しました。

uax-15 のロードは 2 番目に大きなバッチ — ASDF/UIOP のパス名プリミティブの
コンパイル時畳み込み、`with-open-file` で読む同梱データファイルの成果物への
インライン化、`LOOP` マクロの節単位への書き換え、WASM GC 文字列の背後にある
UTF-8 バイトモデル — を牽引しました。

そのうち 11 ライブラリの実行可能なデモ（s-sql のデモは postmodern ツリーの同梱が必要なため保留中） — バックエンド別の実行コマンドと期待
出力付き — は
[`examples/asdf/`](https://github.com/making/rontolisp/tree/develop/examples/asdf)
にあります。

現時点でロードできるライブラリの目安は、おおよそ次の範囲に収まるものです:
素の `defun`/`defmacro`/`defpackage` コード、`loop`、`values` を末尾に持つ
関数への `multiple-value-bind`、サポート済みの型指定子による
`check-type`/`etypecase`、宣言 (パース済み no-op、`deftype` を含む)、CLOS
静的サブセット (単一ディスパッチの
`defclass`/`defgeneric`/`defmethod`/`make-instance`/`slot-value`、および
`(defun (setf name) ...)` setf 関数)、そして
簡易版 `define-condition`/`make-condition`/`warn`/`restart-case`/
`return-from` のイディオム、そして動的 (スペシャル) 変数束縛 (`defvar` の
スペシャル変数に対する `let`/`let*`)。完全なメタオブジェクトプロトコル、
コンディション/リスタートシステム、パス名の上に
構築されたライブラリはまだロードできません
([未対応のCL機能](missing-features.md)を参照)。それ以外の場合の実用は、
**自分自身の**複数ファイル rontolisp プロジェクトの構成です — その `.asd` は
本物の ASDF でも読めるものになります。
