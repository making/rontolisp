# WASM へのコンパイル

`rontolisp` に `-o` で `.wasm` で終わる出力パスを与えると、ソースを解釈実行する代わりに WebAssembly バイナリへコンパイルします。JVM バックエンドと同様、出力の拡張子がターゲットを選択し、バイナリはサードパーティのアセンブラなしで直接出力されます:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
```

```lisp
(print (+ 1 2))
```

```
3
```

## 出力の選び方

出力の形状は、互いに独立な 2 つの選択で決まります:

- **値モデル。** デフォルトでは値は WebAssembly の **GC ヒープ**上に置かれ(整数は `i31ref` で、fixnum 範囲を超えると符号付き 64 ビット構造体に、それも超えるとリム表現の多倍長整数にボックス化。浮動小数点数は構造体にボックス化)、**言語全機能**をサポートしますが、wasm-GC 対応ランタイム(wasmtime 14+、Node 22+、現行ブラウザ)が必要です。`--no-gc` は代わりに言語の**純粋計算サブセット**をアンボックスな `i64`/`f64` スカラーとリニアメモリ文字列へローワリングします — 結果は**任意の** WebAssembly エンジンで動く素の MVP モジュールで、サイズも桁違いに小さくなります。
- **パッケージング。** デフォルトの出力は **WASI Preview 1 コアモジュール**です。`--component` はそれを**コンポーネント**としてラップします: GC パスでは非同期正準 ABI 上でフル I/O を備えた WASI 0.3 コンポーネント、`--no-gc` パスではホスト側フラグを一切必要としないコンパクトな型付きリアクターコンポーネントです。Preview 1 の GC パスでは、代わりに `--no-wasi` で WASI インポートを取り除き、ホストがインポートオブジェクトなしでインスタンス化できる純粋計算ライブラリ(「リアクター」)にできます。

2 つの軸を掛け合わせると 5 つの形状になります:

| 出力形状 | フラグ | 言語 | 動作環境 | 詳細 |
| --- | --- | --- | --- | --- |
| WASI コマンドモジュール | (なし) | 全機能 | WASI Preview 1 対応の wasm-GC エンジン(`wasmtime run -W gc`) | [wasm-GC コアモジュール](../guides/wasm-gc-module.md) |
| ライブラリ(リアクター)モジュール | `--no-wasi` | 全機能(純粋計算エクスポート) | インポート不要の任意の wasm-GC エンジン(Node 22+、現行ブラウザ) | [`--no-wasi` リアクターモード](../guides/wasm-gc-module.md#no-wasi-reactor-mode) |
| WASI 0.3 コンポーネント | `--component` | 全機能 + コンポーネント限定 I/O(`rontolisp:fetch`、TCP ソケット) | wasmtime 46+ または wasm-GC 対応の別のコンポーネントホスト | [WASI 0.3 コンポーネント](../guides/wasm-component.md) |
| 素のコアモジュール | `--no-gc` | 数値/文字列[サブセット](../guides/wasm-nogc.md#eligible-subset) | wasm-GC も SIMD もない環境を含む**任意の** WebAssembly エンジン | [非 GC 出力](../guides/wasm-nogc.md) |
| コンパクトな型付きコンポーネント | `--no-gc --component` | 数値/文字列[サブセット](../guides/wasm-nogc.md#eligible-subset) | 任意のコンポーネントホスト、**フラグゼロ** | [コンパクトなコンポーネント出力](../guides/wasm-nogc.md#compact-component-output---no-gc---component) |

大まかな指針: **値モデル**はコードの要件で選びます。言語全機能が必要なら GC ヒープ、サブセットに収まる数値/文字列カーネルなら `--no-gc`(どこでも動く移植性と数百バイトのバイナリが得られます)。**パッケージング**はホストで選びます。コンポーネントホストなら `--component`、素のエンジンや JavaScript 埋め込みならコアモジュールです。

## ホスト境界

モジュールとホストの境界を渡るものは、2 つの補完的なディレクティブで宣言します:

- [**`rontolisp:wasm-export` / `rontolisp:wasm-import`**](../guides/wasm-host-boundary.md) は境界を、rontolisp 自身の型指定子(`:int`、`:float`、`:string`、`:s-expr`、...)で手書きで綴ります。同じディレクティブは出力形状に応じて 4 つの異なるホスト契約にコンパイルされます(生のコア関数、型付きコンポーネントモデルエクスポートなど)。
- [**WIT 契約(`wit-export` / `wit-import`)**](../guides/wit-contracts.md) は `.wit` ファイルから境界を駆動します — 1 つの契約がすべてのバックエンドで検査され、実装はバックエンドごとに異なります(`--component` では型付きコンポーネントモデルエクスポート、インタプリタと JVM ではプロバイダコールバック)。[`--emit-wit`](../guides/wit-contracts.md#emitting-the-wit-world---emit-wit) と [`--scaffold-wit`](../guides/wit-contracts.md#scaffolding-an-implementation---scaffold-wit) も同ガイドで扱います。

## ブラウザでコンポーネントを実行する

`jco transpile` はコンポーネントを、ページで動くただの JavaScript に変換します。今日動くもの(`--no-gc --component` は何も要らず、wasm-GC の `--component` はロードして計算はできますがまだ印字はできない)、そして末尾の `--no-wasi` / `--no-gc` リアクターモジュールを手書きで Node とブラウザから呼び出す完全なウォークスルーは、[ブラウザガイド](../guides/wasm-browser.md)を参照してください。

## 横断的なフラグ

### 最適化(ツリーシェイキング)

デフォルトでは、コンパイルされたモジュールは、関数インデックスが固定に保たれているために、プログラムが実際に使うものとは無関係に**ランタイム全体**(プリンター、有理数、文字列、リーダー、`eval` ヘルパー、WASI インポートスロットなど)を埋め込みます。`--optimize` を追加すると、モジュールのルート(そのエクスポートと `_start`/`_initialize` エントリ)から到達不能なすべての関数を落とし、生き残りを再番号付けします。未使用の WASI インポートも除去されるため、純粋計算のリアクターモジュールは一握りの関数まで縮みます:

```bash
echo "(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)" > fact.lisp
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~4 KB module
```

この `fact` の例では、モジュールは約 330 KB から約 4 KB まで縮みます。`--optimize` はオプトインで、動作を保存します: 実際の `call` 命令から呼び出しグラフを辿るため、到達可能なもの(組み込みの `eval`/`load` がディスパッチするコードを含む)はすべて保持されます。`--component` を含む**すべての**出力形状で有効です。同じフラグは [JVM 出力](jvm.md)のデッドコード除去も行います。

落ちた関数は道連れも持っていきます: それらだけが使っていた WASI インポート、もはやどこからも名指されない型定義、そして生き残ったコードがどこからも参照しない静的な文字列データです — リテラルを 1 つ表示するだけのモジュールは、ランタイム全体の文字列表ではなく数百バイトになります。

この下限は出力の書き方に依存しません。定数のテキストはコンパイル時にレンダリングされてバイト列として埋め込まれるため、`print`、`princ` + `terpri`、`write-string`、`write-line`、`(format t "Hello, ~a!~%" "World")` のいずれもランタイムのプリンタを置き去りにし、互いに数十バイトの差に収まります(コアモジュールで 700 B 未満、コンポーネントで約 2.2 KB)。計算結果を表示すれば当然プリンタは戻ってきます。

`--component` では、コアだけでなく**ラッパーもプログラムに合わせて縮みます**。コンポーネントがどの WASI 0.3 インターフェースをインポートするかは、プログラムが実際に到達できる範囲から決まります: `(print "Hello World!")` は `wasi:cli/types`、`wasi:cli/stdout`、`wasi:cli/stderr` だけをインポートするコンポーネントになり — `wasi:filesystem` も `wasi:clocks` も `wasi:random` もありません — 一方でファイルを開き、時計を読み、乱数を引くプログラムはそれらをすべて保持します。`--emit-wit` はコンポーネントが実際に持つワールドを出力するので、生成される `.wit` も一緒に縮みます。

```bash
echo '(print "Hello World!")' > hello.lisp
rontolisp hello.lisp --component --optimize -o hello.wasm    # ~2 KB
rontolisp hello.lisp --component -o hello-full.wasm          # ~345 KB
```

`--optimize` なしのコンポーネントは常に固定の WASI 表面をすべて宣言します。これがリリースをまたいで 2 つのビルドをバイト単位で比較可能にしています。

`--optimize` は、読み込んだ**ライブラリ**にどこまで手が届くかも左右します。コンパイル済みプログラムはほとんどの関数を直接呼びますが、`funcall` にはディスパッチ表が必要で、そこに載った関数は実際にその経路で呼ばれるかどうかに関わらず到達可能扱いになります。そこで、表に載るのはプログラムが実際に値として取得しうる関数だけ — `#'name`、クォートされた `'name` の指定子、`lambda` — で、それ以外は普通のデッドコードとなり `--optimize` が除去します。`md5` を読み込んで関数を 1 つ呼ぶだけのプログラムでは、これが 1.18 MB と 598 KB の差になります。

