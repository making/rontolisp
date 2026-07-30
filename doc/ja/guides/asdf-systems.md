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
| `babel` | UTF-8 コーデック: `babel:string-to-octets`/`octets-to-string` (`:start`/`:end`/`:errorp` 付き)、`babel:string-size-in-octets`、`babel-encodings:*default-character-encoding*` (`:utf-8`)、`babel:list-character-encodings`。本物の babel は 20,000 行のテーブルから 40 種類以上のコードページを生成しますが、rontolisp の文字モデルは 1 つだけ (文字は Unicode コードポイントそのもので、外部表現は UTF-8) なので、シムはそのコーデックだけを実装し、`:latin-1`/`:us-ascii` は表現できるオクテットの範囲でコードポイント同一視として扱い、**それ以外の `:encoding` はシグナルします** — 誤った符号化のバイト列を黙って返すよりも |
| `float-features` | IEEE 754 ビットプリミティブ上の `single-float-bits`/`bits-single-float` と double 版 (インタープリタ + JVM。WASM の数値モデルは 64 ビットのビットパターンを保持できない) |
| `bordeaux-threads` (ニックネーム `bt`) | ロック関連のサブセット — `make-lock`、`acquire-lock`、`release-lock`、`with-lock-held`、`*supports-threads-p*` — を [`rontolisp:make-mutex`](../reference/functions/rontolisp-make-mutex.md) などの上に実装しています。スレッド生成は意図的にありません: Lisp からスレッドを生成できるバックエンドはないため、あるふりをするシムはビルドに失敗する代わりにプログラムを黙って逐次化してしまいます。`make-lock` は再入可能なロックを返し (上流のものは再入不可)、`acquire-lock` の `:wait-p` は無視されます — 獲得は常にブロックします |
| `uiop` | 大部分はパッケージスタブです。実体のあるメンバは [`uiop:getenv`](../reference/functions/getenv.md) (Common Lisp に `getenv` はないため、rontolisp における唯一の綴りです)、`uiop:file-exists-p` ([`probe-file`](../reference/functions/probe-file.md) と同じ操作)、`uiop:merge-pathnames*`、[`uiop:add-package-local-nickname`](../reference/functions/add-package-local-nickname.md)、そして上流の本体そのままの 3 つの 1 行関数 `uiop:emptyp`/`uiop:first-char`/`uiop:last-char` です |

シムは意図的に薄く作られています: ロード可能なライブラリが実際に呼ぶものだけを満たし、上流の完全な
API は提供しません。

## 実際に何がロードできるか

現在、実世界の 14 のライブラリが無改変でロードできます。**バックエンド**列は
それぞれの検証済み範囲を示します — 「4 つ全て」はインタプリタ、JVM、WASM
Preview 1、`--component` を意味します。**特筆事項**列はロードの特色と、
動作しないものをまとめています。

