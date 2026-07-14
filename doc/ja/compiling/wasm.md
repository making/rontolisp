# WASM へのコンパイル

`rontolisp` に `-o` で `.wasm` で終わる出力パスを与えると、ソースを解釈実行する代わりに WebAssembly バイナリへコンパイルします。JVM バックエンドと同様、出力の拡張子がターゲットを選択し、バイナリはサードパーティのアセンブラなしで直接出力されます:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
```

```
3
```

WASM バックエンドは、コンパイルフラグの組み合わせでいくつかの異なる**出力形状**を生成できます。まず次のセクションで全体像をつかみ、自分のホストと用途に合う形状を選んでください。あとはその形状のセクションだけを読めば十分です — 各セクションは自己完結しています。

## 出力の選び方

出力の形状は、互いに独立な 2 つの選択で決まります:

- **値モデル。** デフォルトでは値は WebAssembly の **GC ヒープ**上に置かれ(整数は `i31ref`、浮動小数点数は構造体にボックス化)、**言語全機能**をサポートしますが、wasm-GC 対応ランタイム(wasmtime 14+、Node 22+、現行ブラウザ)が必要です。`--no-gc` は代わりに言語の**純粋計算サブセット**をアンボックスな `i64`/`f64` スカラーとリニアメモリ文字列へローワリングします — 結果は**任意の** WebAssembly エンジンで動く素の MVP モジュールで、サイズも桁違いに小さくなります。
- **パッケージング。** デフォルトの出力は **WASI Preview 1 コアモジュール**です。`--component` はそれを**コンポーネント**としてラップします: GC パスでは非同期正準 ABI 上でフル I/O を備えた WASI 0.3 コンポーネント、`--no-gc` パスではホスト側フラグを一切必要としないコンパクトな型付きリアクターコンポーネントです。Preview 1 の GC パスでは、代わりに `--no-wasi` で WASI インポートを取り除き、ホストがインポートオブジェクトなしでインスタンス化できる純粋計算ライブラリ(「リアクター」)にできます。

2 つの軸を掛け合わせると 5 つの形状になります:

| 出力形状 | フラグ | 言語 | 動作環境 | 選ぶ理由 |
| --- | --- | --- | --- | --- |
| [WASI コマンドモジュール](#the-default-output-a-wasm-gc-core-module) | (なし) | 全機能 | WASI Preview 1 対応の wasm-GC エンジン(`wasmtime run -W gc`) | プログラム全体をコマンドラインから実行する |
| [ライブラリ(リアクター)モジュール](#no-wasi-reactor-mode) | `--no-wasi` | 全機能(純粋計算エクスポート) | インポート不要の任意の wasm-GC エンジン(Node 22+、現行ブラウザ) | JavaScript から Lisp 関数を呼び出す |
| [WASI 0.3 コンポーネント](#wasi-03-component---component) | `--component` | 全機能 + コンポーネント限定 I/O(`rontolisp:fetch`、TCP ソケット) | wasmtime 46+(後述のフラグ)または wasm-GC 対応のコンポーネントホスト | 型付きコンポーネントエクスポート + 本物の I/O |
| [素のコアモジュール](#non-gc-output---no-gc) | `--no-gc` | 数値/文字列[サブセット](#eligible-subset) | wasm-GC も SIMD もない環境を含む**任意の** WebAssembly エンジン | 極小・依存ゼロの計算カーネル |
| [コンパクトな型付きコンポーネント](#compact-component-output---no-gc---component) | `--no-gc --component` | 数値/文字列[サブセット](#eligible-subset) | 任意のコンポーネントホスト、**フラグゼロ** | 極小の型付きコンポーネント |

大まかな指針: **値モデル**はコードの要件で選びます。言語全機能が必要なら GC ヒープ、サブセットに収まる数値/文字列カーネルなら `--no-gc`(どこでも動く移植性と数百バイトのバイナリが得られます)。**パッケージング**はホストで選びます。コンポーネントホストなら `--component`、素のエンジンや JavaScript 埋め込みならコアモジュールです。

さらに 2 つのフラグは形状と直交しており、末尾の[横断的なフラグ](#cross-cutting-flags)で扱います:

- [`--optimize`](#optimize-tree-shaking) はモジュールをツリーシェイキングします(GC の `--component` パスでは no-op)。
- [`--simd`](#simd-acceleration---simd) は数値ベクトルカーネルをネイティブ `v128` 命令で高速化します(どちらの値モデルでも有効)。

## Lisp 関数のエクスポート

デフォルトでは、コンパイルされたモジュールはエントリポイント(`_start`)しか公開しません。個々の Lisp 関数をホスト(`wasmtime --invoke`、JavaScript、または別のモジュール)から直接呼び出せるようにするには、`rontolisp:wasm-export` ディレクティブでマークし、パラメータと結果の WASM 境界型を宣言します:

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
```

```bash
rontolisp fact.lisp -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5
```

```
120
```

ディレクティブ自体はどの出力形状でも同じです。形状ごとに変わるのはエクスポートの**ホスト契約**です — コアモジュール形状では生のコア関数、`--component` では型付きコンポーネントモデルエクスポートになります。インタプリタと JVM バックエンドではこのディレクティブは no-op(指定されたシンボルを返すだけ)なので、同じソースがすべてのバックエンドで動作します。

型指定子とその境界表現は次のとおりです:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:long` | `i64` | `--no-gc` only; full 64-bit signed range, matching the non-GC backend's internal `i64` |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory; a component-model `string` under `--component` |
| `:s-expr` | `(ptr, len)` | s-expression text (any value except a function); GC value model only |

副作用を目的とする関数は、`:returns` を省略する(または `nil`、`'()`、`:void` を与える)ことで **void** の結果を宣言できます。ラッパーは Lisp の戻り値を破棄し、WASM の結果を持ちません。同様に、`:params` の省略・`nil`・`'()` は引数なしを意味します。

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

`:as` はエクスポート名を変更します — ホスト向け API に Lisp シンボルとして自然でない名前(例えば JavaScript 向けの camelCase)を付けたいときに便利です:

```lisp
(defun draw-board (w h) (* w h))
(rontolisp:wasm-export 'draw-board :as "drawBoard" :params '(:int :int) :returns :int)
```

すべての形状に共通する制限:

- エクスポートできるのはトップレベルの `defun` のみで、宣言したパラメータ数はそのアリティと一致しなければならず、関数値を受け取ったり返したりする関数は対象外です。
- エクスポート名はデフォルトで素の Lisp 名(`fact`)になり、`:as` で変更できます。引数の書き方はホストに依存します(`wasmtime --invoke fact module.wasm 5`、`instance.exports.fact(5)` など)。

### エクスポートモード早見表

同じディレクティブは、`--no-gc` / `--component` フラグに応じて 4 つの異なるホスト契約にコンパイルされます:

| | GC core module (default / `--no-wasi`) | GC `--component` | `--no-gc` core module | `--no-gc --component` |
| --- | --- | --- | --- | --- |
| ホスト要件 | wasm-GC エンジン(`wasmtime -W gc`、Node 22+、現行ブラウザ) | wasmtime 46+(`-W gc=y -W component-model-more-async-builtins=y`)または wasm-GC + JSPI 対応のコンポーネントホスト([jco 経由のブラウザ](#running-a-component-in-a-browser-jco)ではロードと計算はできるが、まだ印字はできない) | **任意の** WebAssembly エンジン | 任意のコンポーネントモデルホスト、**フラグ不要** — 依存ゼロで動く [jco 経由のブラウザ](#running-a-component-in-a-browser-jco)を含む |
| エクスポートの形 | 生のコア関数 | 型付きコンポーネントモデルエクスポート(WAVE `--invoke`、jco) | 生のコア関数 | 型付きコンポーネントモデルエクスポート(WAVE `--invoke`、jco) |
| スカラー | `:int`/`:float`/`:bool`/void | `:int`/`:float`/`:bool`/void | + `:long`(`i64`) | + `:long`(`s64`) |
| `:string` | 手動の `(ptr,len)` + `__ronto_alloc` | コンポーネントモデル `string`(正準 ABI) | 手動の `(ptr,len)` + `__ronto_alloc` | コンポーネントモデル `string`(正準 ABI) |
| `:s-expr` | 手動の `(ptr,len)` | コンポーネントモデル `string`(印字テキスト) | 非対応 | 非対応 |
| 関数本体で使える機能 | 言語全機能 | 言語全機能 | [非 GC サブセット](#eligible-subset) | [非 GC サブセット](#eligible-subset) |
| エクスポート内の I/O | 動作する(実 WASI インポート。`--no-wasi` ではトラップ) | 同期エクスポートではトラップ。[`:async t`](#component-model-function-exports-wasm-export) を宣言する | `print` のみ(単一の `fd_write` インポート) | `print` のみ(組み込み WASI 0.2 stdio ブリッジ) |
| プログラムのトップレベル | `_start` として実行 | `wasi:cli/run` として共存 | `defun` + ディレクティブのみ | `defun` + ディレクティブのみ |
| 呼び出しごとの文字列メモリ | ホスト管理(`__ronto_alloc` + [アリーナ API](#reclaiming-the-hosts-buffer-the-arena-api)。Lisp 側はエンジンが回収) | 正準 post-return が解放 | ホスト管理(`__ronto_alloc` + [アリーナ API](#reclaiming-memory-the-arena-api)。スカラー戻り値では自動) | 正準 post-return が解放 |
| 典型的なサイズ | 約 100 KB([`--optimize`](#optimize-tree-shaking) で約 2 KB) | 約 110 KB | 数十バイト〜数 KB | 数百バイト〜数 KB |

このページの残りは各形状の詳細です: エクスポートの呼び出し方、その中で動くもの、そして各ホストが提供すべきものです。

## デフォルト出力: wasm-GC コアモジュール

デフォルトの出力 — `-o file.wasm` 以外のフラグなし — は、wasm-GC 値モデル上の **WASI Preview 1 コアモジュール**です:

- **wasm-GC** — 整数は `i31ref` として表現されます。浮動小数点数は `float_struct { f64 }` にボックス化されます。スタック上のすべての値は `(ref eq)` として型付けされます。これが言語全機能(cons セル、シンボル、クロージャ、ハッシュテーブル、`eval` など)を支えるものであり、モジュールが wasmtime 14+(`-W gc`)、Node 22+、現行ブラウザといった wasm-GC 対応ランタイムを必要とする理由です。
- **WASI Preview 1** — モジュールは 8 つの `wasi_snapshot_preview1` 関数(標準出力の `fd_write`、`random_get`、クロック、環境変数など)をインポートし、`_start` エントリポイントを公開するため、`wasmtime run` はプログラムのトップレベルをコマンドのように実行します。

エクスポートされた関数は**生のコア関数**です: スカラー(`:int`/`:float`/`:bool`)は素の数値として境界を渡るため、`wasmtime --invoke` や `instance.exports.fact(5)` が直接使えます。メモリ経由の `:string` と `:s-expr` はモジュールのエクスポートする `memory` を通じて `(ptr, len)` ペアを渡し、ホストが引数バイト列を書き込むための `__ronto_alloc(size)` バンプアロケータも併せてエクスポートされます — このプロトコルはメモリを読み書きできるホスト(JavaScript であって `wasmtime --invoke` ではない)を必要とし、[付録](#appendix-calling-a-module-from-javascript)で端から端まで解説します。モジュールのインスタンス化には依然として 8 つの WASI インポートを満たす必要があります。`wasmtime run` は自動で提供し、ブラウザホストは純粋計算関数に対して no-op スタブを供給できます。あるいは [`--no-wasi`](#no-wasi-reactor-mode) で丸ごと取り除けます。

この値モデルに関する 2 つの動作上の注意:

- **パラメータ数の上限。** 関数(`defun` または `lambda`)は最大 **7 つのパラメータ**しか取れません(インタプリタと JVM バックエンドにこの制限はありません)。上限を超えた固定アリティの `defun` は自動的にバンドルされます: コンパイラは最初の 6 パラメータを残し、残りをリストに詰め、すべての直接呼び出しサイトを一致するように書き換えます — そのため幅広いライブラリシグネチャもそのままコンパイルされます。そのような関数の値を `#'name`/`symbol-function` で取るのはコンパイルエラーです(バンドルされた形を知っているのは直接呼び出しだけです)。また、上限を超えた `lambda` や可変長関数は依然としてエラーになります — その場合は自分で引数をリストにまとめてください。可変長関数の rest リストは 1 パラメータと数えられるため、`&rest` 関数は最大 6 つの必須パラメータを宣言でき、直接呼び出しサイトでは任意個の引数を受け取れます。
- **浮動小数点数の印字の形。** WASM ではあらゆる大きさの浮動小数点数が印字できます: 整数部は 2⁶³ まで正確で、それを超える値は近似的な指数形式(`1.0E19`)にフォールバックし、`Infinity`、`-Infinity`、`NaN` は他のバックエンドと同様にその語で印字されます。形の違いが 1 つ残っています: 10⁷ から 2⁶³ までは、インタプリタと JVM が指数表記(`1.5E12`)を使うのに対し、WASM はすべての桁を印字します(`1500000000000.0`)。`rontolisp:json-stringify` もこの形の違いを引き継ぎます。

