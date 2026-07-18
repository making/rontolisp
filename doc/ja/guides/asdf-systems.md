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
| `uiop` | パッケージスタブと [`uiop:add-package-local-nickname`](../reference/functions/add-package-local-nickname.md) |

シムは意図的に薄く作られています: ロード可能なライブラリが実際に呼ぶものだけを満たし、上流の完全な
API は提供しません。

## 実際に何がロードできるか

現在、実世界の 7 つのライブラリが無改変でロードでき、4 つ全てのバックエンド
(インタプリタ、JVM、WASM Preview 1、`--component`) で検証済みです:

- **[split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1**:
  `split-sequence`/`split-sequence-if`/`split-sequence-if-not` が文字列と
  リストに対して動作します — 関数境界を多値チャネル経由で越える第 2 戻り値
  (再開インデックス) を含めて。CLOS 専用の `extended-sequence.lisp` は
  `:if-feature (:or :sbcl :abcl)` でゲートされており自動的に除外されます。
- **[parse-number](https://github.com/sharplispers/parse-number) v1.8**:
  `parse-number`/`parse-real-number`/`parse-positive-real-number` が整数、
  有理数、浮動小数点数、基数プレフィクス付きリテラル (`#xFF`、`#3r12`)、
  指数マーカーを扱います。`(error 'invalid-number :value ... :reason ...)`
  イディオムは簡易コンディション代替を通じて意図した診断情報付きで
  シグナルされます。
- **[cl-utilities](https://common-lisp.net/project/cl-utilities/) v1.2.4**:
  公開 API 全体が動作します — 独自の `split-sequence`、`extremum`
  ファミリー (`extremum`/`extremum-fastkey`/`extrema`/`n-most-extreme`)、
  `read-delimited`、`expt-mod`、`collecting`/`with-collectors`、自作マクロ
  から使える `with-unique-names`/`with-gensyms`/`once-only` (3 段のネスト
  バッククォート)、`rotate-byte`、`copy-array`、`compose`。
- **[cl-who](https://edicl.github.io/cl-who/) v1.1.5**: Edi Weitz による
  (X)HTML 生成マクロ。`with-html-output-to-string` (および
  `with-html-output`) が S 式の HTML を、属性・ネストしたタグ・ローカルな
  `str`/`esc`/`fmt`/`htm` 演算子とともにレンダリングします。エスケープと
  数値文字参照も動作します。マクロ展開は通常の defun 群**と総称関数**
  (`convert-tag-to-string-list`) をマクロ展開時に実行します — CLOS 静的
  サブセットと setf 関数定義 (`(defun (setf html-mode) ...)`) によりロード
  できます。2 つの簡易版の制限があります: **`:indent` (整形出力) は未対応**
  で、既定のコンパクトなレンダリングになります。また出力モードの切り替えは
  **`(setf (html-mode) :html5)`** を使います — cl-who はモードをマクロ展開
  (コンパイル) 時に読み取るため、`*html-mode*` の実行時 `let` 再束縛は既に
  展開済みのマクロには反映されません (スペシャル変数束縛自体は動作します)。
  既定の `:xml` モードと `:html5` はどちらも正しくレンダリングされます。
- **[assoc-utils](https://github.com/fukamachi/assoc-utils)**: 深町英太郎に
  よる alist ユーティリティ。読み取り/変換 API が動作します — `aget`
  (デフォルト値付き)、`alist-keys`/`alist-values`、
  `alist-plist`/`plist-alist`、`remove-from-alist` とその場所版
  `delete-from-alistf`、`alist-hash`/`hash-alist`、`with-keys`、キーパス
  指定の `alist-get`、`alist=`。2 つの簡易版の制限があります:
  **`(setf (aget alist key) value)` は使えません** (`define-setf-expander`
  の 5 値プロトコルが必要ですが、ここではパース済み no-op です) —
  `cons`/`delete-from-alistf` で alist を組み立ててください。また
  **`alistp` は alist でない値に対して信頼できません** — `mapl` のラムダ
  からの早期脱出がここではラムダローカルな return になるため、本物の
  ASDF ホストなら弾く値に対して `t` を返すことがあります。
- **[cl-base64](https://github.com/darabi/cl-base64) v3.4**: Kevin Rosenberg
  による Base64 エンコーダ/デコーダ。
  `string-to-base64-string`/`base64-string-to-string`(`:columns` の折り返しと
  `:uri` アルファベット付き)、`(unsigned-byte 8)` 配列ペア
  (`usb8-array-to-base64-string` とその逆)、整数ペアがすべて動作します。
  不正な入力文字は `bad-base64-character` を通知し、その
  `:input`/`:position`/`:code` スロットはインタプリタで読み取れます
  (コンパイル系バックエンドは簡易版の `#'error` ラッパを通して素の
  コンディションを通知しますが、同じ `handler-case` で捕捉できます)。
  数値の制限が 1 つ: WASM バックエンドは `i31` 範囲(約 2^30)を超える
  整数を浮動小数点で表現するため、大きな整数の `integer-to-base64-string`
  はそこで結果が変わります。

- **[com.inuoe.jzon](https://github.com/Zulu-Inuoe/jzon) v1.1.4** (`(ql:quickload
  '#:com.inuoe.jzon)` による本物のライブラリ): README
  のウォークスルーを含む JSON のパースと文字列化 — ハッシュテーブル /
  ベクタのラウンドトリップ、`:key-fn`/`jzon:coerce-key`、可変文字列へ書き込む
  Gray ストリームクラス経由の `:stream` ライタ API、インクリメンタルな
  `jzon:writer`、`closer-mop` シムの実スロットリストによる CLOS
  インスタンスの文字列化。依存システム (`closer-mop`、`flexi-streams`、
  `float-features`、`trivial-gray-streams`、`uiop`)
  は下記の組み込みシムシステムに解決され、3 つの数値リーフコンポーネント
  (`eisel-lemire.lisp`/`ratio-to-double.lisp`/`schubfach.lisp` —
  eisel-lemire の float リーダと Schubfach の float プリンタ。u64/u128
  ビット演算が WASM の数値モデルを超えます) はロード時に rontolisp
  ネイティブの float 演算・プリンタによる組み込みシムに置き換えられます。
  トレードオフ: float のテキストは Schubfach の最短ラウンドトリップ文字列では
  なく rontolisp のバックエンド間で同一な形になり、極端な指数のパースは正確な
  丸めから数 ulp ずれることがあります (よく使う範囲の `|exp10| <= 22`
  はちょうど 1 回の丸めです)。WASM バックエンドでは通常の WASM の注意点
  (巨大 float の印字形、ハッシュテーブルの巡回順、非 ASCII `\u`
  エスケープ — `code-char` がバイト単位) が適用されます。

7 ライブラリ全ての実行可能なデモ — バックエンド別の実行コマンドと期待
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
