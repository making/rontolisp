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
  `in-package`/`defpackage` フォーム (スキップ)、`register-system-packages` フォーム
  (スキップ — パッケージはそれを含むシステムではなく、常に自身の `defpackage` を
  通じて見つかります)、そして純粋なリテラル/条件値を持つトップレベル `defparameter`
  (解析時環境に評価されます) を書けます。`#+`/`#-`
  フィーチャ条件は動作し (ターゲットバックエンドのフィーチャーに対して評価されます。
  [データ型](../reference/data-types.md#コメントフィーチャー条件features)を参照)、
  `#.` リード時評価フォームはその `defparameter` に対して解決されます
  (`(:file #.*string-file*)` の慣用形)。解決できないもの — ASDF バージョンガードなど —
  は警告付きでスキップされます。また `:depends-on` のエントリには
  `(:feature EXPR DEP)` を書け (フィーチャ式が成立するときだけ依存が追加されます)、
  `(:version NAME "1.2.3")` も書けます (素の依存として解決されます。バージョン制約は
  検査されません — ここでは `:version` オプションは無視されるメタデータなので、
  比較する相手がありません)。トップレベルの `(defmethod perform ...)` フックは許容され
  無視されます (それを実行する `operate` 機構はありません。他のメソッド名はエラーです)。
  スーパークラスがドキュメントコンポーネントクラス (ASDF の `doc-file`、または同じ
  ファイル内で先に宣言されたもの) であるトップレベル `defclass` は、その名前を
  コンポーネント型として宣言します。そのエントリは順序付けには参加しますがソースを
  提供しません (`:static-file` と同様)。`:doc-file` と `:html-file` は `defclass`
  なしで使えます。それ以外のトップレベルフォームはファイルを名指しするエラーです。
- `.asd` は**フィーチャーを宣言**できます: `defsystem` の前にあるトップレベルの
  `(eval-when (:load-toplevel :execute) (pushnew :my-feature *features*))`
  (裸の `pushnew`/`push` も可) は、そのファイル内でそれ以降に定義される全システムに
  対してそのフィーチャーを宣言します — 各システムに
  `:rontolisp-features (:my-feature)` を書いたのと同じ効果です。この宣言はシステム
  自身の `:if-feature` / `(:feature ...)` 句と、そのコンポーネントファイルの読み取りに
  効きます。同じ `.asd` 内の `#+`/`#-` には**効きません** (ファイル読み取り時に既に
  解決済みのため)。依存システムにも効きません (それぞれが自分で宣言します)。
  situation が `(:compile-toplevel)` だけの `eval-when` は無効です (ASDF は `.asd` を
  ロードするだけでコンパイルしません)。`eval-when` 内のそれ以外のフォームは、その
  フォームを名指しするエラーです。
- `defsystem` はメタデータオプション (無視)、`:depends-on`、`:serial`、`:pathname`
  (全コンポーネントに前置されるリテラルなディレクトリ。ソースが `src/` にあるシステムは
  コンポーネント名を裸で書けます)、`:file`/`:module`/`:static-file` エントリを持つ
  `:components` をサポートします。
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
  インタプリタは実行時に計算された名前も受け付けます。どちらも末尾のキーワード
  オプション (`:verbose nil`、`:force t`、`:silent t`) を受理して無視します。
  実在のライブラリは実行時にシステムをロードするときこれらを渡します。
- **コンパイル時にシステムはツリーシェイキングされます。** ロードしたシステムが定義していても
  プログラムから到達しない関数・変数・定数は — クォートされたシンボルや文字列リテラル全体も
  含めてソース中の名前を辿った上で — `.class`/`.wasm` に含まれません。クラス・総称関数・
  メソッド・コンディション・構造体は常に残ります。すべての定義を残すには `--no-prune`
  (または `--dynamic`) を付けてコンパイルしてください。唯一の帰結については
  [JVM へのコンパイル](../compiling/jvm.md) を参照してください。

- **ロードコンテキスト変数が束縛されます。** ファイルのロード中、`*load-pathname*`
  は `load` が呼ばれたときのパスを、`*load-truename*` はそれが解決されたパスを保持し、
  ファイルが終わると元に戻ります。ロード外ではどちらも `nil` です。
  `*compile-file-pathname*` と `*compile-file-truename*` は常に `nil` です。ここには
  `compile-file` が存在せず、差し込まれたライブラリファイルは実行時にロードされるのでは
  なくコンパイル時にインライン化されるためで、コンパイルバックエンドでは 4 つとも `nil`
  を返します。ライブラリは自分のソースの隣にあるデータファイルを見つけるのに
  `(or *compile-file-truename* *load-truename*)` という定型句を使います。登録済み
  システムのディレクトリは
  [`asdf:component-pathname`](../reference/functions/asdf-component-pathname.md)
  でより直接に得られます。

## 組み込みシムシステム

Quicklisp ライブラリの中には、rontolisp 側を知り得ない実装ごとのポータビリティレイヤに依存するものがあります。rontolisp
はそれらを**組み込み ASDF システム**として同梱します: `asdf:load-system`/`ql:quickload`
(および実ライブラリの `:depends-on`) は、その名前をダウンロードせずバンドルされたシムに解決します。

| システム | シムが提供するもの |
|--------|------------------------|
| `usocket` | `rontolisp:tcp-*` 上のソケット API ([TCP ガイド](tcp-sockets.md#the-usocket-compatible-shim)を参照) |
| `trivial-gray-streams` | ポータブルな Gray ストリームのクラス/総称関数 (バイナリ/入力クラスと `trivial-gray-stream-mixin` を含む基底クラス階層、読み書き/シーケンス総称関数、`stream-file-position` とその `(setf ...)` ライタ)。rontolisp 自身のプロトコル — ストリームを取る組み込みが CLOS インスタンスストリームに対してディスパッチする先 — へのアダプタ ([Gray ストリーム](gray-streams.md)を参照) |
| `closer-mop` | 実クラスメタオブジェクト([`find-class`](../reference/functions/find-class.md) / [`class-of`](../reference/functions/class-of.md) の返り値)に対するクラスイントロスペクションのリーダー群: `classp`、`class-slots`、`slot-definition-name`/`-initargs`/`-type`/`-readers`/`-initfunction`、`class-name`、`class-direct-superclasses`、`class-direct-slots`、`class-direct-subclasses`、`class-finalized-p`、`ensure-finalized`。レガシーのタグシンボル指定子には従来通り `(name declared-type)` ペアを返す。フラットな `closer-common-lisp` 再エクスポートパッケージ(ニックネーム `c2cl`: `cl` 全体にこれらを重ねたもので、衝突は closer-mop が優先)は常に登録済みのため、`(:use :closer-common-lisp)` するパッケージが動作する |
| `flexi-streams` | パススルーのストリーム (flexi ストリームは基底ストリームそのもの) |
| `babel` | UTF-8 コーデック: `babel:string-to-octets`/`octets-to-string` (`:start`/`:end`/`:errorp` 付き)、`babel:string-size-in-octets`、`babel-encodings:*default-character-encoding*` (`:utf-8`)、`babel:list-character-encodings`。本物の babel は 20,000 行のテーブルから 40 種類以上のコードページを生成しますが、rontolisp の文字モデルは 1 つだけ (文字は Unicode コードポイントそのもので、外部表現は UTF-8) なので、シムはそのコーデックだけを実装し、`:latin-1`/`:us-ascii` は表現できるオクテットの範囲でコードポイント同一視として扱い、**それ以外の `:encoding` はシグナルします** — 誤った符号化のバイト列を黙って返すよりも |
| `float-features` | IEEE 754 ビットプリミティブ上の `single-float-bits`/`bits-single-float` と double 版 (インタープリタ + JVM。WASM の数値モデルは 64 ビットのビットパターンを保持できない) |
| `bordeaux-threads` (ニックネーム `bt` と `bt2`) | 1 つのシムが両方の API 名前空間を提供します。ロック関連のサブセット — `make-lock`、`acquire-lock`、`release-lock`、`with-lock-held`、`*supports-threads-p*` — は [`rontolisp:make-mutex`](../reference/functions/rontolisp-make-mutex.md) などの上に、スレッド生成 — `bt2:make-thread` (`:initial-bindings` 付き)、`join-thread`、`threadp`、`thread-alive-p`、`destroy-thread` — は [`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md) の上に実装されており、インタプリタと JVM では本物の仮想スレッドが生成されます。シングルスレッドの WASM バックエンドではスレッド生成の入口は呼び出し時にエラーを送出し、`bt:*supports-threads-p*` は `nil` です。`make-lock` は再入可能なロックを返し (上流のものは再入不可)、`acquire-lock` の `:wait-p` は無視されます — 獲得は常にブロックします。`:initial-bindings` の値フォームは quote フォームか自己評価値に限られます (それ以外は新スレッドの動的環境が必要になるためエラーを送出します) |
| `uiop` | 大部分はパッケージスタブです。実体のあるメンバは [`uiop:getenv`](../reference/functions/uiop-getenv.md)、[`uiop:file-exists-p`](../reference/functions/uiop-file-exists-p.md) ([`probe-file`](../reference/functions/probe-file.md) と同じ操作)、[`uiop:merge-pathnames*`](../reference/functions/uiop-merge-pathnames-star.md)、[`uiop:add-package-local-nickname`](../reference/functions/uiop-add-package-local-nickname.md)、そして上流のセマンティクスどおりのシーケンス/文字ユーティリティ [`uiop:emptyp`](../reference/functions/uiop-emptyp.md)/[`uiop:first-char`](../reference/functions/uiop-first-char.md)/[`uiop:last-char`](../reference/functions/uiop-last-char.md)/[`uiop:split-string`](../reference/functions/uiop-split-string.md)、[`uiop:symbol-call`](../reference/functions/uiop-symbol-call.md) (全バックエンド — コンパイル済みプログラムは実行時の関数レジストリを通じて名前を解決します)、`uiop` パッケージが再エクスポートする [`uiop/image:print-condition-backtrace`](../reference/functions/uiop-print-condition-backtrace.md) (ライト版: どのバックエンドも Lisp レベルのコールスタックを持たないため、コンディション自体だけを出力します)、そしてマクロ [`uiop:if-let`](../reference/macros/uiop-if-let.md)/[`uiop:when-let`](../reference/macros/uiop-when-let.md)/[`uiop:when-let*`](../reference/macros/uiop-when-let-star.md)、[`uiop:with-deprecation`](../reference/macros/uiop-with-deprecation.md) (ライト版: 定義はそのまま確立され、非推奨警告は落とされます — それを流す警告チャネルがありません)、`uiop:with-temporary-file` です |
| `swank` | 依存しているライブラリをロードできるようにするためだけのスタブです: `swank:create-server` はエラーを送出し (「rontolisp はリモート REPL を提供できません」)、`swank:stop-server` は `nil` を返す何もしない関数です。本物の swank は SLIME のサーバ側であり、その `.asd` はデータとしての defsystem フロントエンドが読めないプログラムです -- スタブがないと `(ql:quickload "clack")` は SLIME の tarball を取得してそこで失敗します |
| `mgl-pax-bootstrap` | [mgl-pax](https://github.com/melisgl/mgl-pax) でドキュメント化されたライブラリをロードできるようにする、`mgl-pax` パッケージ (ニックネーム `pax`) のスタブです (uuid の依存である trivial-utf-8 がハード依存しています。本物のシステムの `.asd` は `:defsystem-depends-on` を使います)。`pax:define-package` は `defpackage` として働き、`pax:defsection` はセクション名を `nil` の変数として定義し、**さらにセクションの `(シンボル ロケーティブ)` エントリを export します** — mgl-pax のドキュメント化されたデフォルトであり、この種のライブラリが公開 API を export する方法です。PAX-World 登録ヘルパーは `nil` を返す何もしない関数です。ドキュメントは生成されません |
| `trivial-garbage` (ニックネーム `tg`) | GC ファイナライザを正直な no-op として提供します: `tg:finalize` は何も登録せずオブジェクトを返し、`tg:cancel-finalization` は `nil` を返す no-op です。GC フックを公開するバックエンドは存在せず — そして Common Lisp はファイナライザの実行を何も保証しないため、準拠したコンシューマは実行されなくても動作しなければなりません。`dbd-postgres` (このシムのコンシューマ) への実際的な帰結: リークした prepared statement は接続が閉じるまで残ります。`dbi:disconnect` を明示的に呼んでください |
| `clack-handler-rontolisp` | [Clack](https://github.com/fukamachi/clack) のハンドラバックエンド: `run`/`stop` をエクスポートするパッケージ `clack.handler.rontolisp` で、Clack のアプリケーションプロトコルを rontolisp の組み込み HTTP サーバに橋渡しします。手動でロードすることはありません — `(clack:clackup app :server :rontolisp)` が実行時に名前で解決します (clack がパッケージ名から導出するドット区切りの綴り `clack.handler.rontolisp` でもこのシステムが応答します)。[Clack ガイド](clack.md)を参照 |

シムは意図的に薄く作られています: ロード可能なライブラリが実際に呼ぶものだけを満たし、上流の完全な
API は提供しません。

## 実際に何がロードできるか

現在、実世界の 16 のライブラリが無改変でロードできます。**バックエンド**列は
それぞれの検証済み範囲を示します — 「4 つ全て」はインタプリタ、JVM、WASM
Preview 1、`--component` を意味します。**特筆事項**列はロードの特色と、
動作しないものをまとめています。

| ライブラリ | バックエンド | 特筆事項 |
|------------|--------------|----------|
| [alexandria](https://gitlab.common-lisp.net/alexandria/alexandria) 1.0.1 | 4 つ全て | エコシステムで最も依存されているユーティリティライブラリを実物のソースからロードします。2 つのパッケージ (`alexandria`/`alexandria-1` と `alexandria-2`) の両方が動作し、以下の依存を持つライブラリはすべてこれを取り込みます。まだ無いプリミティブに依存するメンバーは動きません: `type=` (`subtypep` の第 2 返り値)。`format-symbol`/`ensure-symbol` と**シンボル**を渡した `ensure-function` はインタプリタでのみ動作します (コンパイルバックエンドでは誤った値ではなくエラー)。`shuffle`/`random-elt`/`gaussian-random` は各バックエンド固有のエントロピーを引くため、出力はバックエンド間で比較できません |
| [split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1 | 4 つ全て | 文字列とリストに対する API 全体。第 2 戻り値 (再開インデックス) も動作します。CLOS 専用の `extended-sequence.lisp` は `:if-feature (:or :sbcl :abcl)` でゲートされ自動的に除外されます |
| [parse-number](https://github.com/sharplispers/parse-number) v1.8 | 4 つ全て | API 全体が整数・有理数・浮動小数点数・基数プレフィクス付きリテラル (`#xFF`、`#3r12`)・指数マーカーを扱い、`invalid-number` コンディションも意図した診断情報付きでシグナルされます |
| [cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4 | 4 つ全て | 公開 API 全体 — 独自の `split-sequence`、`extremum` ファミリー、`read-delimited`、`expt-mod`、`collecting`/`with-collectors`、自作マクロから使える `with-unique-names`/`with-gensyms`/`once-only` (3 段のネストバッククォート)、`rotate-byte`、`copy-array`、`compose` |
| [cl-who](https://edicl.github.io/cl-who/) v1.1.5 | 4 つ全て | (X)HTML 生成マクロ — `with-html-output(-to-string)` が属性・ネストしたタグ・ローカルな `str`/`esc`/`fmt`/`htm` 演算子を扱い、`:xml` と `:html5` はどちらも正しくレンダリングされます。**`:indent` (整形出力) は未対応**で、出力モードの切り替えは **`(setf (html-mode) :html5)`** を使います: cl-who はモードをマクロ展開時に読み取るため、`*html-mode*` の実行時 `let` 再束縛は反映されません |
| [assoc-utils](https://github.com/fukamachi/assoc-utils) | 4 つ全て | Alist ユーティリティの API 全体 — `aget` (setf 可能)、alist/plist/ハッシュの相互変換、`remove-from-alist`/`delete-from-alistf`、`with-keys`、`alist-get`、`alist=`、`alistp` |
| [cl-base64](https://github.com/darabi/cl-base64) v3.4 | 4 つ全て | Base64。文字列・`(unsigned-byte 8)` 配列・整数に対して動作し、`:columns` の折り返しと `:uri` アルファベットも扱います。不正な入力文字は `bad-base64-character` を通知しますが、その `:input`/`:position`/`:code` スロットを読めるのはインタプリタのみです (コンパイル系バックエンドは素のコンディションを通知し、同じ `handler-case` で捕捉できます) |
| [md5](https://github.com/pmai/md5) v2.0.4 | 4 つ全て | MD5 (RFC 1321) — `md5sum-sequence`/`md5sum-string` とインクリメンタル API が、4 バックエンドすべてで RFC のテストベクタと一致します |
| [cl-ppcre](https://github.com/edicl/cl-ppcre) v2.1.2 | 4 つ全て | Perl 互換正規表現を実物のソースからロードします — `scan`、`scan-to-strings`、`split`、`regex-replace(-all)`、`all-matches`、`count-matches`、`do-scans`/`do-matches` 系マクロ、`register-groups-bind`、`quote-meta-chars`、パースツリー正規表現、`(?i)` などのインラインモディファイア |
| [com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4 | 4 つ全て | README のウォークスルーを含む JSON のパースと文字列化 — ハッシュテーブル / ベクタのラウンドトリップ、`:key-fn`、Gray ストリームによる `:stream` ライタ、`jzon:writer`、CLOS インスタンスの文字列化。依存システムは上記の組み込みシムに解決されます。3 つの数値リーフコンポーネント (eisel-lemire の float リーダと Schubfach の float プリンタ) は rontolisp ネイティブの float 演算による組み込みシムに置き換わるため、float のテキストは rontolisp のバックエンド間で同一な形になり、極端な指数のパースは正確な丸めから数 ulp ずれることがあります (絶対値 22 以下の 10 進指数はちょうど 1 回の丸め)。WASM バックエンドでは通常の注意点 (巨大 float の印字形、ハッシュテーブルの巡回順、非 ASCII `\u` エスケープ) が適用されます |
| [ironclad](https://github.com/sharplispers/ironclad) v0.61 (SHA-256 / HMAC / PBKDF2 / HKDF / SCRAM のスライス) | 4 つ全て | 実物のソースからロードし、公開されている FIPS 180-2・RFC 4231・RFC 5869・RFC 7677 のテストベクタを再現します — PostgreSQL クライアントが認証に使う一連の流れ、SCRAM-SHA-256 のクライアントプルーフを端から端まで含みます。ロードされるのはこのスライスのみです (ironclad 自身の `.asd` は実行可能なプログラムのため、同梱の代替がスライスを宣言します): **暗号方式 (cipher)、公開鍵演算、AEAD モード、その他のダイジェストは利用できず**、要求すると呼び出し時に通知されます。`prng.lisp` は `rontolisp:random-bytes` 上の OS エントロピー表面だけに絞られるため、ノンスや既定のソルトはどのバックエンドでも暗号論的に強いものの、`:fortuna` とシードファイル操作はありません |
| [uax-15](https://github.com/sabracrolleton/uax-15) v0.1.3 | 4 つ全て | Unicode 正規化 (UAX #15) の 4 形式すべてを実物のソースからロードします。**`--system-path` には 3 ディレクトリが必要です** (uax-15、split-sequence、cl-ppcre を `:` で連結)。上流はロード時に同梱の 2.7 MB の Unicode テキストを解析してテーブルを構築します (インタプリタでは数分) が、rontolisp はコンパイル/ロード時に同じファイルから同じテーブルを導出し、各テーブルを最初に読まれた時点で構築します。正規化を計算する関数は上流のまま残るため、ロードはほぼ無償で、正規化しないプログラムは一切払いません。意図的な差は 1 点だけで、それは修正です: `(unicode-letter-p #\A)` は `T` を返します (実際のロードでは `NIL`。上流のループが `#+utf-32` を読むため)。`get-mapping` は全バックエンドで通知しますが、上流の時点で壊れており呼び出し元はどこにもありません |
| [quri](https://github.com/fukamachi/quri) v0.7.0 | 4 つ全て | URI ライブラリを、実物のソースから `(ql:quickload "quri")` でロードします — スキーム別構造体へのパース、各アクセサ、`render-uri`、`merge-uris`、`uri-query-params`、パーセントエンコーディング、公開サフィックス API、アドレス述語。依存する `babel` は組み込みの UTF-8 シムに解決されるため UTF-8 以外の `:encoding` はシグナルし、実効 TLD テーブルは同梱の 152 KB のリストから最初の読み取り時に構築するため `(load-etld-data OTHER-FILE)` は `OTHER-FILE` ではなくそのリストを読みます。`:lenient` なパーセントデコードは不正なエスケープを `handler-bind` のハンドラからの `go` で読み飛ばしますが、コンパイルバックエンドはこれを非局所脱出へ低位化するため 4 つ全てで同じ結果になります。`--system-path` には alexandria・split-sequence・cl-utilities・idna が必要です |
| [local-time](https://github.com/dlowe-net/local-time) v1.0.6 | 4 つすべて | 日付/時刻ライブラリを、実際のソースから `(ql:quickload "local-time")` でロードします: `encode-timestamp`/`decode-timestamp`、`now`/`today`、unix 時刻と universal time の相互変換、`parse-timestring`、同梱のフォーマット全て (ISO 8601、RFC 3339、RFC 1123、asctime、ISO 週日付) とカスタムフォーマットリストに対する `format-timestring`、比較関数群、`timestamp+`/`timestamp-`/`adjust-timestamp`/`timestamp-minimize-part`、ユリウス日の 2 つ、`print-object`。依存は組み込みの `uiop` だけです。**ホストにファイルシステムがあれば実際の TZif ゾーンファイルもロードできます** — `(local-time:define-timezone tokyo #p"/usr/share/zoneinfo/Asia/Tokyo" :load t)` — `*default-timezone*` を設定するロード時の `/etc/localtime` 読み込みも同様に動作し、ファイルを読めない場合は `+utc-zone+` にフォールバックします (`--dir` なしの WASM バックエンドがこれにあたります)。`directory` が入ったことで、**`reread-timezone-repository` も 4 バックエンドすべてで同梱の `zoneinfo/` ツリーを走査します**。`find-timezone-by-location-name` で `"Asia/Tokyo"` などが解決できます。コンパイルバックエンドではリポジトリを明示的に渡してください (`(local-time:reread-timezone-repository :timezone-repository "zoneinfo/")`)。既定値はロード時に `asdf:component-pathname` を実行時 `eval` 経由で (フォールバックは `*load-truename*`) 計算されますが、コンパイルバックエンドではどちらも答えられず既定値が `nil` になるためです |
| [trivia](https://github.com/guicho271828/trivia) (`trivia.trivial` ルート) | 4 つ全て | Optima 互換のパターンマッチングを、実ソースから `(ql:quickload "trivia")` でロードします — `match`/`match*`/`ematch` (失敗は `match-error` を通知)、定数 / 変数 / `cons` / `list` / `list*` / `vector` パターン、`guard`、`or`/`and`/`not` パターン、`defpattern`、構造体パターン (キーワード形と conc-name 形)、クラスパターン (キーワードスロット形と `(class name (slot var))` 形)、`(type spec)` パターン。システム `trivia` は `trivia.trivial` — 拡張向けに上流自身が指定するベースシステム — にマップされるため、節は `:trivial` オプティマイザで実行されます: 意味論は同一で、balland2006 の節最適化 (`iterate` + `type-i` が必要) だけがありません。依存 (alexandria、lisp-namespace、closer-mop / trivial-cltl2 シム) も一緒にロードされます。インタプリタは評価のたびにマクロを再展開するため、ホットな `match` ループはコンパイル系バックエンド向きです |
| [sxql](https://github.com/fukamachi/sxql) | 4 つ全て | SQL ジェネレータを、改変なしの実ソースから `(ql:quickload "sxql")` でロードします — `sxql:yield` は SQL 文字列とバインド値リストを多値で返し、全バックエンドでバイト同一 (同じソースの SBCL とも同一) です: `select` と `from`/`where` (`:and`/`:or`/`:in`/`:like` を含む)、`order-by` (`:desc`、`nulls`)、`limit`/`offset`、`left-join ... :on`、`set=` を使う `insert-into`、`update`、`delete-from`、カラムオプション付き `create-table` (mito の `deftable` が出力する形)、`drop-table`、`alter-table`。依存 (trivia の `trivia.trivial` ルート、alexandria、cl-package-locks — 最後のものは実質 no-op のロックライブラリ) も一緒にロードされます。マクロ中心のライブラリの常として、ホットなクエリ構築はコンパイル系バックエンド向きです (インタプリタは評価のたびにマクロを再展開します)。`yield` とステートメントビルダの解説は[O/R マッピングガイド](mito.md)にあります |
| [esrap](https://github.com/scymtym/esrap) 0.19 | 4 つ全て | パックラット / PEG パーサを、改変なしの実ソースから `(ql:quickload "esrap")` で読み込みます。インライン式または名前付きルールに対する `esrap:parse`、`:lambda` / `:destructure` / `:text` 変換を伴う `defrule`、`add-rule` / `make-instance 'esrap:rule`、大文字小文字を区別しない `(~ "lit")` 終端、`and` / `or` / `not` / `*` / `+` / `?` の連結、意味述語（`(oddp decimal)`）、`:junk-allowed`、そして正確なパースエラー報告（`esrap:esrap-parse-error`。そのテキストは SBCL 独自の Unicode 文字名を除いて SBCL とバイト単位で一致します）が動作します。パーサは純粋な計算なので **Preview 1 WASM も対象**です（ソケット不要、`-W gc` 以外のフラグも不要）。依存する alexandria と trivial-with-current-source-form も一緒に読み込まれます。`esrap:trace-rule` は存在しない `break` を、swank のインデントフックは `set` を必要としますが、どちらも呼ばない限り到達しません |
| [postmodern](https://github.com/marijnh/Postmodern) v1.33.12 (MOP ビルド) | インタプリタ、JVM、WASM コンポーネント | PostgreSQL スタック (s-sql を含む) を、無改変の上流ソースから `(ql:quickload "postmodern")` でロードします: `with-connection`/`connect` とコネクションプール、S-SQL フォームまたは文字列に対する `query`/`execute` (結果スタイルは全て)、`doquery`、`:reconnect`/`reset-prepared-statement` リスタート付きのプリペアドステートメント、トランザクションとセーブポイント、`execute-file`、`deftable`。`:postmodern-thread-safe` は ON なのでロックは実際に直列化します。**DAO 層が入っています**: ビルドは `:postmodern-use-mop` を ON にしており、`table.lisp` が静的メタオブジェクトサブセットの上で無改変のままロードされます — `:col-type`/`:keys`/`:table-name` を持つ `(defclass ... (:metaclass pomo:dao-class))`、`dao-table-definition`、`deftable` の `!dao-def`、`insert-dao`/`get-dao`/`update-dao`/`upsert-dao`/`delete-dao`/`save-dao`/`select-dao`/`query-dao`、`make-dao`。メタクラスプロトコルは定義時に実行されるため、DAO クラスはリテラルなオプションを持つトップレベルの `defclass` でなければならず (実行時データからのクラス構築はエラーを通知)、`finalize-inheritance` は初回使用時ではなくクラス定義時に即座に実行されます — 定義エラーが早く表面化するだけで、結果は変わりません。接続には cl-postgres のソケット層が必要なため **Preview 1 WASM は対象外**で、どちらの wasm 実行コマンドにも `-W exceptions=y` が、`--component` にはさらに `-S tcp=y -S inherit-network=y` が必要です。s-sql の層だけ (`(ql:quickload "s-sql")`) はソケットを開かず、4 つ全てのバックエンドで同一の SQL を生成します |
| [clack](https://github.com/fukamachi/clack) v2.1.0 ([lack](https://github.com/fukamachi/lack) 同梱) | インタプリタ、JVM、WASM コンポーネント | Web アプリケーション環境を無改変の上流ソースから `(ql:quickload "clack")` でロードし、組み込みの `clack-handler-rontolisp` バックエンドで serve します — [Clack ガイド](clack.md)を参照。lack 側もロードされます: `lack:builder`、(ironclad スライス上の) `lack-util` の `generate-random-id`、そして `clackup` のデフォルト `:use-default-middlewares t` がエンドツーエンドで通すバックトレースミドルウェア。`clackup` のデフォルト `:use-thread t` はインタプリタと JVM でアクセプタを本物のスレッド ([`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md)) で実行します。WASM コンポーネントは代わりに `wasmtime serve` の下で serve します (ソケットはホストが所有)。Preview 1 WASM は設計上着信 TCP を持たないため、`clackup` は呼び出し時にエラーを送出します |
| [tiny-routes](https://github.com/jeko2000/tiny-routes) v0.1.1 | 4 つ全て | Clack アプリケーション向けのルーティング層を無改変のソースから `(ql:quickload "tiny-routes")` でロードします — `clack:clackup` とルートを持つアプリケーションの間を埋める部品です。`define-get`/`define-post`/`define-put`/`define-delete`/`define-any`/`define-route` と `define-routes`、cl-ppcre 上の `:id` 形式のパステンプレート (`:regex t` で正規表現も)、`path-parameter`、`with-request`/`with-path-parameters`、`wrap-request-body` (Clack の `:raw-body` ストリーム)・`wrap-query-parameters`・`wrap-request-predicate`/`-mapper`・レスポンス系ラッパを繋ぐ `pipe` コンビネータ、そして `ok`/`created`/`not-found`/… のコンストラクタ一式。姉妹システム `tiny-routes-middleware-cookie` もロードでき (`parse-cookie-header`、`write-set-cookie-header`、`wrap-request-cookies`、`wrap-response-cookies`)、cl-cookie・quri・local-time・proc-parse を引き込みます。ルーティング自体は純粋な計算なので**4 つ全てのバックエンドが対象**です。ルートを serve するには `clackup` が要るため Preview 1 は外れます — [Clack ガイド](clack.md)を参照。依存は cl-ppcre だけなので、ディスクからロードする場合 `--system-path` には 2 ディレクトリが必要です。テストシステムは fiveam を要求しますが、fiveam はロードできません。サイズ制約のあるコンパイル済みモジュール向けには ppcre 不要の**オプトイン** `tiny-routes/lite` があります — 直下の節を参照 |
| [cl-dbi](https://github.com/fukamachi/cl-dbi) 0.11.1 (`dbd-postgres` のみ) | インタプリタ、JVM、WASM コンポーネント | データベース非依存インターフェイスを無改変ソースから `(ql:quickload "dbd-postgres")` でロードします: `dbi:connect` (ドライバはロード済みシステム上で解決されます — コンパイルされたプログラムは実行時にシステムをロードできないため、プログラム自身に `ql:quickload` を含める必要があります)、`dbi:do-sql`、`dbi:prepare`/`execute`/`fetch`/`fetch-all`、`dbi:with-transaction` (commit と rollback)、`dbi:connect-cached`、`dbi:disconnect`。`:mysql` と `:sqlite3` ドライバは FFI を要するため存在しません。スレッド対応バックエンドではコネクションキャッシュはスレッド別です (`bt2` シムの実ロックと [`rontolisp:current-thread`](../reference/functions/rontolisp-current-thread.md) 上の `cache/thread.lisp`)。シングルスレッドの WASM バックエンドは upstream 自身のスレッドなしキャッシュを使います。依存の `trivial-garbage` は上記の no-op ファイナライザシムに解決されるので、`dbi:disconnect` を明示的に呼んでください。ソケット制約は postmodern と同じです: Preview 1 WASM は対象外、コンポーネントは `-W exceptions=y -S tcp=y -S inherit-network=y` が必要です |
| [mito](https://github.com/fukamachi/mito) 0.2.0 | インタプリタ、JVM、WASM コンポーネント | O/R マッパーを無改変ソースから `(ql:quickload "mito")` でロードします — **システム全体** (`mito-core` + `mito-migration` + `lack-middleware-mito`) が入ります。詳細は[O/R マッピングガイド](mito.md)を参照してください。DAO 層: `connect-toplevel`/`disconnect-toplevel`、`deftable` (静的メタオブジェクトプロトコル上の `dao-table-class` メタクラス — auto-pk `:serial` と `:uuid`、`record-timestamps-mixin` の `created-at`/`updated-at`)、`table-definition`、`ensure-table-exists`、`create-dao`/`insert-dao`/`save-dao`/`delete-dao`、`find-dao`、sxql 句付きの `select-dao`、`object-id`、`retrieve-by-sql`、`execute-sql`。マイグレーション層: `migration-expressions` と `migrate-table` によるクラスと実スキーマの差分は 3 バックエンドすべてで動作し、マイグレーション**ファイル**を扱う `generate-migrations` / `migrate` はインタプリタ + JVM です (WASM バックエンドはディレクトリ作成とファイル削除の呼び出しをインポートしていないため、呼び出し時にエラーになります)。`migrate` は生成された `.sql` を esrap で読み直し、アドバイザリロックは chipz の CRC32 だけを切り出したスライスに乗ります。PostgreSQL のみ (`dbd-postgres` を明示的に quickload する必要があります)。他のメタクラス利用ライブラリと同様、`deftable` はリテラルオプションのトップレベルフォームである必要があります。既知のギャップ (いずれもガイドに記載): `:conc-name` アクセサは生成されません (`slot-value` は動作します)。sxql の SQL 関数オペレータ — `(:count ...)`、したがって `mito:count-dao` — はインタプリタ限定です。次の 2 つは SBCL でも同一に失敗する上流の不具合であり、ギャップではありません: `:col-type` のない `:references` 単独、および `:initform` を持つ NOT NULL カラムの追加。依存の uuid もロードされます (v1/v4 の生成はバックエンド自身のエントロピーを使います)。`dissect` のスタック内省は no-op インターフェイスです。ソケット制約は cl-dbi と同じです: Preview 1 WASM は対象外、コンポーネントは `-W exceptions=y -S tcp=y -S inherit-network=y` が必要です |

### サイズ向けオプトイン: `tiny-routes/lite`

`(ql:quickload "tiny-routes/lite")` は、同じ tiny-routes のソースツリーを 1
コンポーネントだけ差し替えてロードします — upstream では cl-ppcre のスキャナ
だった `path-template.lisp` を ppcre 不要のマッチャーに置き換え、`:cl-ppcre`
依存もあわせて落とします。これが存在する理由は、ルーティングが正規表現
エンジンを**生かしたまま**にするからです: ルートのテンプレートはルート
*構築*時にスキャナへとコンパイルされるため、コンパイル済みモジュールでは
どんな tree-shaking も cl-ppcre を除去できず、サイズ制限のあるターゲット
ではそれがモジュールの大半を占めます —
[ルーティング版 Worker の例](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin-tiny-routes)
の計測では、フルシステムで 1,236,811 B raw、lite で 501,689 B。ルートも
応答もリクエスト単位で同一です。

この置換はテンプレートが*何にマッチするか*を決して変えません — フル
システムと同一にマッチするか、ルート構築時に大きな音を立てて拒否するかの
どちらかです:

- **受理**: リテラル文字と `:name` トークンだけから成るテンプレート —
  トークンは `:` の後に英字または `_`、以降は英数字・`_`・`-` が続くもの
  で、テンプレート内のどこにでも置けます: `/users/:id`、`/files/v:version`、
  `/pair/:a/:b`。このサブセット内では lite マッチャーはフルシステムの意味論
  を正確に再現します。貪欲なバックトラッキングも、upstream の貪欲な
  トークン*名*走査も含めてです (`/a/:x-:y` の最初のトークン名は `x-` に
  なります)。2 つのエンジンはテストスイートがテンプレート単位でピン留め
  しています。
- **ルート構築時に拒否** (逃げ道を明示するエラー): 正規表現メタ文字 —
  `.` `\` `[` `]` `(` `)` `{` `}` `|` `^` `$` `*` `+` `?` — を含む
  テンプレートと、すべての `:regex t` テンプレート。(`:name` トークンを
  含まないテンプレートは upstream でも正規表現にならず `string=` で比較
  されるため、そこにメタ文字があっても両システムとも問題ありません。)

素の `(ql:quickload "tiny-routes")` は手つかずのまま — cl-ppcre 込みの
無改変ライブラリ — で、2 つのシステムを 1 つのプログラムにロードすることは
どちらの順でも拒否されます (後からロードした側がマッチャーを黙って再定義
してしまうため)。`tiny-routes/lite` は Quicklisp のインデックスには
ありません。この名前は tiny-routes のリリースをダウンロードし、その
`.asd` に対して解決されます。

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
スペシャル変数に対する `let`/`let*`)。完全なメタオブジェクトプロトコルや
対話的デバッガ (`break`、`*debugger-hook*`) の上に
構築されたライブラリはまだロードできません
([未対応のCL機能](missing-features.md)を参照)。それ以外の場合の実用は、
**自分自身の**複数ファイル rontolisp プロジェクトの構成です — その `.asd` は
本物の ASDF でも読めるものになります。