### ホストのバッファの回収(アリーナ API)

Lisp 側が確保するもの — コンスセル、クロージャ、文字列 — はすべてエンジンが回収するため、wasm-GC モジュールの*内側*にメモリ規律は不要です。エンジンから見えない唯一のものが、**ホスト**が引数のバイト列を書き込んだバッファです: それはリニアメモリであり、エンジンが決してトレースしない不透明なバイト配列で、決して解放しないバンプアロケータ `__ronto_alloc` から配られます。したがって、呼び出しごとに新しい入力バッファを確保する常駐ホストは、リニアメモリを際限なく成長させます。

そこで、`memory` をエクスポートするモジュールは、同じヒープポインタ上の対の関数もエクスポートします:

| export | signature | meaning |
| --- | --- | --- |
| `__ronto_alloc_mark` | `() -> i32` | snapshot the current bump-heap top |
| `__ronto_alloc_reset` | `(i32 mark) -> ()` | restore the top to a saved mark |

入力を確保する**前**にスナップショットを取り、結果を読み出した**後**に復元すれば、何回呼び出しても、各入力がどれだけ長くても、常駐インスタンスは平坦なままです:

```js
const countVowels = (s) => {
  const b = enc.encode(s);
  const mark = ex.__ronto_alloc_mark();          // snapshot BEFORE allocating
  const ptr = ex.__ronto_alloc(b.length);        // a fresh buffer, any length
  new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
  const n = ex['count-vowels'](ptr, b.length);   // scalar result, read out here
  ex.__ronto_alloc_reset(mark);                  // pop the input buffer
  return n;
};
```

アリーナ一般と同じく、ルールは 2 つです:

- まだ生きているすべてのものより**前**に取ったマークにだけリセットしてください。
- `:string` を**返す**エクスポートは結果のバイト列をメモリに残します: **リセットする前にデコードしてください**。さもないと次の確保がそれを上書きします。

バックエンド固有のガードが 1 つあります: GC バックエンドでは同じヒープポインタがインターン済みシンボルのバイトプールも保持している(シンボルの同一性がそこでのオフセット*そのもの*)ため、`__ronto_alloc_reset` はそのプールの高水位より下へはポップしません。したがって新しいシンボルをインターンする呼び出し(`read`、`intern`、`gensym`)は入力バッファを保持し、それ以外の呼び出しは最後までポップします。ホスト側ですることはありません。

