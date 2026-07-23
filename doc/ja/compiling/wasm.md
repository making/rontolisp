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

- **値モデル。** デフォルトでは値は WebAssembly の **GC ヒープ**上に置かれ(整数は `i31ref`、浮動小数点数は構造体にボックス化)、**言語全機能**をサポートしますが、wasm-GC 対応ランタイム(wasmtime 14+、Node 22+、現行ブラウザ)が必要です。`--no-gc` は代わりに言語の**純粋計算サブセット**をアンボックスな `i64`/`f64` スカラーとリニアメモリ文字列へローワリングします — 結果は**任意の** WebAssembly エンジンで動く素の MVP モジュールで、サイズも桁違いに小さくなります。
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
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~18 KB module
```

この `fact` の例では、モジュールは約 170 KB から約 18 KB まで縮みます。`--optimize` はオプトインで、動作を保存します: 実際の `call` 命令から呼び出しグラフを辿るため、到達可能なもの(組み込みの `eval`/`load` がディスパッチするコードを含む)はすべて保持されます。**GC の `--component`** パスでは no-op です(WASI 0.3 アダプタがコアの固定インポート/インデックスレイアウトに依存しているため、コンポーネントは無変更で出力されます)。[`--no-gc --component`](../guides/wasm-nogc.md#compact-component-output---no-gc---component) では有効です — コアモジュールはラップの前にシェイクされます。同じフラグは [JVM 出力](jvm.md)のデッドコード除去も行います。

さらに小さく仕上げたい場合は、同じ `fact.lisp` を [`--no-gc`](../guides/wasm-nogc.md) でコンパイルすると `fact` は unboxed な `i32` にローワリングされ、18 KB を占めていた GC ランタイム一式（リーダーの大文字化テーブル、条件クラス階層、cons セル、プリンタ）が丸ごと落ちます:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, from a ~76 byte module (no -W gc)
```

ソースは変更不要で（`wasm-export` は値モデルを問わず同じ挙動）、生成モジュールは `-W gc` の要求も落とします。

`--optimize` とは独立に（`--component` を含むすべての出力モードで）、コンパイルは常に同梱の Lisp ソースライブラリ（`linalg:`、`vec:`、JSON、URL、`equalp`/`string<`）をツリーシェイキングします。プログラムがソース中でその名前に一切言及しない（クォートされたシンボルや文字列リテラルの中も含む）ライブラリ関数はモジュールに含まれません。その帰結として、実行時に計算した文字列から名前を組み立てて `eval`/`apply` 経由で呼び出すライブラリ関数は、通常の「undefined function」エラーを通知します。その場合は `--no-prune`（または `--dynamic`）を付けてコンパイルすると、すべてのライブラリ定義が保持されます。

### SIMD アクセラレーション(`--simd`)

`--simd` はすべてのバックエンドに共通する唯一のアクセラレーションスイッチです: ベクトル化可能な [`vec:` および `linalg:` カーネル](../guides/simd-acceleration.md)を本物のベクトル命令へローワリングします。WASM では値モデルと直交します:

- **wasm-GC + `--simd`** はカーネルを GC 管理のレーングループ配列上のネイティブ固定幅 SIMD(`f64x2`/`f32x4`)へローワリングします — パック float 配列は通常の GC オブジェクトのままで、メモリはフラグなしの場合とまったく同じに振る舞います。`--component` および `--optimize` と組み合わせられ、通常どおり `wasmtime run -W gc` で実行します(wasmtime は SIMD プロポーザルをデフォルトで有効にしています)。
- **`--no-gc` + `--simd`** は同じカーネルをパックされたリニアメモリブロック上の `v128` へローワリングします。`--simd` なしの `--no-gc` は代わりに素のスカラーループを出力します — SIMD プロポーザルのないランタイムでも動く v128 フリーの MVP モジュールです。

全体像 — どのカーネルがベクトル化されるか、単精度リダクションの精度規則、測定された効果、`linalg` のインターセプト — は [SIMD アクセラレーションガイド](../guides/simd-acceleration.md)にあります。
