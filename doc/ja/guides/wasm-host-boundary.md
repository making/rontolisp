# WASM ホスト境界(`wasm-export` / `wasm-import`)

モジュールとホストの境界を渡るものを、rontolisp 自身の型指定子で宣言する 2 つの補完的なディレクティブです。いずれもすべての WASM 出力形状で動作します(同じソースがすべてのバックエンドで動きます — インタプリタと JVM ではディレクティブは no-op またはスタブの defun になります)。

型付きの WIT 駆動の境界については、[WIT 契約ガイド](wit-contracts.md)を参照してください。

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
| `:int` | `i32` | full 32-bit signed range |
| `:long` | `i64` | full 64-bit signed range on every backend |
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
| ホスト要件 | wasm-GC エンジン(`wasmtime -W gc`、Node 22+、現行ブラウザ) | wasmtime 46+(`-W gc=y`)または wasm-GC + JSPI 対応のコンポーネントホスト([jco 経由のブラウザ](wasm-browser.md)ではロードと計算はできるが、まだ印字はできない) | **任意の** WebAssembly エンジン | 任意のコンポーネントモデルホスト、**フラグ不要** — 依存ゼロで動く [jco 経由のブラウザ](wasm-browser.md)を含む |
| エクスポートの形 | 生のコア関数 | 型付きコンポーネントモデルエクスポート(WAVE `--invoke`、jco) | 生のコア関数 | 型付きコンポーネントモデルエクスポート(WAVE `--invoke`、jco) |
| スカラー | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void |
| `:string` | 手動の `(ptr,len)` + `__ronto_alloc` | コンポーネントモデル `string`(正準 ABI) | 手動の `(ptr,len)` + `__ronto_alloc` | コンポーネントモデル `string`(正準 ABI) |
| `:s-expr` | 手動の `(ptr,len)` | コンポーネントモデル `string`(印字テキスト) | 非対応 | 非対応 |
| 関数本体で使える機能 | 言語全機能 | 言語全機能 | [非 GC サブセット](wasm-nogc.md#eligible-subset) | [非 GC サブセット](wasm-nogc.md#eligible-subset) |
| エクスポート内の I/O | 動作する(実 WASI インポート。`--no-wasi` では出力は破棄、`random` は組み込み生成器、`getenv`/ファイル検索は「無い」と答え、時計はシグナル、入力はトラップ) | 同期エクスポートでも通常は動作する。[`:async t`](wasm-component.md#component-model-function-exports-wasm-export) で残余のトラップリスクを除去 | `print` のみ(単一の `fd_write` インポート) | `print` のみ(組み込み WASI 0.3 stdout ブリッジ。エクスポートは async リフトになる) |
| プログラムのトップレベル | `_start` として実行 | `wasi:cli/run` として共存 | `defun` + ディレクティブのみ | `defun` + ディレクティブのみ |
| 呼び出しごとの文字列メモリ | ホスト管理(`__ronto_alloc` + [アリーナ API](wasm-gc-module.md#reclaiming-the-hosts-buffer-the-arena-api)。Lisp 側はエンジンが回収) | 正準 post-return が解放 | ホスト管理(`__ronto_alloc` + [アリーナ API](wasm-nogc.md#reclaiming-memory-the-arena-api)。スカラー戻り値では自動) | 正準 post-return が解放 |
| 典型的なサイズ | 約 100 KB([`--optimize`](../compiling/wasm.md#optimize-tree-shaking) で約 2 KB) | 約 110 KB | 数十バイト〜数 KB | 数百バイト〜数 KB |

各形状の詳細 — エクスポートの呼び出し方、その中で動くもの、各ホストが提供すべきもの — はそれぞれのガイドを参照してください:
[wasm-GC コアモジュール](wasm-gc-module.md)、
[WASI 0.3 コンポーネント](wasm-component.md)、
[--no-gc 出力とそのコンパクトなコンポーネントラップ](wasm-nogc.md)。

## ホスト関数のインポート

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

[WebGL トライアングルの例](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-triangle)はこのパターンの hello world です: 10 個のインポート関数、エクスポートなしで、色付きの三角形をすべて Lisp から描画します。[WebGL キューブの例](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-cube)は 3D を加えます: 透視投影と回転の行列を毎フレーム Lisp で計算します。[WebGL ギャラクシーの例](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)は同じ発想を完全なブラウザプログラムに育てたものです: WebGL パイプライン全体が Lisp から駆動されます — GLSL シェーダは Lisp ソース内にあり、Lisp が 32 個のインポートされたホスト関数を通じてコンパイル・リンク・バッファ確保とすべてのドローコールを発行し、JavaScript はハンドルテーブル上の 1 行バインディングだけを提供します — そのバインディングは境界を宣言する [WIT](wit-contracts.md#importing-a-wit-interface-wit-import) から生成されています。

スカラー型以外の境界の詳細:

- `:string`/`:s-expr` の**引数**は、モジュールのエクスポートする `memory` 内への `(ptr, len)` ペアとしてホストに届きます(`:s-expr` 引数は先に読み取り可能なテキストへ印字されます)。
- `:string` の**結果**はホストがリニアメモリに書き込む必要があります — エクスポートされた `__ronto_alloc` でバッファを確保し、`(ptr, len)` ペア(JavaScript では 2 要素配列)を返します。
- `:s-expr` の**結果**は組み込みリーダーで解析されるため、ホストはリスト構造全体をテキストとして渡し返せます。

制限:

- デフォルト(wasm-GC)の Preview 1 出力専用です: `--component` と `--no-gc` はこのディレクティブをエラーで拒否します。
- インタプリタと JVM バックエンドでは、このディレクティブは呼び出すとエラーを通知するスタブを定義します。共有ソースはどこでもロードできますが、実際にインポートを呼び出すには WASM ホストが必要です。
- インポートされた関数にも wasm-GC 値モデルの他の関数と同じ 7 パラメータのアリティ上限があります。
- モジュールのインスタンス化には宣言したすべてのインポートの提供が必要です: `wasmtime run` はインポートモジュール名ごとに `--preload <module>=<file>.wasm` を必要とし、JavaScript ホストはインポートオブジェクトを渡します。