この絞り込みは全部か無かで、1 つの条件で無効になります: プログラムが実行時に関数を名前で指定できる場合、すべての関数を残さなければなりません。該当するのは `eval`、`read`、`read-from-string`、実行時の `load`、`intern`、`find-symbol`、`make-symbol`、`symbol-function`、`fdefinition`、`fboundp`、`uiop:symbol-call` のいずれかの使用で、読み込んだライブラリの中にあるものも含みます。`--optimize` が期待ほど縮まないときは、どの演算子が原因かをコンパイラに尋ねてください:

```bash
rontolisp -Drontolisp.debug.dispatchgate=true app.lisp -o app.wasm --optimize
# => [dispatch-gate] every function stays dispatchable because of: INTERN
```

`intern` のうち 1 つの形だけは例外です: `(intern name :keyword)` はキーワードしか作らず、キーワードが関数を指すことはあり得ないため、絞り込みは有効なままです — リクエストメソッドを `:GET`/`:POST` に大文字化するハンドラがこの最適化を失うことはありません。

format の制御文字列に含まれる `~/name/` ディレクティブも該当します。実行時に関数を名前で指定するためです。ただし持ち込まれるのはコンパイラが見つけられる制御文字列だけなので、そのディレクティブを綴らないプログラムには影響しません（[`format`](../reference/macros/format.md)を参照）。