| ライブラリ | バックエンド | 特筆事項 |
|------------|--------------|----------|
| [alexandria](https://gitlab.common-lisp.net/alexandria/alexandria) 1.0.1 | 4 つ全て | エコシステムで最も依存されているユーティリティライブラリを実物のソースからロードします。2 つのパッケージ (`alexandria`/`alexandria-1` と `alexandria-2`) の両方が動作し、以下の依存を持つライブラリはすべてこれを取り込みます。まだ無いプリミティブに依存するメンバーは動きません: `type=` (`subtypep` の第 2 返り値)。`format-symbol`/`ensure-symbol` と**シンボル**を渡した `ensure-function` はインタプリタでのみ動作します (コンパイルバックエンドでは誤った値ではなくエラー)。`shuffle`/`random-elt`/`gaussian-random` は各バックエンド固有のエントロピーを引くため、出力はバックエンド間で比較できません |
| [split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1 | 4 つ全て | 文字列とリストに対する API 全体。第 2 戻り値 (再開インデックス) も動作します。CLOS 専用の `extended-sequence.lisp` は `:if-feature (:or :sbcl :abcl)` でゲートされ自動的に除外されます |
| [parse-number](https://github.com/sharplispers/parse-number) v1.8 | 4 つ全て | API 全体が整数・有理数・浮動小数点数・基数プレフィクス付きリテラル (`#xFF`、`#3r12`)・指数マーカーを扱い、`invalid-number` コンディションも意図した診断情報付きでシグナルされます |
| [cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4 | 4 つ全て | 公開 API 全体 — 独自の `split-sequence`、`extremum` ファミリー、`read-delimited`、`expt-mod`、`collecting`/`with-collectors`、自作マクロから使える `with-unique-names`/`with-gensyms`/`once-only` (3 段のネストバッククォート)、`rotate-byte`、`copy-array`、`compose` |
| [cl-who](https://edicl.github.io/cl-who/) v1.1.5 | 4 つ全て | Edi Weitz による (X)HTML 生成マクロ — `with-html-output(-to-string)` が属性・ネストしたタグ・ローカルな `str`/`esc`/`fmt`/`htm` 演算子を扱い、`:xml` と `:html5` はどちらも正しくレンダリングされます。**`:indent` (整形出力) は未対応**で、出力モードの切り替えは **`(setf (html-mode) :html5)`** を使います: cl-who はモードをマクロ展開時に読み取るため、`*html-mode*` の実行時 `let` 再束縛は反映されません |
| [assoc-utils](https://github.com/fukamachi/assoc-utils) | 4 つ全て | 深町英太郎による alist ユーティリティの API 全体 — `aget` (setf 可能)、alist/plist/ハッシュの相互変換、`remove-from-alist`/`delete-from-alistf`、`with-keys`、`alist-get`、`alist=`、`alistp` |
| [cl-base64](https://github.com/darabi/cl-base64) v3.4 | 4 つ全て | Kevin Rosenberg による Base64。文字列・`(unsigned-byte 8)` 配列・整数に対して動作し、`:columns` の折り返しと `:uri` アルファベットも扱います。不正な入力文字は `bad-base64-character` を通知しますが、その `:input`/`:position`/`:code` スロットを読めるのはインタプリタのみです (コンパイル系バックエンドは素のコンディションを通知し、同じ `handler-case` で捕捉できます) |
| [md5](https://github.com/pmai/md5) v2.0.4 | 4 つ全て | Pierre Mai による MD5 (RFC 1321) — `md5sum-sequence`/`md5sum-string` とインクリメンタル API が、4 バックエンドすべてで RFC のテストベクタと一致します |
| [cl-ppcre](https://github.com/edicl/cl-ppcre) v2.1.2 | 4 つ全て | Dr. Edmund Weitz による Perl 互換正規表現を実物のソースからロードします — `scan`、`scan-to-strings`、`split`、`regex-replace(-all)`、`all-matches`、`count-matches`、`do-scans`/`do-matches` 系マクロ、`register-groups-bind`、`quote-meta-chars`、パースツリー正規表現、`(?i)` などのインラインモディファイア |
| [com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4 | 4 つ全て | README のウォークスルーを含む JSON のパースと文字列化 — ハッシュテーブル / ベクタのラウンドトリップ、`:key-fn`、Gray ストリームによる `:stream` ライタ、`jzon:writer`、CLOS インスタンスの文字列化。依存システムは上記の組み込みシムに解決されます。3 つの数値リーフコンポーネント (eisel-lemire の float リーダと Schubfach の float プリンタ) は rontolisp ネイティブの float 演算による組み込みシムに置き換わるため、float のテキストは rontolisp のバックエンド間で同一な形になり、極端な指数のパースは正確な丸めから数 ulp ずれることがあります (絶対値 22 以下の 10 進指数はちょうど 1 回の丸め)。WASM バックエンドでは通常の注意点 (巨大 float の印字形、ハッシュテーブルの巡回順、非 ASCII `\u` エスケープ) が適用されます |
| [ironclad](https://github.com/sharplispers/ironclad) v0.61 (SHA-256 / HMAC / PBKDF2 / HKDF / SCRAM のスライス) | 4 つ全て | 実物のソースからロードし、公開されている FIPS 180-2・RFC 4231・RFC 5869・RFC 7677 のテストベクタを再現します — PostgreSQL クライアントが認証に使う一連の流れ、SCRAM-SHA-256 のクライアントプルーフを端から端まで含みます。ロードされるのはこのスライスのみです (ironclad 自身の `.asd` は実行可能なプログラムのため、同梱の代替がスライスを宣言します): **暗号方式 (cipher)、公開鍵演算、AEAD モード、その他のダイジェストは利用できず**、要求すると呼び出し時に通知されます。`prng.lisp` は `rontolisp:random-bytes` 上の OS エントロピー表面だけに絞られるため、ノンスや既定のソルトはどのバックエンドでも暗号論的に強いものの、`:fortuna` とシードファイル操作はありません |
| [uax-15](https://github.com/sabracrolleton/uax-15) v0.1.3 | 4 つ全て | Unicode 正規化 (UAX #15) の 4 形式すべてを実物のソースからロードします。この表で唯一自身の依存を持つため、**`--system-path` には 3 ディレクトリが必要です** (uax-15、split-sequence、cl-ppcre を `:` で連結)。上流はロード時に同梱の 2.7 MB の Unicode テキストを解析してテーブルを構築します (インタプリタでは数分) が、rontolisp はコンパイル/ロード時に同じファイルから同じテーブルを導出し、各テーブルを最初に読まれた時点で構築します。正規化を計算する関数は上流のまま残るため、ロードはほぼ無償で、正規化しないプログラムは一切払いません。意図的な差は 1 点だけで、それは修正です: `(unicode-letter-p #\A)` は `T` を返します (実際のロードでは `NIL`。上流のループが `#+utf-32` を読むため)。`get-mapping` は全バックエンドで通知しますが、上流の時点で壊れており呼び出し元はどこにもありません |
| [quri](https://github.com/fukamachi/quri) v0.7.0 | 4 つ全て | 深町英太郎と André A. Gomes による URI ライブラリを、実物のソースから `(ql:quickload "quri")` でロードします — スキーム別構造体へのパース、各アクセサ、`render-uri`、`merge-uris`、`uri-query-params`、パーセントエンコーディング、公開サフィックス API、アドレス述語。依存する `babel` は組み込みの UTF-8 シムに解決されるため UTF-8 以外の `:encoding` はシグナルし、実効 TLD テーブルは同梱の 152 KB のリストから最初の読み取り時に構築するため `(load-etld-data OTHER-FILE)` は `OTHER-FILE` ではなくそのリストを読みます。入力が実際に不正な場合、**`:lenient` なパーセントデコードは 3 つのコンパイルバックエンドでクラッシュします** — quri は不正なエスケープを `handler-bind` のハンドラからの `go` で読み飛ばしますが、ラムダ境界を越える `go` はそこでは呼び出し時エラーです ([未対応機能](missing-features.md))。正しい入力はハンドラに到達しません。`--system-path` には alexandria・split-sequence・cl-utilities・idna が必要です |
| [postmodern](https://github.com/marijnh/Postmodern) v1.33.12 (非 MOP ビルド) | インタプリタ、JVM、WASM コンポーネント | Marijn Haverbeke と Sabra Crolleton による PostgreSQL スタック (s-sql を含む) を、無改変の上流ソースから `(ql:quickload "postmodern")` でロードします: `with-connection`/`connect` とコネクションプール、S-SQL フォームまたは文字列に対する `query`/`execute` (結果スタイルは全て)、`doquery`、`:reconnect`/`reset-prepared-statement` リスタート付きのプリペアドステートメント、トランザクションとセーブポイント、`execute-file`、`deftable`。`:postmodern-thread-safe` は ON なのでロックは実際に直列化します。**DAO 層はありません**: ビルドは `:postmodern-use-mop` を OFF にしているため `table.lisp` はソースを提供しません (同梱の `.asd` ではフィーチャの切り替えであって書き直しではありません)。接続には cl-postgres のソケット層が必要なため **Preview 1 WASM は対象外**で、どちらの wasm 実行コマンドにも `-W exceptions=y` が、`--component` にはさらに `-S tcp=y -S inherit-network=y` が必要です。s-sql の層だけ (`(ql:quickload "s-sql")`) はソケットを開かず、4 つ全てのバックエンドで同一の SQL を生成します |

cl-ppcre のロードはこれまでで最大の機能バッチ — ローカル
`(declare (special ...))`、ジェネリック化された CLOS スロットアクセサ、
`initialize-instance :after`、`&environment` + `get-setf-expansion`、`psetf`、
`(setf (subseq ...))`、`subst`/`search`/`copy-tree`、降順・大文字小文字非区別の
文字比較 — を牽引しました。

uax-15 のロードは 2 番目に大きなバッチ — ASDF/UIOP のパス名プリミティブの
コンパイル時畳み込み、`with-open-file` で読む同梱データファイルの成果物への
インライン化、`LOOP` マクロの節単位への書き換え、WASM GC 文字列の背後にある
UTF-8 バイトモデル — を牽引しました。

alexandria のバッチは、他のすべてのライブラリが受け継いだものです。依存を持つ
ライブラリはすべて alexandria に依存しているからです:
`defmacro`/`destructuring-bind` の `&whole`、`&rest`/`&body` の後の分配パターン
(`if-let`)、`lambda-list-keywords`、`do-external-symbols`、パッケージ指定子付きの
`intern`、ハッシュテーブルの内部情報リーダ
(`hash-table-test`/`-size`/`-rehash-size`/`-rehash-threshold`)、`mismatch`、
`arrayp`、`with-open-stream`、第一級の値としての `#'open` — さらに `mappend` の
ために、複数リストを取る第一級の値としての `#'mapcar`。

そのうち 12 ライブラリの実行可能なデモ — バックエンド別の実行コマンドと期待
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
コンディションとリスタートのシステム
(`define-condition`/`handler-case`/`handler-bind`/`restart-case`/
`invoke-restart`)、`return-from` のイディオム、そして動的 (スペシャル) 変数束縛 (`defvar` の
スペシャル変数に対する `let`/`let*`)。完全なメタオブジェクトプロトコル、
対話的デバッガ (`break`、`*debugger-hook*`)、パス名の上に
構築されたライブラリはまだロードできません
([未対応のCL機能](missing-features.md)を参照)。それ以外の場合の実用は、
**自分自身の**複数ファイル rontolisp プロジェクトの構成です — その `.asd` は
本物の ASDF でも読めるものになります。