このブラケットは、[`count-vowels` の例](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)が `--no-gc` について Node と [Endive](https://endive.run)(Java)で示しているものと同一です — 境界のプロトコルはバックエンドで変わらず、変わるのは関数の中に何を書けるかだけです。[`--component`](#wasi-03-component---component) ではアリーナ API はなく、囲むものもありません: 正準 ABI の `post-return` が引数の文字列を解放してくれます。

### ホスト関数のインポート

`rontolisp:wasm-import` は `wasm-export` の逆方向です: **ホスト**が提供する関数を宣言し、指定した名前でトップレベルの `defun` とまったく同じように Lisp から呼び出せるようにします — `#'name`、`funcall`、`mapcar`、`eval` も含めてです。`:from` はインポートモジュール名(デフォルト `"env"`)、`:as` はその中のフィールド名(デフォルト: Lisp 名)を指定し、型指定子は上記と同じ表です:

```lisp
; main.lisp
(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
(defun add10 (n) (add n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

wasmtime では、それらをエクスポートする別のモジュールをプリロードしてインポートを満たします — ここではホストモジュール自体も Lisp で書かれており、`:as` エイリアス `add` でその関数をエクスポートしています:

```console
$ cat host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
$ rontolisp host.lisp -o host.wasm --no-wasi
$ rontolisp main.lisp -o main.wasm --no-wasi
$ wasmtime run -W gc --preload host=host.wasm --invoke add10 main.wasm 32
42
```

ブラウザ(または Node)ではインポートオブジェクトがそのままモジュール表になります — `:from` 名ごとに 1 キー、`:as` 名ごとに 1 プロパティです。これは WASM バックエンドが提供しないあらゆるものへの脱出ハッチでもあります。例えば三角関数の組み込みはないので、JavaScript のものを借りられます:

```lisp
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

```js
const imports = { math: { sin: Math.sin, cos: Math.cos } };
const { instance } = await WebAssembly.instantiate(bytes, imports);
```

[WebGL トライアングルの例](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-triangle)はこのパターンの hello world です: 10 個のインポート関数、エクスポートなしで、色付きの三角形をすべて Lisp から描画します。[WebGL キューブの例](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-cube)は 3D を加えます: 透視投影と回転の行列を毎フレーム Lisp で計算します。[WebGL ギャラクシーの例](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)は同じ発想を完全なブラウザプログラムに育てたものです: WebGL パイプライン全体が Lisp から駆動されます — GLSL シェーダは Lisp ソース内にあり、Lisp が 34 個のインポートされたホスト関数を通じてコンパイル・リンク・バッファ確保とすべてのドローコールを発行し、JavaScript はハンドルテーブル上の 1 行バインディングだけを提供します。

スカラー型以外の境界の詳細:

- `:string`/`:s-expr` の**引数**は、モジュールのエクスポートする `memory` 内への `(ptr, len)` ペアとしてホストに届きます(`:s-expr` 引数は先に読み取り可能なテキストへ印字されます)。
- `:string` の**結果**はホストがリニアメモリに書き込む必要があります — エクスポートされた `__ronto_alloc` でバッファを確保し、`(ptr, len)` ペア(JavaScript では 2 要素配列)を返します。
- `:s-expr` の**結果**は組み込みリーダーで解析されるため、ホストはリスト構造全体をテキストとして渡し返せます。

制限:

- デフォルト(wasm-GC)の Preview 1 出力専用です: `--component` と `--no-gc` はこのディレクティブをエラーで拒否します。
- インタプリタと JVM バックエンドでは、このディレクティブは呼び出すとエラーを通知するスタブを定義します。共有ソースはどこでもロードできますが、実際にインポートを呼び出すには WASM ホストが必要です。
- インポートされた関数にも他の関数と同じ 7 パラメータのアリティ上限があります。
- モジュールのインスタンス化には宣言したすべてのインポートの提供が必要です: `wasmtime run` はインポートモジュール名ごとに `--preload <module>=<file>.wasm` を必要とし、JavaScript ホストはインポートオブジェクトを渡します。

### No-WASI(リアクター)モード

`--no-wasi` を追加すると、WASI 関数を**一切**インポートしない Preview 1 モジュールが出力され、ホストはインポートオブジェクトなしでインスタンス化できます — エクスポートされた Lisp 関数だけを表面とする「リアクター」/ライブラリモジュールです:

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

リアクターは JavaScript からも同様に簡単に駆動できます: **インポートオブジェクトがない**ため、ホスト側は「インスタンス化してからエクスポートを呼び出す」だけです(`WebAssembly.instantiate(bytes).then(({ instance }) => instance.exports.fact(5))`)。コピー＆ペーストして実行できる完全な Node + ブラウザの例は、このページ末尾の[付録](#appendix-calling-a-module-from-javascript)にあります。

8 つの WASI インポートスロットは内部のトラップスタブで埋められるため、すべての関数インデックスは固定のままです(他のコード生成に変更はありません)。このモードは**純粋計算**のエクスポート専用です: あらゆる I/O(`print`/`read`/`open`/`getenv`/時刻/`random`、印字するトップレベルフォームを含む)はスタブに当たって**トラップ**します。Preview 1 専用です — `--no-wasi` は `--component` のもとでは無視されます。

モジュールはリアクター(WASI コマンドではない)なので、トップレベルの初期化子は `_start` ではなく **`_initialize`** としてエクスポートされます。ホストはインスタンス化後に一度 `_initialize` を呼んでトップレベルフォーム(エクスポートされた関数が読む `defvar`/`defparameter`/`setq` のグローバル)を実行すべきです。トップレベル状態を持たない純粋計算リアクターは省略できます。

## WASI 0.3 コンポーネント(`--component`)

`--component` を追加すると、Preview 1 コアモジュールの代わりに WASI 0.3(Preview 3)**コンポーネント**が出力されます。コンポーネントは `wasi:cli/stdout@0.3.0` を通じて印字します:

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y hello.wasm
```

```
3
```

WASI 0.3 ではすべてのバイト I/O が組み込みのコンポーネントモデル型 `stream<u8>` / `future<T>` と非同期正準 ABI を流れます。rontolisp は同じ Preview 1 コアモジュールを無変更のまま保ち — 依然として 8 つの `wasi_snapshot_preview1` 関数をインポートします — **アダプタ**コアモジュールがそれらを WASI 0.3(`wasi:cli`、`wasi:filesystem`、`wasi:clocks`、`wasi:random`)の上に `stream.new`/`stream.read`/`stream.write` と `future.read` を使って実装します。コンポーネントの `wasi:cli/run@0.3.0` エクスポート(`async func`)は**スタックフル**な非同期エクスポートとしてリフトされるため、同期的な stream/future 組み込みは協調的にブロックし、アダプタは直線的なコードのままです。非同期正準 ABI とスタックフルリフトは wasmtime 46+ でデフォルト有効です。同期的な stream/future 組み込みだけがまだフィーチャーゲートされており、それが `-W component-model-more-async-builtins=y` の理由です(wasm-GC コアのための `-W gc=y` も併せて)。

wasmtime の起動方法が出力の種類を選ぶわけでは**ありません**。`wasmtime run` は wasmtime のデフォルトサブコマンドで、コアモジュールかコンポーネントかを自動検出するため、`wasmtime run -W gc` は Preview 1 の `hello.wasm` も同様に実行します。Preview 1 コアモジュールと WASI 0.3 コンポーネントのどちらが生成されるかを決めるのは、コンパイル時の `--component` フラグだけです。(実際上の違いはコンポーネント専用ランタイムで現れます。そこではコンポーネントは動きますが Preview 1 コアモジュールは動きません。)

コンポーネント内で動くもの、そして各機能が実行時に必要とするもの:

- `print`/標準出力、標準入力(`read`、0 引数の `read-line`、`wasi:cli/stdin@0.3.0` 経由)、ファイル I/O(`open`、`close`、`write-line`、ストリーム `read-line`、`load`、`with-open-file`)はすべて動作します。ファイルアクセスには `--dir` が必要です(パスは最初にプリオープンされたディレクトリに対して解決されます):

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y --dir . fileio.wasm
# "hello"
```

- `random` は `wasi:random@0.3.0` から本物のエントロピーを引きます(Preview 1 はホストの `random_get` を使います)。そのため `(random N)` は実行ごとに異なります。`get-universal-time` / `get-internal-real-time` / `get-internal-run-time` は `wasi:clocks@0.3.0`(`system-clock`/`monotonic-clock`)を読み、`getenv` は `wasi:cli/environment@0.3.0` を読みます。
- 送信 HTTP(`rontolisp:fetch` と `rontolisp:await` / `rontolisp:then` / `rontolisp:promisep` のプロミス操作)はコンポーネントモードで動作し、真の非同期性も含みます: `fetch` はリクエストを送って(処理中の `wasi:http` レスポンスハンドルをラップした)プロミスを即座に返すため、`await` が各リクエストをブロックする前に複数のリクエストを重ねられます。プロミス操作自体はどのモードでもコンパイルできます。コンポーネント専用なのは `fetch` だけです。これは**ハイブリッド**です: ベースの I/O は WASI 0.3 のまま、fetch 自体は `wasi:http@0.2` + `wasi:io@0.2` をインポートします(非同期の `wasi:http@0.3` はまだ上流に存在しません)。fetch コンポーネントは非同期フラグに加えて `-S http=y` で実行してください。fetch を使わないコンポーネントは `wasi:http` をインポートしないため、`-S http` は不要です。
- TCP ソケット(`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port`)はコンポーネントモードで `wasi:sockets@0.3.0` の上で動作します(ネイティブに WASI 0.3 — 0.2 ハイブリッドではありません)。ソケットは双方向ストリームハンドルなので、`read-line` / `write-line` / `read-byte` / `write-byte` / `close` が直接使えます。ソケットコンポーネントは非同期フラグに加えて `-S tcp=y -S inherit-network=y` で実行してください。これらがなくてもコンポーネントは起動しますが、すべてのソケット操作が失敗して `nil` を返します。ホストは IPv4 リテラルでなければならず(ホスト名解決はまだありません)、`rontolisp:fetch` と tcp 関数はまだ 1 つのコンポーネントで組み合わせられません。
- それ以外の点では、コンパイルされた Lisp はサポートされる機能について Preview 1 出力と同一に振る舞います。受信 HTTP のサービング(`rontolisp:http-handler`)もコンポーネントにコンパイルされますが、別種のコンポーネント(`wasi:http/incoming-handler`)で、`wasmtime serve` のもとで動きます — [HTTP ハンドラーガイド](../guides/http-handler.md)を参照してください。

### コンポーネントモデル関数エクスポート(wasm-export)

`--component` のもとでは、[`rontolisp:wasm-export`](#exporting-lisp-functions) は**型付きコンポーネントモデルエクスポート**になり、正準 ABI を通じて WAVE 構文(`wasmtime run --invoke 'name(args)'`、experimental 警告なし)で呼び出せます — しかも `wasi:cli/run` のコマンドエントリと共存するため、同じコンポーネントは引き続きコマンドとしても実行できます:

```lisp
(defun sumsquared (a b) (* (+ a b) (+ a b)))
(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
(print (sumsquared 10 10))
```

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y --invoke 'sumsquared(2, 3)' sumsq.wasm
# 25    (the export's return value, rendered by wasmtime)
wasmtime run -W gc=y -W component-model-more-async-builtins=y sumsq.wasm
# 400    (the ordinary run entry executes the top-level program)
```

2 つのコマンドは異なるものを表示します: `--invoke` は名前付きエクスポート**だけ**を呼び出し、トップレベルのプログラム(`wasi:cli/run` エントリ)は実行されません — `25` は wasmtime がエクスポートの戻り値を WAVE 構文でレンダリングしたものであり、`print` の出力ではありません。素の `run` は代わりにトップレベルのプログラムを実行するため、`400` は `(print (sumsquared 10 10))` の出力です。

型付きシグネチャ(`:int` → `s32`、`:float` → `f64`、`:bool` → `bool`、`:string` → `string`、`:s-expr` → 印字された S 式テキストを運ぶ `string`、`:returns` 省略 → 結果なし)は任意のコンポーネントホストから見え、`:as` はコア側と同様にコンポーネントエクスポートの名前を変更します。

`:string` 境界は本物のコンポーネントモデル `string` として越えます — どちら側にも手動のポインタ処理はありません。ホストは引数のバイト列をリニアメモリへローワリングし、結果を正準 ABI を通じて読み出します。その後モジュールは呼び出しごとの確保を解放する(正準 *post-return* 関数がバンプアロケータをポップする)ため、常駐インスタンスは繰り返し呼び出しでもフラットに保たれます:

```lisp
;; greet.lisp
(defun greet (s) (concatenate 'string "Hello, " s))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y --invoke 'greet("世界")' greet.wasm
# "Hello, 世界"
```

デフォルトではエクスポートは**同期的に**リフトされ、純粋計算でなければなりません: その中の I/O(`print`、`read`、`rontolisp:fetch`、ファイルアクセス)は実行時に "cannot block a synchronous task" でトラップします。**`:async t`** でエクスポートを非同期と宣言すると、代わりに非同期関数型に対してリフトされ — `run` エントリと同じスタックフル非同期の形です — その中の I/O が動作します。`wasmtime --invoke` は非同期エクスポートもまったく同じ方法で呼び出します:

```lisp
;; status.lisp
(defun fetch-status (url)
  (print "fetching")
  (getf (rontolisp:await (rontolisp:fetch url)) :status))
(rontolisp:wasm-export 'fetch-status :params '(:string) :returns :int :async t)
```

```bash
rontolisp status.lisp --component -o status.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y -S http=y \
  --invoke 'fetch-status("https://httpbin.org/status/204")' status.wasm
# "fetching"
# 204
```

コンポーネントの WIT レベルの契約では、`:async t` エクスポートは `async func` です(例えば jco は Promise を返す関数として型付けし、同期エクスポートは普通の関数のままです)。同期と非同期のエクスポートは 1 つのコンポーネント内で自由に混在でき、`:async` は `:string`/`:s-expr` を含むすべての境界型と組み合わせられ、`:async` エクスポートのないプログラムはバイト単位で同一の出力を生成します。

コンポーネントエクスポートの現在の制限:

- **同期**(デフォルト)エクスポートは純粋計算専用です: その中の I/O は実行時に "cannot block a synchronous task" でトラップします。エクスポートが印字・fetch・その他の I/O を行うときは `:async t` にオプトインし、純粋計算のエクスポートは同期のままにしてください。
- `:async` が意味を持つのはここだけです: Preview 1 / `--no-wasi` のコアエクスポートは無視し(そこではホストが直接 I/O を提供します)、`--no-gc --component` は拒否します(コンパクトなリアクターコンポーネントには非同期アダプタがありません)。
- jco(1.25.2)は `:async t` エクスポートをトランスパイルして非同期として型付けしますが、まだ呼び出せません — 生成されるドライバはコールバック方式の非同期タスクを前提としており、スタックフル非同期エクスポートは上流で未実装です(トランスパイルされた `run` を呼べないのと同系統のギャップです)。非同期エクスポートの検証済みパスは `wasmtime run --invoke` です。同期エクスポートはどちらでも動作します。
- エクスポート名は lower-kebab-case のコンポーネントモデル名(`sum-squared`)でなければなりません。その文法から外れる Lisp 名については、コンパイラが `:as` での改名を求めます。
- エクスポートの呼び出しはプログラムのトップレベルを先に実行しないため、`defvar`/`defparameter` のグローバルを読むエクスポートは未初期化の値を見ることになります(これは Preview 1 の `--invoke` の動作と一致します)。

純粋計算のエクスポートキットには、コンパクトな [`--no-gc --component`](#compact-component-output---no-gc---component) が同じ型付きエクスポート(加えて `:long` → `s64`、ただし `:s-expr` なし)を、wasmtime のフラグを一切必要としない数百バイトのコンポーネントとして出力します。

### WIT ワールドの出力(`--emit-wit`)

任意の `--component` ビルドに `--emit-wit` を追加すると、コンポーネントの WIT 記述も `.wasm` 出力の隣に書き出されます — `-o sumsq.wasm --emit-wit` は `sumsq.wit` を書きます:

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm --emit-wit
```

```text
// sumsq.wit (the world; the file also carries the referenced package
// definitions, so it is self-contained and parseable on its own)
package root:component;

world root {
  import wasi:cli/types@0.3.0;
  import wasi:cli/stdout@0.3.0;
  // ... the WASI imports of the build's blob variant ...

  export wasi:cli/run@0.3.0;
  export sumsquared: func(p0: s32, p1: s32) -> s32;
}
```

このテキストは同じバイト列に対して `wasm-tools component wit sumsq.wasm` が印字するものと一致するため、まさにコンポーネントの実際の表面です — しかしもうバイナリを内省する必要はありません: `.wit` をそのままバインディングジェネレータに渡せます。例えば jco は `.wasm` に触れることなく、この `.wit` から TypeScript の型定義を生成します:

```bash
npx @bytecodealliance/jco types sumsq.wit -o types/
# types/sumsq.d.ts: export function sumsquared(p0: number, p1: number): number;
```

world のインポートはビルドのバリアントに従います(プレーン、`rontolisp:fetch`、`rontolisp:tcp-*`、`rontolisp:http-handler`。[`--no-gc --component`](#compact-component-output---no-gc---component) では world はインポートなしになり、プログラムが印字するときは 0.2 stdio のインポートを持ちます)。`:async t` エクスポートは `async func` として描画され、`rontolisp:http-handler` ビルドは `run` の代わりに `wasi:http/incoming-handler` をエクスポートします。`--component` なしの `--emit-wit` はコンパイルエラーです — コアモジュールには記述すべき WIT レベルの表面がありません。

### `--emit-wit` は何のためにあるか

エクスポート一覧がどこから来たかによって、答える問いが変わります。

**world を持たないプログラム** — `rontolisp:wasm-export` で手書きしたエクスポート、あるいは WIT の綴り自体が存在しない `:s-expr` エクスポート — には `.wit` がどこにもありません。上のとおり、`--emit-wit` がそれを得る唯一の手段です。

**world を持つプログラム**([`wit-export`](#implementing-a-wit-world-wit-export))は、エクスポートについてはすでに書き下しています。書き下していないのはコンポーネントの**インポート**であり、そしてそちらの方が大きな半分です: `wit-export` が読むのは world の `export` 項目だけです。コンポーネントの WASI 表面は world からではなく、ビルドがリンクする固定のアダプタ blob から来るからです。[次節](#implementing-a-wit-world-wit-export)の 6 行の `wit/greeter.wit` は、実際の型が **149 行**あるコンポーネントにコンパイルされます — 宣言したただ 1 つの `greet` のまわりに、10 個の `wasi:*` インポートと `export wasi:cli/run@0.3.0` が付きます。その `greet` から `rontolisp:fetch` を呼べば、ビルドはさらに 5 つのインポート(`wasi:io/poll`、`wasi:io/error`、`wasi:io/streams`、`wasi:http/types`、`wasi:http/outgoing-handler`)を黙って追加し、**325 行**になります。`rontolisp:tcp-*` も同様に `wasi:sockets` を引き込みます。`wasm-tools` を入れてバイナリを内省するのでない限り、自分が実際に何をビルドしたのかを見る手段は `--emit-wit` だけです — そしてそれこそが、ホストや `jco` がそれらのインポートを*供給する*ために必要とするものです。

一方、world を持つプログラムにとって `--emit-wit` が**そうではない**もの、それはそのプログラムの乖離チェックです。エクスポート行は構成上の不動点です: world が `rontolisp:wasm-export` ディレクティブを生み、それがコンポーネントの関数型を生み、それがそのまま印字されて戻ってくる — 双方向に 1 対 1 で対応する境界型の集合(`s32`、`s64`、`f64`、`bool`、`string`)の上での話です。渡した world と食い違って出てくることはありえません。したがって `.wit` を再出力して CI で差分を取るのは、*rontolisp 側の*型マッピングに対するリグレッションテストであって(安価であり、続ける価値もあります)、あなたのソースに対するチェックではありません。乖離したプログラムを捕まえるのは `wit-export` 自身であり、それはすでにすべてのバックエンド(素のインタプリタ実行を含む)で走っています。これは過渡的な状態です: world がプログラムの束縛するインポートも宣言できるようになれば、出力される WIT は真に双方向の契約になります。

### WIT world の実装(wit-export)

ここまではすべて Lisp から出発して `.wit` を*出力*するものでした。**`rontolisp:wit-export`** はこれを逆転させます: 誰かが書いた world をコンパイラに渡し、プログラムがそれを**実装**します。

```console
// wit/greeter.wit
package example:greeter;

world greeter {
  /// Greet someone by name.
  export greet: func(who: string) -> string;
}
```

```console
;;; greet.lisp -- the directive comes last: on the interpreter it sees only the
;;; functions defined so far.
(defun greet (who)
  (concatenate 'string "Hello, " who "!"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y --invoke 'greet("world")' greet.wasm
# "Hello, world!"
```

どこにも `:params '(:string) :returns :string` はありません — 型は world から来ます。これこそが要点です: 手書きの境界型は、別途生成される `.wit` の隣に置かれ、両者は乖離していき、最終的に `wasmtime --invoke` が実行時に失敗して初めて気づきます。`wit-export` では **WIT が唯一の真実の源**です:

- world がプログラムのエクスポート一覧であるため、同じプログラム内の手書きの `rontolisp:wasm-export` はコンパイルエラーです。
- すべてのエクスポートには正しいアリティの `defun` が必要で、すべての WIT 型は境界が運べるもの(`s32`、`s64`、`f64`、`bool`、`string`)でなければならず、world 中の `async func` はそのエクスポートを `:async t` としてリフトします(I/O を行うエクスポートは推測されるのではなく WIT によって非同期と宣言されます)。各不一致は WIT ファイル名と行番号を示すコンパイルエラーになります:
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`。
- 契約は**すべての**バックエンドで検査されます: 素の `rontolisp greet.lisp` 実行(や `-o Greet.class` ビルド)は world を検証するだけでエクスポートは行いません。したがって乖離は WASM ビルドよりずっと手前で捕まります。

このディレクティブは前節までの機構のフロントエンドであって、第 2 のエクスポート経路ではありません: 手書きの実装が持つのとまったく同じ `rontolisp:wasm-export` ディレクティブへローワリングされるため、**生成されるコンポーネントはそれとバイト単位で同一**です — GC パスでも [`--no-gc --component`](#compact-component-output---no-gc---component) でも同様です(world が `s64` を使う場合は後者を選びます。wasm-GC の `i31ref` 整数は `s64` を保持できません)。

ビルドに [`--emit-wit`](#emitting-the-wit-world---emit-wit) を追加するとコンポーネントの実際の型が書き出され、そのエクスポート行は書いたとおりに戻ってきます。引数名も含めてです — WIT の名前はコンポーネントの関数型まで運ばれます(手書きのエクスポートは、自分で `:param-names '(who)` と宣言しない限り引数を `p0`、`p1`、... と名づけます)。

```bash
rontolisp greet.lisp --component -o greet.wasm --emit-wit   # writes greet.wit
```

```text
export greet: func(who: string) -> string;
```

ただしこの行は不動点であって、判定ではありません: world *から*導出されたものである以上、world と矛盾しえないのです。それでも出力する理由はファイルの残りにあります — world が何も語らない `wasi:*` インポートと `wasi:cli/run` エクスポート、すなわちホストが供給しなければならないものです。`greet.wit` は、あの 1 つのエクスポートのまわりに 149 行あります。入力との意図的な違いが 2 つあります: `///` ドキュメントコメントは失われます。コンポーネントの型がそれを保持しないためです(`wasm-tools` も復元できません)。そして出力される world は常に `package root:component; world root` です。それがコンポーネントの型*そのもの*だからです。

現在の制限:

- 束縛されるのは world の**エクスポート**側だけです。`import` 項目は無視され(コンポーネントの WASI インポートは、それが構築される固定のアダプタ表面から来ます — それを見る手段が [`--emit-wit`](#emitting-the-wit-world---emit-wit) です)、インラインの `import name: func(...)` は黙って捨てるのではなく拒否されます。プログラムが呼び出す関数は、[`wit-import`](#importing-a-wit-interface-wit-import) でインターフェースから束縛します(あるいは `rontolisp:wasm-import` で手書きします)。
- 実装できるのは素の関数エクスポートだけです。インターフェースをエクスポートする world はエラーであり、`rontolisp:http-handler` のプログラムは world をまったく使えません(serve モードのコンポーネントの唯一のエクスポートは `wasi:http/incoming-handler` です)。
- `:s-expr` に対応する WIT の綴りはないため、任意の S 式を境界で受け渡すエクスポートには引き続き手書きの `rontolisp:wasm-export` が必要です。
- インタプリタではディレクティブは順に評価され、それまでに定義された関数しか見えません。ファイルの末尾に置いてください。

### 実装のスケルトン生成(`--scaffold-wit`)

`--scaffold-wit` は「`.wit` を渡された、さてどうする」への答えです: コンパイルする代わりに、実装のスケルトンを生成します。

```bash
rontolisp --scaffold-wit wit/greeter.wit -o greet.lisp   # no -o: print to stdout
```

```console
;;;; Implementation of the WIT world 'greeter' (wit/greeter.wit).
;;;;
;;;; The world is the contract: the compiler checks every defun below against
;;;; it, so a renamed export, a changed arity or a changed type is a compile
;;;; error rather than a runtime surprise. Fill in the bodies; each one signals
;;;; until you do.

;;; Greet someone by name.
;;; WIT: greet: func(who: string) -> string
(defun greet (who)
  (error "greet is not implemented yet"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

引数は WIT の名前のまま命名され、各エクスポートの WIT シグネチャは満たすべき契約としてスタブの上に書き出され、`///` ドキュメントコメントは `;;;` コメントになります。スタブはコンパイル時ではなく**実行**時にシグナルするため、生成されたファイルはそのままコンパイルでき、エクスポートを 1 つずつ埋めていけます。`.wit` が複数の world を宣言している場合は `--world NAME` を追加してください。

## WIT インターフェースのインポート(`wit-import`)

`wit-export` が WIT 契約のエクスポート側だとすれば、**`rontolisp:wit-import`** はインポート側です。プログラムが WIT インターフェースを**呼び出す**ことを宣言し、そのインターフェースが宣言するすべての関数を通常の Lisp 関数として束縛します — 名前もラムダリストも型も、すべて `.wit` から取られます。これは既存のフォームへローワリングされるコンパイル時ディレクティブであり、*何に*ローワリングされるかはバックエンドごとに異なります。それこそが要点です: **1 つの WIT、バックエンドごとに異なる実装、ソース変更はゼロ**。

```console
// wit/host.wit
package example:host@0.1.0;

interface math {
  /// Add two integers on the host.
  add-ints: func(a: s32, b: s32) -> s32;
}
```

```console
;;; main.lisp -- the directive comes FIRST: it defines the functions the rest of
;;; the file calls.
(rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")

(defun add10 (n) (add-ints n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

Preview 1 WASM では、各 WIT 関数が [`rontolisp:wasm-import`](#importing-host-functions) になります。インポート**モジュール**はインターフェースの素の名前 (`math`。`:from` で変更可)、インポート**フィールド**は WIT ラベルの camelCase 表記 (`addInts` — JavaScript の慣習であり、`jco` が生成するものでもあります。`:field-style :kebab` でラベルのままにできます) です。したがってホスト側の満たし方は従来どおりです。ここではそのフィールド名で関数をエクスポートする、もう 1 つの Lisp モジュールが担います:

```console
;;; host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "addInts" :params '(:int :int) :returns :int)
```

```bash
rontolisp host.lisp -o host.wasm --no-wasi
rontolisp main.lisp -o main.wasm --no-wasi
wasmtime run -W gc --preload math=host.wasm --invoke add10 main.wasm 32
# 42
```

生成されるモジュールは、手書きの
`(rontolisp:wasm-import 'add-ints :from "math" :as "addInts" :params '(:int :int) :returns :int)`
が生成するものと**バイト単位で同一**です — このディレクティブは第 2 のインポート経路ではなく、その機構への型付きフロントエンドです。また [`--optimize`](#optimize-tree-shaking) はプログラムが呼び出さないインポートを従来どおり削ぎ落とすため、34 関数のインターフェースを束縛して 3 つだけ使ってもコストはかかりません。

### プロバイダ: インタプリタと JVM でも同じソース

インタプリタと JVM には WASM ホストが存在しないため、そこでは各 WIT 関数がインターフェースの**プロバイダ**へディスパッチする通常の `defun` になります。プロバイダとは、束縛された関数の Lisp メンバー名 (文字列) に続けてその関数の引数を受け取る Lisp 呼び出し可能オブジェクトです。[`rontolisp:wit-provide`](../reference/functions/rontolisp-wit-provide.md) がそれを束縛します — そして rontolisp は**どのインターフェースについてもプロバイダを同梱していません**。同梱しているのはプロバイダの仕組みであって、`wasi:keyvalue` が何であるかを rontolisp は知りません。WIT インターフェースの実装は通常の Lisp コードです:

```console
;;; counter.lisp -- wasi:keyvalue, against a store written in Lisp.
(rontolisp:wit-import "wit/store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)
  (cond ((string= member "open") 1)              ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)

(defvar *bucket* (kv:open "counts"))

(kv:bucket-set *bucket* "visits" "41")
(print (kv:bucket-get *bucket* "visits"))   ; "41"
```

`:package kv` は束縛をエクスポートする `defpackage` を合成します。WIT の `resource` のメソッドはハンドルを第 1 引数として取り (`bucket.get` は `(kv:bucket-get b "visits")` になります)、各束縛は通常の関数なので `#'kv:bucket-get`、`funcall`、`mapcar` がそのまま使えます。プロバイダが束縛されていない状態で呼ぶと、何らかの既定値に到達するのではなく `rontolisp:wit-error` がシグナルされます (`No provider is bound for the WIT interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide`)。

要点は、プロバイダが*ただの関数*だということです: 上のハッシュテーブルを本物のストア — Redis、ファイル、JDBC 接続 — に差し替えても、`(kv:bucket-set b "visits" "41")` を呼ぶコードは変わりません。[`wit/keyvalue` の例](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)は、1 つのページビューカウンタを 3 つのストア (可搬な Lisp ストア、JVM では `java.util.LinkedHashMap` のストア、そしてコンポーネントとしては wasmtime 自身の `wasi:keyvalue` 実装) の上で動かし、出力は同一です。同じソースを WASM にコンパイルすれば、代わりに**ホスト**がインターフェースを実装します。その場合トップレベルの `rontolisp:wit-provide` はエラーにならず**捨てられます** (ホストがプロバイダだからです)。まさに 1 つのソースがどこでも動くようにするためです。

WIT の `result<T, E>` は値ではありません。ok アームが戻り値で、error アームはマップされた `E` を運ぶ `rontolisp:wit-error` コンディションをシグナルします。これは `handler-case` で捕捉でき、`rontolisp:wit-error-payload` でペイロードを取り出せます。

### コンポーネント: ホストがプロバイダになる(`--component`)

まったく同じソースを `--component` でコンパイルすると、インターフェースはコンポーネントモデルの本物の**インポート**になります。コンポーネントは自身の型でそれを宣言し、束縛された各関数はコアモジュールへ `canon lower` されるので、呼び出しは canonical ABI を通って外へ出ます。コンポーネントの中にプロバイダは一切ありません — **ホストがプロバイダ**であり、そのインターフェースをエクスポートするホスト(あるいは他のコンポーネント)なら何でもそれを満たせます。wasmtime は `wasi:keyvalue` を実装しているので、それに対して書かれたプログラムはアダプタも書き換えもなしに動きます:

```bash
rontolisp counter.lisp -o counter.wasm --component
wasmtime run -W gc=y -W exceptions=y -W component-model-more-async-builtins=y \
    -S keyvalue=y counter.wasm
```

豊かな型をマーシャリングするのは canonical ABI なので、コンポーネントの境界は Preview 1 の境界よりはるかに多くを運びます: `result` (その error アームは `rontolisp:wit-error` コンディションとして到着し、`handler-case` で捕捉できます)、`option`、`record` (キーワード plist)、`variant`、`enum`、`tuple`、`list<T>`、`list<u8>`、`string`、`bool`、そして `resource` ハンドル。

`list<T>` を除くすべてが**両方向で**渡ります。しかも引数は、同じ型が戻り値として取るのとまったく同じ形を取ります — ある呼び出しが返した値を、そのまま次の呼び出しに渡せます:

```console
;;; wasi:http/types, imported and called: a variant argument, whose `other` case
;;; carries a string
(http:outgoing-request-set-method req :post)
(http:outgoing-request-set-method req '(:other . "PATCH"))
(http:outgoing-request-method req)                 ; => (:other . "PATCH")

;;; wasi:sockets/types: an enum argument, then a variant whose case payload is a
;;; record (a keyword plist) carrying a tuple (a positional list)
(let ((s (sock:tcp-socket-create :ipv4)))
  (sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1))))
```

いまだにローワリングできない唯一の形は **`list<T>` の引数**です (`list<u8>` はバイト文字列として渡ります)。引数はフラット化されますが、リストは代わりに canonical な配列としてリニアメモリに書き込む必要があるためです。これは WIT の行を示すコンパイルエラーになります。`flags` は今のところどちらの向きでも渡りません。

コンポーネントが束縛**できない**インターフェースが 1 つあります: そのコンポーネントが自身の WASI 表面としてすでにインポートしているものです。しかもその表面はプログラムが使う機能に応じて増えます (`rontolisp:fetch` は `wasi:http` と `wasi:io` を、`rontolisp:tcp-*` 組み込みは `wasi:sockets/types` を引き込みます)。コンポーネントは同じインターフェースを 2 回インポートできないため、これもコンパイルエラーです: 組み込みと併用するのではなく、組み込みの*代わりに* WIT 束縛経由で使ってください。

コンポーネントが**インポートするのはプログラムが実際に呼ぶ関数だけ**です (この経路にはコアのツリーシェイカーがないため、使われないインターフェースメンバーはインポート自体から落とされます。`--no-prune` ですべて残せます)。[`--emit-wit`](#emit-wit) はその刈り込まれたインターフェースをコンポーネントの world に書き出し、`wasm-tools component wit` の出力とバイト単位で一致します。何もインポートしないコンポーネントは、この機能が存在しなかった頃のビルドとバイト単位で同一です。

コンポーネントの**合成**もこの仕組みです: `wasi:keyvalue/store` をインポートするコンポーネントは、それをエクスポートする任意の言語のコンポーネントへ [`wac`](https://github.com/bytecodealliance/wac) で差し込めます。ホストがランタイム組み込みである必要はありません。

現在の制限事項:

- `--no-gc` はこのディレクティブを明確なエラーで拒否します。その契約は、何もインポートしない素の MVP モジュールだからです。
- Preview 1 の境界を渡れるのは `rontolisp:wasm-import` が運べる型だけです — 32 ビットまでの整数スカラー、浮動小数点スカラー、`bool`、`string`、`list<u8>`、リソースハンドル。`record`、`option`、`result`、`s64` は、`--component`・インタプリタ・JVM のいずれもが束縛できるとしても、WIT ファイル名と行番号を示すコンパイルエラーになります (上の `wasi:keyvalue` プログラムが Preview 1 向けではなく、コンポーネントまたはインタプリタ／JVM 向けなのはそのためです: その `result` アームが Preview 1 の境界から遠ざけています)。コアインポートは素のホスト関数であり、より豊かな形を記述するためのコンポーネント型を持たないからです。`stream` と `future` はすべてのバックエンドで拒否されます。
- `--component` では、**`list<T>` の引数** (`list<u8>` を除く)、および位置を問わず `flags` はコンパイルエラーになります。`list<T>` は戻り値としては渡ります。
- コンポーネントは `wit-import` と `rontolisp:http-handler` (serve モード) を組み合わせられません。serve されるコンポーネントのインポートは固定の `wasi:http` 表面だからです。
- 束縛できるのは**インターフェース**です。world の `import` 項目は依然として読まれません。
- ディレクティブはトップレベルで、インターフェースを呼ぶコードより**前**に置かなければなりません (パッケージと束縛を定義するのがこれだからです)。`wit-export` とは逆です。

オプションの一覧、名前マッピングの規則、WIT 型の表は
[wit-import](../reference/functions/rontolisp-wit-import.md) と
[wit-provide](../reference/functions/rontolisp-wit-provide.md)
のリファレンスページにあります。

## 非 GC 出力(`--no-gc`)

上記の GC 値モデルの出力は — 最適化されたリアクターであっても — すべての値が GC ヒープ型(`i31ref`、float 構造体、`(ref eq)`)であるため、依然として **wasm-GC 対応**ランタイムを必要とします。`--no-gc` を追加すると、代わりに素の **MVP** モジュールが出力されます: rec グループなし、`struct`/`array`/`i31` 型なし、`eqref` なし、インポートなしです(素のリニアメモリはプログラムが文字列を使うときのみ追加され — [後述](#strings) — 単一の `fd_write` インポートは[印字](#printing-print--princ--terpri)するときのみ追加されます)。印字しないモジュールはインポートオブジェクトなしでインスタンス化でき、**`-W gc` なし**で任意の MVP クラスのランタイムで動作します:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, no -W gc needed
```

これは、各値をアンボックスな wasm スカラーへ直接ローワリングし、文字列には小さなリニアメモリ表現を加えることで達成されます — そのため対象サブセットは言語の制限であって、別の言語ではありません。プログラムの形も制限されます: トップレベルには `defun` と `rontolisp:wasm-export` ディレクティブ**のみ**を置けます(純粋計算リアクターであり、`_start` はありません)。境界指定子は `:int`、`:long`、`:float`、`:bool`、`:string`(および `:void`/省略)です。`:s-expr` は**非対応**です — このバックエンドが意図的に省いている cons/リーダー/プリンターのランタイムを必要とするためです。

数値ベクトルカーネル([`vec:` パッケージ](../guides/simd-acceleration.md))も `--no-gc` で動作し、デフォルトでは素のスカラーループへローワリングされます — そのためベクトルプログラムも上記の「任意の MVP ランタイムで動く」性質を保ちます。[`--simd`](#simd-acceleration---simd) を追加すると、それらのカーネルはネイティブの WebAssembly SIMD(`v128`)へローワリングされ、SIMD プロポーザル対応のランタイム(wasmtime ではデフォルト有効)が必要になります。

### 対象となるサブセット

関数が対象となるのは、その**推移的な呼び出しグラフ全体**が次のサブセットに収まる場合だけです:

- 数値とブール: 算術(`+ - * / mod rem 1+ 1- abs min max sqrt`)、整数ビット演算(`logand logior logxor lognot ash`)、比較と述語(`= < <= > >= not zerop plusp minusp evenp oddp`);
- 制御と束縛: `if`/`when`/`unless`/`cond`/`progn`/`let`/`let*`、再帰、他の対象関数の呼び出し;
- 反復とローカルな変更: `dotimes`/`do`/`do*` とその基盤の `while`/`setq`/`return`。let/`do` 束縛変数は自由に再代入できます。`loop` は非 cons 化節(数値 `for`、`sum`/`count`/`maximize`/`minimize`、`repeat`/`while`/`until`/`do`/`return`)に限り対象です — `collect`/`append`/`nconc` と `for ... in`/`on` の節はリストを確保するため対象外です;
- 浮動小数点/整数変換: `float truncate floor ceiling round`;
- 文字列と文字: 文字列リテラル、文字リテラル、`(concatenate 'string ...)`、`length`、`subseq`、`string=`、`char`、`char-code`/`code-char`、`char=`、および(整数・浮動小数点数・文字列の)`princ-to-string`。独立した文字型はありません: 文字はそのコードポイントで表現されるため、移植性のあるイディオム `(char= (char s i) #\x)` と `(char-code (char s i))` は他のバックエンドとまったく同じように振る舞い、素の `(char s i)` が `:int` 境界を越えるとコードが見えます;
- 印字: `print`、`princ`、`terpri`(省略可能なストリーム引数なし) — [後述](#printing-print--princ--terpri)を参照;
- メモリ回収: [`rontolisp:with-arena`](#reclaiming-from-lisp-rontolispwith-arena)。

それ以外のヒープオブジェクトを確保するもの(cons/リスト、シンボル、ベクタ、ハッシュテーブル、`eval`/`apply`、I/O、`dolist`/リスト反復、自由変数やグローバルへの代入、`&optional`/`&rest`/`&key` などのラムダリストキーワード — rest リストは cons です)は関数を対象外にします。黙って誤コンパイルするのではなく、問題の操作を名指しする**コンパイルエラー**になるため、境界は明示的なままです。

### 数値モデル

各値の wasm 型は静的型推論で選ばれます: 整数は `i64`、浮動小数点数は `f64` を使います。型はエクスポートの境界指定子を種として呼び出しグラフ上の不動点で推論され、整数と浮動小数点数が出会う場所(例えば `(* 3.14 n)`)では整数が `f64` へ昇格します。`i64` を使うことで整数演算は 2^63 まで正確です — GC バックエンドの `i31` fixnum よりも、全 `f64` ローワリング(2^53 までしか正確でない)よりもはるかに広く、例えば `a*a - (a-1)*(a+1)` は中間値が 2^53 を超えても正確に `1` のままです。

推論は自動的に拡幅もします: let/`do` 束縛変数は初期化子と代入されるすべての値のジョインを取るため、浮動小数点数と足し合わされる整数アキュムレータは `f64` になります:

```lisp
(defun sum-squares (n)        ; sum of i*i for i in 0..n-1, as a float
  (let ((acc 0))              ; acc starts as an integer 0 ...
    (dotimes (i n)
      (setq acc (+ acc (* (float i) (float i)))))  ; ... and widens to f64 here
    acc))
(sum-squares 5)  ; => 30.0
```

`--no-gc` のもとでは、これは `acc`(および戻り値)を `f64` と推論し、ループカウンタ `i` は `i64` のままです。

有理数型はないため、完全な Common Lisp とも GC バックエンドとも異なる点が 2 つあります: `/` は浮動小数点除算であり(`1/3` の比はありません)、ブール文脈で偽になるのは値がちょうどゼロのときです(Common Lisp は `nil` だけを偽として扱います)。**境界**指定子はホスト幅のままです — `:int`/`:bool` は(GC バックエンドと同様)32 ビットの `i32` として渡るため、32 ビット範囲外の戻り値はラップします。広い `i64` 範囲は内部計算にのみ適用されます。パラメータや結果が 32 ビット範囲を超えうるときは `:long` を宣言してください — `wrap`/`extend` なしの `i64` として境界を渡ります(`:long` は `--no-gc` 専用で、整数が `i31ref` である GC バックエンドは拒否します)。このモードが対象とする数値カーネル(階乗、数学/金融関数、バリデータ)については、結果はインタプリタおよび GC バックエンドと一致します。

### 文字列

文字列はリニアメモリ内の `[length][bytes]` ヘッダを指す `i32` ポインタで、`(concatenate 'string ...)` は新しいバッファをバンプ確保します — そのため文字列の組み立てはただのアキュムレータループです:

```lisp
(defun stars (n)               ; an n-character run of '*'
  (let ((out ""))
    (dotimes (k n)
      (setq out (concatenate 'string out "*")))
    out))
(stars 5)  ; => "*****"
```

スライスと検査も同じ表現の上で動きます: `length` はヘッダを読み、`subseq` はスライスを新しいバッファへコピーし、`string=` は内容をバイト単位で比較し、`char` はバイトをインデックスし、`princ-to-string` は整数を文字列化します — 蓄積だけでなく、ルーティング/パースのカーネルにも十分です:

```lisp
(defun describe-int (n)
  (let ((s (princ-to-string n)))
    (concatenate 'string s " has " (princ-to-string (length s)) " chars")))
(describe-int -42)  ; => "-42 has 3 chars"
```

文字列を使用するモジュールは(拡張可能な)リニアメモリを持ち、その `memory` と `__ronto_alloc(size)` バンプアロケータを関数とともにエクスポートします。`:string` パラメータはホストがメモリに書き込む `(ptr, len)` ペアとして渡され、`:string` の結果も同じ方法で返されます — そのため文字列を返すエクスポートは、`wasmtime --invoke` だけではなく、エクスポートされたメモリを読み書きできるホスト(JavaScript、小さな Node スクリプト、ブラウザのプレイグラウンド)を必要とします。[付録](#passing-strings-string)で JS 側を詳しく説明し、[`--no-gc --component`](#compact-component-output---no-gc---component) はこの手動プロトコルを丸ごと不要にします。

これが ASCII アートのマンデルブロレンダラーを wasm-GC なしで動かせる理由です: [`examples/console/mandelbrot-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/console/mandelbrot-nogc.lisp) は浮動小数点の脱出時間ループを保ちながら、描画したグリッドを印字する代わりに 1 つの文字列として返します:

```console
$ rontolisp examples/console/mandelbrot-nogc.lisp --no-gc --optimize -o mandelbrot.wasm
$ node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("mandelbrot.wasm"), {})).instance.exports;
  const [p, n] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
  process.stdout.write(Buffer.from(new Uint8Array(ex.memory.buffer, p, n)).toString());
})()'
```

### 印字(`print` / `princ` / `terpri`)

エクスポートされた関数は印字できます: `print`(読み取り可能な形 + 末尾の改行。文字列は引用符付きで出力)、`princ`(表示形、改行なし)、`terpri`(改行)が対象サブセット内で動作し、出力はインタプリタとバイト単位で一致します:

```console
$ cat show.lisp
(defun show (n)
  (print n)
  (print (* 1.5 n))
  (print "done"))
(rontolisp:wasm-export 'show :params '(:int) :returns :void)
$ rontolisp show.lisp --no-gc -o show.wasm
$ wasmtime run --invoke show show.wasm 4
4
6.0
"done"
```

浮動小数点数は GC バックエンドと同じ桁抽出プリンターを通って印字されます。IEEE のエッジ(`NaN`、`Infinity`/`-Infinity`、`-0.0`。2^63 以上の大きさは WASM バックエンドの `E` 表記の形を使います)も含みます。数値の `print` はそのテキストを一時的な文字列に描画して即座に回収するため、印字ループでヒープは成長しません。

知っておくべきことが 2 つあります:

- **印字するモジュールは 1 つのインポートを持ちます。** `print`/`princ`/`terpri` は単一の `wasi_snapshot_preview1.fd_write` インポートを通じて書き込みます — これは**プログラムが印字するときにのみ**追加されるため、印字しないモジュールはインポートゼロと正確なバイト列を保ちます。WASI Preview 1 ホストなら `fd_write` は自動で提供されますが(`wasmtime run`、Node 組み込みの `node:wasi` モジュール)、印字するモジュールは[マンデルブロのスニペット](#strings)のように空の `{}` インポートオブジェクトではインスタンス化できなくなります — 生の JavaScript 埋め込みは `{ wasi_snapshot_preview1: { fd_write } }` を供給する(または `node:wasi` を使う)必要があります。
- **ブールはリテラルでのみ名前で印字されます。** この値モデルには実行時ブール型がありません: `(print t)` / `(print nil)` は `t` / `nil` を印字しますが、`(print (> a b))` のような*計算された*ブールはその `0`/`1` 整数を印字します。省略可能なストリーム引数とパック float 配列の印字はコンパイルエラーです。

### メモリの回収(アリーナ API)

`__ronto_alloc` は決して解放しないバンプアロケータなので、**常駐**ホスト — 1 つのインスタンスを生かしたままループで呼び出し、毎回新しい入力バッファを確保するホスト — のリニアメモリは際限なく成長します。2 つの機構がそれを平坦に保ちます:

- **スカラー戻り値では自動。** エクスポートが非メモリのスカラー(`:int`/`:long`/`:float`/`:bool`/`:void`)を返す場合、そのラッパーはエントリでヒープトップをスナップショットし、出口で復元します。そのため*その呼び出し*が確保したすべて(`:string` 引数の内部コピーと、あらゆる `concatenate`/`subseq`/`princ-to-string` のスクラッチ)は戻り時に回収されます。ホスト側ですることはありません。
- **ホスト自身のバッファには手動。** ホストは入力バッファを呼び出しの*前*に確保するため、それはラッパーの自動リセットマークより下にあり、生きたまま残されます。それも回収するために、文字列を使用するモジュールは同じヒープポインタ上の対の関数もエクスポートします:

| export | signature | meaning |
| --- | --- | --- |
| `__ronto_alloc_mark` | `() -> i32` | snapshot the current bump-heap top |
| `__ronto_alloc_reset` | `(i32 mark) -> ()` | restore the top to a saved mark |

入力を確保する**前**にスナップショットを取り、結果を読み出した**後**に復元すれば、常駐インスタンスは何回呼び出されても完全に平坦なままです:

```bash
node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("count_vowels.wasm"), {})).instance.exports;
  const enc = new TextEncoder();
  const countVowels = (s) => {
    const b = enc.encode(s);
    const mark = ex.__ronto_alloc_mark();        // snapshot BEFORE allocating input
    const ptr = ex.__ronto_alloc(b.length);
    new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
    const n = ex.count_vowels(ptr, b.length);    // scalar result read out here
    ex.__ronto_alloc_reset(mark);                // pop the input + wrapper scratch
    return n;
  };
  const before = ex.memory.buffer.byteLength;
  for (let i = 0; i < 100000; i++) countVowels("Hello, World! " + i);
  console.log(before, "->", ex.memory.buffer.byteLength);   // 65536 -> 65536 (flat)
})()'
```

アリーナは手動のスタックであってガベージコレクタではないため、2 つのルールがあります:

- まだ生きているすべてのものより**前**に取ったマークにだけリセットしてください — まだ必要なデータの*後*に取ったマークへポップすると、そのデータが解放されます。
- `:string` を**返す**エクスポートは自動リセットしません(その結果は生きたヒープポインタです)。**`__ronto_alloc_reset` を呼ぶ前に、返されたバイト列をメモリから読み出してください** — 先にリセットすると文字列が解放され、次の確保がそれを上書きします。

[`count-vowels` の例](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)は、Node と [Endive](https://endive.run)(Java)の両ホストでこのレシピを一通り示します。

wasm-GC バックエンドも同じ `__ronto_alloc_mark`/`__ronto_alloc_reset` の対を、同じホスト側レシピでエクスポートします([上記](#reclaiming-the-hosts-buffer-the-arena-api))。ただしそちらで回収が必要なのは*ホストの*バッファだけです — Lisp 側が確保したものはエンジンが面倒を見ます。スカラー戻り値の自動リセットは `--no-gc` 専用です: 非 GC サブセットには cons もクロージャもハッシュテーブルもグローバル `setq` もなく、呼び出しが確保したものが呼び出しより長生きし得ないからこそ健全なのです。

### Lisp からの回収(`rontolisp:with-arena`)

上記の 2 つの機構はどちらも**エクスポート境界**で発火します — 1 回の呼び出しの*中*では何も解放されません。反復ごとに確保するループ(`concatenate 'string` は新しいバッファを、`vec:zeros`/`vec:ones` は新しいベクトルを作ります)は、したがって呼び出しの間ヒープを成長させます。[`rontolisp:with-arena`](../reference/macros/rontolisp-with-arena.md) はその回収境界をソース内で指名します: バンプヒープポインタをスナップショットし、本体を実行し、本体が確保したすべてをポップします — 本体自身の値だけを残して(文字列またはパック float 配列の結果はスナップショット位置へコピーダウンされます):

```lisp
(defun train (epochs n)
  (let ((acc 0.0))
    (dotimes (i epochs)
      (rontolisp:with-arena ()                    ; everything allocated inside ...
        (setq acc (+ acc (vec:sum (vec:ones n)))) ; ... is popped here
        ))
    acc))
```

アリーナがあれば 10 万回の反復も初期リニアメモリ内に収まります。なければ同じループは反復ごとにベクトル 1 つ分成長します。エスケープ契約は `__ronto_alloc_reset` と同じです: **本体内で確保されたものは、本体自身の値を除き、本体の後から到達可能であってはなりません。** インタプリタ、JVM バックエンド、デフォルト(wasm-GC)出力では、`with-arena` は観測上は素の `progn` です — 本物のガベージコレクタがすでに回収します — そのため同じソースがすべてのバックエンドで動作します。

### コンパクトなコンポーネント出力(`--no-gc --component`)

`--component` を追加すると、同じ MVP コアモジュールが **WASM コンポーネント**としてラップされ、エクスポートは正準 ABI を通じて WAVE 構文で呼び出せる型付きコンポーネントモデルエクスポートになります。印字しないコアモジュールはインポートゼロなので、ラップに WASI アダプタも共有メモリモジュールも wasm-GC も不要です — 小さなプログラムならコンポーネント全体が数百バイトに収まり、**wasmtime のフラグを一切必要とせず**動作します:

```bash
rontolisp fact.lisp --no-gc --component -o fact.wasm
wasmtime run --invoke 'fact(5)' fact.wasm
# 120
```

型付き WIT シグネチャは `:int` → `s32`、`:long` → `s64`、`:float` → `f64`、`:bool` → `bool`、`:string` → `string` に対応し、`:returns` 省略は結果なしです。コンポーネントは jco でもトランスパイルでき(`jco transpile`、`:long` は JavaScript の BigInt として現れます)、wasm-GC サポートなしで任意のコンポーネントモデルホスト上で動作します。

GC コンポーネントパスと違い、ここでは `:long` が有効です — 値が 32 ビット範囲を超えうるときに使ってください。バックエンド内部の `i64` 演算とそのまま一致します:

```lisp
;; cube.lisp
(defun cube (n) (* n n n))
(rontolisp:wasm-export 'cube :params '(:long) :returns :long)
```

```bash
rontolisp cube.lisp --no-gc --component -o cube.wasm
wasmtime run --invoke 'cube(2000000)' cube.wasm
# 8000000000000000000
```

`:string` 境界は本物のコンポーネントモデル `string` として越えます — どちら側にも手動のポインタ処理はありません。ホストは引数のバイト列をモジュール自身のメモリへローワリングし、結果を正準 ABI を通じて読み出します。その後モジュールは呼び出しごとの確保をすべて解放する(正準 *post-return* 関数がバンプアロケータをベースまでポップする)ため、常駐インスタンスは繰り返し呼び出しでもフラットに保たれます:

```bash
rontolisp greet.lisp --no-gc --component -o greet.wasm
wasmtime run --invoke 'greet("world")' greet.wasm
# "Hello, world"
```

[印字](#printing-print--princ--terpri)もここで動作します: 印字するプログラムには組み込みの **print マイクロアダプタ** — コアの単一の `fd_write` インポートを WASI 0.2 stdio(`wasi:cli/stdout` と、`wasi:io/streams` の*同期的な* `blocking-write-and-flush`)の上に実装する 3 つの小さな固定コアモジュール — が、プログラムが印字するときだけ配線されます。エクスポートは通常の同期リフトのまま、フラグゼロという性質も維持され(ホストは 0.2 stdio をデフォルトで提供します)、印字出力はインタプリタとバイト単位で一致します — 先の `show.lisp` を使うと:

```bash
rontolisp show.lisp --no-gc --component -o show.wasm
wasmtime run --invoke 'show(4)' show.wasm
# 4
# 6.0
# "done"
# ()
```

素の `--no-gc` 出力とのトレードオフ、および現在の制限:

- コンポーネントはコンポーネントモデル対応のホストを必要とします。生のコアモジュールは素の埋め込み API を通じて**任意の** WebAssembly エンジンで動きます。両方の出力が使えます — ホストごとに選んでください。コンポーネントは `--no-gc` のデフォルトでは*ありません*。(`--component` なしでは、`:string` は代わりに手動の `(ptr,len)` コア ABI で境界を渡ります。)
- コンポーネントは純粋なリアクターです: `wasi:cli/run` エントリはありません(トップレベルでは何も実行されません)。エクスポート内の印字は上記のマイクロアダプタで動作します。それ以外の I/O は通常どおり `--no-gc` サブセットの外です。`:async t` は拒否されます(サスペンドするための非同期アダプタが存在しません)。
- エクスポート名は lower-kebab-case のコンポーネントモデル名でなければなりません。その文法から外れる Lisp 名については、コンパイラが `:as` での改名を求めます。
- `--optimize` は組み合わせられます: コアモジュールはラップの前にツリーシェイキングされます。
- [`--emit-wit`](#emitting-the-wit-world---emit-wit) も組み合わせられ、型付きエクスポートだけの小さなインポートなし world(プログラムが印字するときは 0.2 stdio インポート付き)を書き出します。

## ブラウザでコンポーネントを実行する(jco)

コンポーネントは wasmtime 専用の成果物ではありません。`jco transpile` はコンポーネントを JavaScript に変換し、その結果はブラウザで動作します — エクスポートはただの JavaScript 関数になります。jco はコンポーネントモデルのエクスポート名を camelCase 化するため、WIT の `count-vowels` は `countVowels` として現れます。(jco 1.25.2 + Chrome 149 で確認。)

**`--no-gc --component` は何も必要としません。** その world はインポートを持たないため、jco は自己完結した単一の ES モジュール(コア WASM が base64 で内部に埋め込まれ、[`count-vowels`](#compact-component-output---no-gc---component) の例で約 90 KB)を、それ自身の `import` 文なしで出力します。ページ側が供給するものは何もありません — シムも、import map も、ポリフィルも不要です:

```bash
rontolisp count-vowels.lisp --no-gc --component --optimize -o cv.wasm
npx @bytecodealliance/jco transpile cv.wasm -o dist
```

```html
<script type="module">
  const { countVowels } = await import('./dist/cv.js');
  console.log(countVowels('Hello, World!'));  // 3
</script>
```

**印字する `--no-gc --component` は import map が 1 つだけ必要です。** その[印字マイクロアダプタ](#compact-component-output---no-gc---component)は WASI 0.2 stdio(`wasi:cli/stdout@0.2.0`、`wasi:io/streams@0.2.0`)をインポートし、これを実装するのが `@bytecodealliance/preview2-shim` です — このパッケージはブラウザ向けビルド(`dist/browser/`、`node:` 組み込みを含まない)を同梱しています。ページがすべきことは、jco がインポートする 2 つの指定子をそこへ対応づけることだけで、`print` はコンソールに書き出されます。

**wasm-GC の `--component` はロードされ計算もできますが、まだ印字はできません。** Chrome は wasm-GC、JSPI、正準 ABI のいずれにも対応しており、コンポーネントの同期エクスポートは正しい値を返します。残りを阻んでいるのは 2 つのギャップで、どちらも JavaScript 側にあります(wasmtime はすべて実行できます):

- 必要となる WASI 0.3 インポートにブラウザ実装がありません: `@bytecodealliance/preview3-shim` はパッケージの `exports` に `node` 条件しか宣言しておらず、`node:worker_threads`、`node:net`、`node:http` などを取り込みます。したがってページは、jco がモジュール先頭で分割代入する 9 つのメンバー — `environment.getEnvironment`、`stdout.writeViaStream`、`stderr.writeViaStream`、`stdin.readViaStream`、`monotonicClock.now`、`systemClock.now`、`preopens.getDirectories`、`types.Descriptor`、`random.getRandomU64` — の代役を手書きする必要があります。純粋計算のエクスポートであれば、これらは存在しさえすれば十分です。
- 印字はその先、jco 自身の生成コードの中で失敗します。生成コードは `FutureReadableEnd` / `FutureWritableEnd` / `FutureEnd` を*参照*しているのに、そのいずれも定義していません(`ReferenceError: FutureReadableEnd is not defined`)。この経路は `wasi:cli/stdout` の `write-via-stream` から到達します — その WIT の結果型が `future` だからです。これとは別に、jco はスタックフル非同期エクスポートをまだ*呼び出せません*。[`:async t`](#component-model-function-exports-wasm-export) の I/O エクスポートがまさにそれです。

ここでは Node の方が弱いホストです: Node 22 には JSPI がなく(`WebAssembly.Suspending is not a constructor`)、トランスパイルされた GC コンポーネントをインスタンス化することすらできません。Chrome にはできます。

## 横断的なフラグ

### 最適化(ツリーシェイキング)

デフォルトでは、コンパイルされたモジュールは、関数インデックスが固定に保たれているために、プログラムが実際に使うものとは無関係に**ランタイム全体**(プリンター、有理数、文字列、リーダー、`eval` ヘルパー、WASI インポートスロットなど)を埋め込みます。`--optimize` を追加すると、モジュールのルート(そのエクスポートと `_start`/`_initialize` エントリ)から到達不能なすべての関数を落とし、生き残りを再番号付けします。未使用の WASI インポートも除去されるため、純粋計算のリアクターモジュールは一握りの関数まで縮みます:

```bash
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~2 KB module
```

この `fact` の例では、モジュールは約 100 KB から 2 KB 未満まで縮みます。`--optimize` はオプトインで、動作を保存します: 実際の `call` 命令から呼び出しグラフを辿るため、到達可能なもの(組み込みの `eval`/`load` がディスパッチするコードを含む)はすべて保持されます。**GC の `--component`** パスでは no-op です(WASI 0.3 アダプタがコアの固定インポート/インデックスレイアウトに依存しているため、コンポーネントは無変更で出力されます)。[`--no-gc --component`](#compact-component-output---no-gc---component) では有効です — コアモジュールはラップの前にシェイクされます。同じフラグは [JVM 出力](jvm.md)のデッドコード除去も行います。

`--optimize` とは独立に（`--component` を含むすべての出力モードで）、コンパイルは常に同梱の Lisp ソースライブラリ（`linalg:`、`vec:`、JSON、URL、`equalp`/`string<`）をツリーシェイキングします。プログラムがソース中でその名前に一切言及しない（クォートされたシンボルや文字列リテラルの中も含む）ライブラリ関数はモジュールに含まれません。その帰結として、実行時に計算した文字列から名前を組み立てて `eval`/`apply` 経由で呼び出すライブラリ関数は、通常の「undefined function」エラーを通知します。その場合は `--no-prune`（または `--dynamic`）を付けてコンパイルすると、すべてのライブラリ定義が保持されます。

### SIMD アクセラレーション(`--simd`)

`--simd` はすべてのバックエンドに共通する唯一のアクセラレーションスイッチです: ベクトル化可能な [`vec:` および `linalg:` カーネル](../guides/simd-acceleration.md)を本物のベクトル命令へローワリングします。WASM では値モデルと直交します:

- **wasm-GC + `--simd`** はカーネルを GC 管理のレーングループ配列上のネイティブ固定幅 SIMD(`f64x2`/`f32x4`)へローワリングします — パック float 配列は通常の GC オブジェクトのままで、メモリはフラグなしの場合とまったく同じに振る舞います。`--component` および `--optimize` と組み合わせられ、通常どおり `wasmtime run -W gc` で実行します(wasmtime は SIMD プロポーザルをデフォルトで有効にしています)。
- **`--no-gc` + `--simd`** は同じカーネルをパックされたリニアメモリブロック上の `v128` へローワリングします。`--simd` なしの `--no-gc` は代わりに素のスカラーループを出力します — SIMD プロポーザルのないランタイムでも動く v128 フリーの MVP モジュールです。

全体像 — どのカーネルがベクトル化されるか、単精度リダクションの精度規則、測定された効果、`linalg` のインターセプト — は [SIMD アクセラレーションガイド](../guides/simd-acceleration.md)にあります。

## 付録: JavaScript からのモジュール呼び出し

リアクターモジュール(`--no-wasi` または `--no-gc`)は何もインポートしないため、ホスト側は丸ごと「インスタンス化してからエクスポートを呼び出す」だけです — そして Node とブラウザで同じコードです。端から端まで、コピー＆ペーストで動く完全な例を示します。3 つのエクスポートからなる小さなキットから始めます:

```lisp
;; mathkit.lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
(defun area (r) (* 3.141592653589793 r r))
(defun in-range (x lo hi) (if (< x lo) nil (if (> x hi) nil t)))
(rontolisp:wasm-export 'fact     :params '(:int)           :returns :int)
(rontolisp:wasm-export 'area     :params '(:float)         :returns :float)
(rontolisp:wasm-export 'in-range :params '(:int :int :int) :returns :bool)
```

`--no-gc`(任意のエンジンで動く)と `--optimize`(エクスポートから到達不能なものをすべて落とす — ここではモジュール全体が約 200 バイト)でコンパイルします:

```bash
rontolisp mathkit.lisp --no-gc --optimize -o mathkit.wasm
```

Node 18+ では、これを `run.mjs` として保存して `node run.mjs` を実行します:

```js
import { readFile } from 'node:fs/promises';

// Node reads the .wasm from disk. In a browser, use the streaming fetch shown below.
const bytes = await readFile(new URL('./mathkit.wasm', import.meta.url));
const { instance } = await WebAssembly.instantiate(bytes);   // no import object

const ex = instance.exports;
console.log(ex.fact(10));                         // 3628800
console.log(ex.area(2));                          // 12.566370614359172
console.log(Boolean(ex['in-range'](5, 0, 10)));   // true   (:bool crosses as 0 / 1)
console.log(Boolean(ex['in-range'](42, 0, 10)));  // false
```

```
3628800
12.566370614359172
true
false
```

ブラウザで違うのはバイト列の読み込み方だけです — `instantiateStreaming` は `fetch` を直接受け取ります — ページ全体は次のとおりです:

```html
<!doctype html>
<script type="module">
  const { instance } = await WebAssembly.instantiateStreaming(fetch('./mathkit.wasm'));
  const ex = instance.exports;
  document.body.textContent = `fact(10) = ${ex.fact(10)}, area(2) = ${ex.area(2)}`;
</script>
```

知っておく価値のある境界の詳細:

- `in-range` のようなハイフン付きの Lisp 名は有効な JavaScript 識別子ではないため、ブラケットアクセスで参照します: `ex['in-range'](...)`。
- `:int`/`:float` は素の JS 数値として届きます。`:bool` は `i32`(`0`/`1`)として渡るため、本物の JS ブールが欲しければ `Boolean(...)` で包んでください。
- **`--no-gc`** モジュールは**任意の** WebAssembly エンジンで動きます。GC の **`--no-wasi`** モジュールは wasm-GC 対応のエンジン(Node 22+、現行ブラウザ)を必要とします。上記の JavaScript はどちらでもバイト単位で同一です — コンパイルフラグを差し替えるだけで、他には何も変わりません。

### 文字列の受け渡し(`:string`)

上記のスカラーの例は、`:int`/`:float`/`:bool` が素の数値として境界を渡るため、メモリを必要としません。`:string` は代わりにモジュールのエクスポートする `memory` を通じて `(ptr, len)` ペアを渡します: ホストは(エクスポートされた `__ronto_alloc(size)` バンプアロケータで確保したオフセットに)引数のバイト列をメモリへ書き込み、`(ptr, len)` を渡し、エクスポートが返す `(ptr, len)` をデコードします。

`:string` は `--no-gc` のもとで動作するため、関数が非 GC の文字列サブセット(上記の[対象サブセット](#eligible-subset)を参照)に収まっている限り、モジュールは依然として**任意の**エンジンで動作します。プロトコルを示すには挨拶文ビルダーで十分です:

```lisp
;; greetkit.lisp
(defun greet (name) (concatenate 'string "Hello, " name "!"))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greetkit.lisp --no-gc --optimize -o greetkit.wasm
```

```js
import { readFile } from 'node:fs/promises';

const bytes = await readFile(new URL('./greetkit.wasm', import.meta.url));
const { instance } = await WebAssembly.instantiate(bytes);   // no import object
const ex = instance.exports;
const enc = new TextEncoder(), dec = new TextDecoder();

// Copy a JS string into linear memory; return its (ptr, len).
function write(str) {
  const b = enc.encode(str);
  const ptr = ex.__ronto_alloc(b.length);
  new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
  return [ptr, b.length];
}
// Decode a (ptr, len) result. Re-read ex.memory.buffer AFTER the call: a call may grow
// memory, which detaches the previous ArrayBuffer.
const read = (ptr, len) => dec.decode(new Uint8Array(ex.memory.buffer, ptr, len));

console.log(read(...ex.greet(...write('rontolisp'))));     // Hello, rontolisp!
```

```
Hello, rontolisp!
```

[`--no-gc --component`](#compact-component-output---no-gc---component) では、同じ `:string` エクスポートが型付きコンポーネントモデル `string` として境界を越えるようになり、上記のホスト側グルーコードはすべて不要になります(正準 ABI がコピーを行い、post-return 関数がヒープを平坦に保ちます)。

より高機能な文字列関数(`string-upcase`、`subseq`、`string=` など)は非 GC サブセットの外です。それらを使うということは、代わりに wasm-GC バックエンド(`--no-wasi`)向けにコンパイルするということです — 境界プロトコルは同一で、エンジンが wasm-GC 対応である必要があるだけです。下の `:s-expr` の例がそのパスを示します。

### リストの受け渡し(`:s-expr`)

`:s-expr` は**任意の** Lisp 値を S 式*テキスト*として運びます: モジュールは入力を組み込みリーダーで解析し、結果を印字して返します。同じ `(ptr, len)` / `__ronto_alloc` プロトコルの上でです。そのリーダー/プリンター/cons の機構は **wasm-GC 専用**なので、`:s-expr`(および上記のより高機能な文字列関数)には `--no-wasi` と wasm-GC 対応エンジン(Node 22+、現行ブラウザ)が必要です:

```lisp
;; textkit.lisp
(defun shout (s) (string-upcase s))
(defun rev (lst) (reverse lst))
(rontolisp:wasm-export 'shout :params '(:string) :returns :string)   ; "hello" -> "HELLO"
(rontolisp:wasm-export 'rev   :params '(:s-expr)  :returns :s-expr)    ; a list, reversed
```

```bash
rontolisp textkit.lisp --no-wasi --optimize -o textkit.wasm
```

```js
// Same instantiate + write/read helper as above (textkit.wasm needs a wasm-GC engine).
console.log(read(...ex.shout(...write('hello'))));         // HELLO
console.log(read(...ex.rev(...write('("a" "b" "c")'))));   // ("c" "b" "a")
```

```
HELLO
("c" "b" "a")
```

ブラウザでは読み込みの行だけが変わります(`WebAssembly.instantiateStreaming(fetch(...))`)。`write`/`read`/`memory`/`__ronto_alloc` のロジックは同一です。多値の `(ptr, len)` を返す関数は JS では 2 要素配列として現れます。`read(...ex.shout(...))` としているのはそのためです。