`--dynamic` でも同様に無効になります。遅延束縛は実行時に任意の名前を解決するためです。

さらに小さく仕上げたい場合は、同じ `fact.lisp` を [`--no-gc`](../guides/wasm-nogc.md) でコンパイルすると `fact` は unboxed な `i32` にローワリングされ、5 KB を占めていた GC ランタイム一式（条件クラス階層、cons セル、プリンタ）が丸ごと落ちます:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, from a ~76 byte module (no -W gc)
```

ソースは変更不要で（`wasm-export` は値モデルを問わず同じ挙動）、生成モジュールは `-W gc` の要求も落とします。

`--optimize` とは独立に（`--component` を含むすべての出力モードで）、コンパイルは常にスプライスしたライブラリをツリーシェイキングします。対象は同梱の Lisp ソースライブラリ（`linalg:`、`vec:`、JSON、URL、`equalp`/`string<`）と、[`asdf:load-system` / `ql:quickload`](../guides/asdf-systems.md) で読み込んだシステムです。プログラムがソース中でその名前に一切言及しない（クォートされたシンボルや文字列リテラルの中も含む）関数・変数・定数はモジュールに含まれません。あなた自身のコードが刈られることはなく、`load`/`require` でスプライスされたファイルも刈られません。対象になるのはシステム由来のライブラリだけです。

クラス・総称関数・メソッド・コンディション・構造体は常に残ります。`make-instance` はソースのどの行も名前を書いていないメソッドに到達しうるからです。

その帰結として、実行時に計算した文字列から名前を組み立てて `eval`/`apply` 経由で呼び出すライブラリ関数は、通常の「undefined function」エラーを通知します。その場合は `--no-prune`（または `--dynamic`）を付けてコンパイルすると、すべてのライブラリ定義が保持されます。

このフラグは省略可能なレベルを取ります。`--optimize` と `--optimize=default` は同じもの — ここまでに書いたすべて — で、素の綴りのこの意味は恒久的に変わりません。`--optimize=size` はそれに次節のトレードを加えたものです。

### サイズ最適化(`--optimize=size`)

wasm-GC のコード生成には、速度と引き換えにバイト数を費やしている箇所が 2 つあり、どちらも `--optimize` の有無に関わらず有効です:

- `(logand (+ (ash x 7) i) #xFFFFFFFF)` のような整数式ツリーは**2 回**コンパイルされます — 1 回は unboxed な `i64` の単一計算として、もう 1 回は汎用ヘルパ経由で、浮動小数点数・有理数・bignum 領域への桁あふれが辿るフォールバックとして。
- 代入が整数演算である `let` 束縛には、通常の boxed なスロットに加えて unboxed な `i64` スロットが与えられます。

`--optimize=size` はこの 2 つを断ります。プログラムの計算結果は何も変わりません — 高速経路は残り続けるフォールバックの代替として存在していただけです — が、演算は汎用ヘルパ経由になるため代償は実在し、その大きさはプログラムがどれだけ整数中心かで決まります:

| プログラム | `--optimize` | `--optimize=size` | 実行時間 |
| --- | --- | --- | --- |
| ironclad SHA-256/HMAC/PBKDF2、4096 ラウンド | 2,078,195 B | 1,562,816 B (**-24.8%**) | 1.4 s -> 5.2 s (**3.8 倍**) |
| `vec:` カーネルを使うニューラルネットの学習ループ | 271,233 B | 214,169 B (-21.0%) | 1.07 s -> 1.26 s (+18%) |
| 浮動小数点 MLP の学習ループ(`vec:` なし) | 159,747 B | 125,496 B (-21.4%) | 5.6 s -> 6.1 s (+9%) |
| `cl-postgres` の hello world (`--component`) | 8,024,998 B | 6,384,099 B (-20.4%) | — |

(wasmtime 47、3 回実行の最良値。)サイズの削減幅はほとんど変わりませんが、実行時間の代償は大きく変わります。融合されるのは整数演算だけだからです — 浮動小数点カーネルはループのインデックス計算でしか代償を払いませんが、暗号のラウンド計算はすべてで払います。

したがって、モジュールを運ぶ必要があるとき — エッジへのデプロイ、ブラウザへのダウンロード、サイズ上限のあるレジストリ — に使ってください。ただしプログラムのホットループが整数演算(ハッシュ、暗号、ビット演算)である場合は別で、そこでは同じ削減が実行時間の数倍という代償を伴います。

このレベルはすべてのバックエンドで受け付けられるので、ビルドスクリプトが対象を知る必要はありません。ただしトレードする対象があるのは wasm-GC(Preview 1 と `--component`)だけで、[JVM](jvm.md) と [`--no-gc`](../guides/wasm-nogc.md) の出力は `--optimize` が生成するものとバイト単位で同一です。

### SIMD アクセラレーション(`--simd`)

`--simd` はすべてのバックエンドに共通する唯一のアクセラレーションスイッチです: ベクトル化可能な [`vec:` および `linalg:` カーネル](../guides/simd-acceleration.md)を本物のベクトル命令へローワリングします。WASM では値モデルと直交します:

- **wasm-GC + `--simd`** はカーネルを GC 管理のレーングループ配列上のネイティブ固定幅 SIMD(`f64x2`/`f32x4`)へローワリングします — パック float 配列は通常の GC オブジェクトのままで、メモリはフラグなしの場合とまったく同じに振る舞います。`--component` および `--optimize` と組み合わせられ、通常どおり `wasmtime run -W gc` で実行します(wasmtime は SIMD プロポーザルをデフォルトで有効にしています)。
- **`--no-gc` + `--simd`** は同じカーネルをパックされたリニアメモリブロック上の `v128` へローワリングします。`--simd` なしの `--no-gc` は代わりに素のスカラーループを出力します — SIMD プロポーザルのないランタイムでも動く v128 フリーの MVP モジュールです。

全体像 — どのカーネルがベクトル化されるか、単精度リダクションの精度規則、測定された効果、`linalg` のインターセプト — は [SIMD アクセラレーションガイド](../guides/simd-acceleration.md)にあります。
