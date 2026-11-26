# WASM ホスト境界(`wasm-export` / `wasm-import`)

モジュールとホストの境界を渡るものを、rontolisp 自身の型指定子で宣言する 2 つの補完的なディレクティブです。いずれもすべての WASM 出力形状で動作します(同じソースがすべてのバックエンドで動きます — インタプリタと JVM ではディレクティブは no-op またはスタブの defun になります)。

型付きの WIT 駆動の境界については、[WIT 契約ガイド](wit-contracts.md)を参照してください。Clojure のプログラムは同じ境界を [`rontolisp.wasm`](../clojure/reference/wasm.md) で宣言します。これはここのディレクティブにローワリングされます。

## Lisp 関数のエクスポート

デフォルトでは、コンパイルされたモジュールはエントリポイント(`_start`)しか公開しません。個々の Lisp 関数をホスト(`wasmtime --invoke`、JavaScript、または別のモジュール)から直接呼び出せるようにするには、`rontolisp:wasm-export` ディレクティブでマークし、パラメータと結果の WASM 境界型を宣言します:

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
```

```bash
rontolisp fact.lisp -o fact.wasm
wasmtime run --invoke fact fact.wasm 5
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
| `:bytes` | `(ptr, len)` argument / `(ptr, cap) -> len` result | an `(unsigned-byte 8)` vector as **raw bytes** — no UTF-8 in either direction; GC core-module shapes only (not `--component`, not `--no-gc`) |

`:string` は*値*を運びます(デコードされ、呼び出しごとに割り当てられます)。
`:bytes` は*転送*です: **呼び出し側がバッファを渡す** `read(2)` の形です。
`:bytes` の**結果**はエクスポートのパラメータに `(ptr, cap)` ペアを追加し —
ホストが(例えば `__ronto_alloc` で)`cap` バイトを確保し、ラッパーはそこへ
最大 `cap` バイトをコピーします — 単一の `i32` 結果はベクタの**全長**です。
バッファが小さすぎた場合は切り詰めではなくリトライになります。転送に
呼び出しごとの割り当てを使わないことが、チャンク単位の pull ループの
メモリをフラットに保ちます。

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
- エクスポートする関数は [`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md) でも構いません。境界が返り値のフューチャーを解決するため、ホストが受け取るのは宣言した型であってフューチャーではありません。

### エクスポートモード早見表

同じディレクティブは、`--no-gc` / `--component` フラグに応じて 4 つの異なるホスト契約にコンパイルされます:

| | GC core module (default / `--no-wasi`) | GC `--component` | `--no-gc` core module | `--no-gc --component` |
| --- | --- | --- | --- | --- |
| ホスト要件 | wasm-GC エンジン(wasmtime、Node 22+、現行ブラウザ) | wasmtime 46+ または wasm-GC + JSPI 対応のコンポーネントホスト([jco 経由のブラウザ](wasm-browser.md)ではロードと計算はできるが、まだ印字はできない) | **任意の** WebAssembly エンジン | 任意のコンポーネントモデルホスト、**フラグ不要** — 依存ゼロで動く [jco 経由のブラウザ](wasm-browser.md)を含む |
| エクスポートの形 | 生のコア関数 | 型付きコンポーネントモデルエクスポート(WAVE `--invoke`、jco) | 生のコア関数 | 型付きコンポーネントモデルエクスポート(WAVE `--invoke`、jco) |
| スカラー | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void |
| `:string` | 手動の `(ptr,len)` + `__ronto_alloc` | コンポーネントモデル `string`(正準 ABI) | 手動の `(ptr,len)` + `__ronto_alloc` | コンポーネントモデル `string`(正準 ABI) |
| `:s-expr` | 手動の `(ptr,len)` | コンポーネントモデル `string`(印字テキスト) | 非対応 | 非対応 |
| `:bytes` | 手動の `(ptr,len)` / 呼び出し側バッファの結果 | 非対応(`list<u8>` リフトは未対応) | 非対応 | 非対応 |
| 関数本体で使える機能 | 言語全機能 | 言語全機能 | [非 GC サブセット](wasm-nogc.md#eligible-subset) | [非 GC サブセット](wasm-nogc.md#eligible-subset) |
| エクスポート内の I/O | 動作する(実 WASI インポート。`--no-wasi` では出力は破棄、`random` は組み込み生成器、`getenv`/ファイル検索は「無い」と答え、時計はホストが `__ronto_set_time` で書き込んだ値、入力はトラップ) | 同期エクスポートでも通常は動作する。[`:async t`](wasm-component.md#component-model-function-exports-wasm-export) で残余のトラップリスクを除去 | `print` のみ(単一の `fd_write` インポート) | `print` のみ(組み込み WASI 0.3 stdout ブリッジ。エクスポートは async リフトになる) |
| プログラムのトップレベル | `_start` として実行 | `wasi:cli/run` として共存 | `defun` + ディレクティブのみ | `defun` + ディレクティブのみ |
| 呼び出しごとの文字列メモリ | ホスト管理(`__ronto_alloc` + [アリーナ API](wasm-gc-module.md#reclaiming-the-hosts-buffer-the-arena-api)。Lisp 側はエンジンが回収) | 正準 post-return が解放 | ホスト管理(`__ronto_alloc` + [アリーナ API](wasm-nogc.md#reclaiming-memory-the-arena-api)。スカラー戻り値では自動) | 正準 post-return が解放 |
| 典型的なサイズ | 約 2 KB（[ツリーシェイキング](../compiling/wasm.md#optimize-tree-shaking)済み。`--optimize=off` では約 100 KB) | 約 110 KB | 数十バイト〜数 KB | 数百バイト〜数 KB |

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
$ wasmtime run --preload host=host.wasm --invoke add10 main.wasm 32
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

- `:string`/`:s-expr` の**引数**は、モジュールのエクスポートする `memory` 内への `(ptr, len)` ペアとしてホストに届きます(`:s-expr` 引数は先に読み取り可能なテキストへ印字されます)。1 つのインポートに複数あってもそれぞれが専用の領域を持ち、呼び出しの間はすべて生存します — 復帰時に解放されるため、ホストは応答する前に必要な引数をすべて読み取る必要があります。
- **このポインタは借用された読み取り専用のものです。** そこへ書き込むことは未定義であり、何を壊すかは出力形状によって異なります: ここ(wasm-GC)ではその領域はこの 1 回の呼び出し用にコンパイラがステージするスクラッチで、復帰時にポップされるか、引数がリテラルだった場合は次にステージする呼び出しが上書きするため、呼び出し中に書き込んでも直後には死んでいるメモリに触れるだけです — 契約によってではなく、たまたま無害なだけです。[`--no-gc`](wasm-nogc.md#host-imports-rontolispwasm-import) にはステージングが一切なく、ポインタはモジュールがすでに保持しているブロックの `(ptr, len)` で、インスタンスの寿命いっぱい生存します。そのため書き込みはそのブロックを恒久的に破壊し、同じ綴りの文字列リテラルは 1 つのブロックへ重複排除されているため、その綴りを共有するすべての呼び出し箇所が、インスタンスが生きている限り道連れで壊れます。
- `:string` の**結果**はホストがリニアメモリに書き込む必要があります — エクスポートされた `__ronto_alloc` でバッファを確保し、`(ptr, len)` ペア(JavaScript では 2 要素配列)を返します。
- `:s-expr` の**結果**は組み込みリーダーで解析されるため、ホストはリスト構造全体をテキストとして渡し返せます。
- `:bytes` の**引数**は `(unsigned-byte 8)` ベクタを生の `(ptr, len)` ペアとしてステージします — UTF-8 エンコードを通らないため、任意のバイナリが正確に渡ります。
- `:bytes` の**結果**は呼び出し側バッファ方式です: Lisp シグネチャの末尾に受信用の `(unsigned-byte 8)` ベクタが 1 つ加わり、ホスト関数は末尾の `(ptr, cap)` ペア付きで呼ばれます — *`ptr` に最大 `cap` バイトを書き込み、全長 `n` を返す*。呼び出しは `n` を返し(バッファ長を超える `n` は「より大きいバッファでリトライ」の合図)、ラッパーのステージングは返却時にポップされるため、1 つのバッファを使い回す pull ループはリニアメモリをフラットに保ちます。
- `:u32` は符号なしの全範囲を両方向で正確に渡します。一方、`:int` の**引数**は内部の `i31ref` の 31 ビットまでで、超えるとトラップします。アドレス、サイズ、ホストが発行する ID は `:u32` で宣言してください。引数あたりのコードは `:int` より大きいので、小さいままの値は `:int` のほうが適しています。
- **出力先ポインタ**で答えるホスト関数は、プログラムが確保したセルに書き込みます。そのセルの確保と応答の読み返しには、[`:from "rontolisp:memory"`](../reference/functions/rontolisp-wasm-import.md#rontolispmemory--the-modules-own-linear-memory) でモジュール自身のメモリを束縛します。
- **非同期の**ホスト関数 — JSPI で `WebAssembly.Suspending` ラップされたインポート — は `:async t` で宣言します: 呼び出しは `rontolisp:await` で解決できる future を返し、ビルドはホストの義務(インポートの `Suspending` ラップ、そこへ到達しうるエクスポートの `promising` 経由呼び出し、呼び出しの直列化 — 再入されたエクスポートは両方の呼び出しを壊す代わりにトラップで拒否します。ただし [`--reentrant`](#overlapping-calls---reentrant) でコンパイルされたモジュールを除きます)を出力し、`--no-wasi` モジュールのトップレベルフォームから到達しうる呼び出しはコンパイルエラーになります(`_initialize` はサスペンドできない)。完全な契約は[リファレンスページ](../reference/functions/rontolisp-wasm-import.md)を参照してください。

制限:

- コアモジュール専用です: `--component` はこのディレクティブをエラーで拒否します。[`--no-gc`](wasm-nogc.md#host-imports-rontolispwasm-import) は受け付けます。型の語彙はより広く(ハウス整数が `i64` なので 64 ビットの指定子も渡ります)、`:s-expr`・`:bytes`・`:async t` はありません。
- インタプリタと JVM バックエンドでは、このディレクティブは呼び出すとエラーを通知するスタブを定義します。共有ソースはどこでもロードできますが、実際にインポートを呼び出すには WASM ホストが必要です。
- インポートされた関数にも wasm-GC 値モデルの他の関数と同じ 10 パラメータのアリティ上限があります。
- モジュールのインスタンス化には宣言したすべてのインポートの提供が必要です: `wasmtime run` はインポートモジュール名ごとに `--preload <module>=<file>.wasm` を必要とし、JavaScript ホストはインポートオブジェクトを渡します。

## ボディ境界の選択 (--host-boundary)

HTTP リアクター — ホストがエントリポイントを呼ぶ `--no-wasi` モジュールであり、そこでは [`clack:clackup`](clack.md) と [`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md) がこれにコンパイルされます — は双方向に 1 つの JSON エンベロープで話します。`--host-boundary` が決めるのは、**ボディ**がそのエンベロープの中に乗るか、その横を渡るかです。これは**モジュールがインポートするもの**を変えるので、`--emit-js-glue` の値ではなく独立したフラグになっています。

| | `envelope` (デフォルト) | `streaming` |
| --- | --- | --- |
| リクエストボディ | エンベロープの `"body"` キー | `env.readRequestBody(ptr, cap) -> i32`、1 回の呼び出しで 1 チャンク |
| レスポンスボディ | ヘッドの `"body"` キー | `env.writeResponseBody(ptr, len)`、1 回の呼び出しで 1 チャンク |
| `rontolisp:fetch` の応答ボディ (`--host-fetch`) | 応答ヘッドの `"body"` キー | `env.readResponseBody(ptr, cap) -> i32` |
| ホスト側の状態 | なし | 読み取りインポートごとに 1 カーソル |
| バイナリボディ | 生き残らない — `ff fe 41` は `ef bf bd ef bf bd 41` になる | 正確に渡る |
| 大きなボディ | リニアメモリがボディに比例する | 平坦なまま |
| ストリーミングされた上流応答 | バッファしてから転送 | チャンク単位で転送 |
| 生成されるホスト側 | `instantiate` と `defaultHost()` と `worker(module)` — 両者で同じ |

```console
$ rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch
$ wasm-tools print worker.wasm | grep -oE '\(import "[^"]+" "[^"]+"'
(import "env" "fetch"
```

**`envelope` がデフォルトであり、望むべき側です。** ボディが*ドキュメント*であるなら — JSON のリクエストを 1 つ読んで JSON のレスポンスを 1 つ返す Worker — 測定できないほどのコピーと引き換えに、ホスト側の状態が存在しない境界が得られます。この面で見つかった不具合はすべてその状態の寿命バグでした。次のいずれかに当てはまるときだけ `--host-boundary=streaming` を指定します:

- **ボディがバイナリ** — 画像、ファイル、protobuf、圧縮済みのもの。エンベロープはボディを JSON のテキストとして運ぶので、正しい UTF-8 でないバイト列は生き残りません: `ff fe 41` は `ef bf bd ef bf bd 41` の 7 バイトになって届き(2 オクテットが 2 つの置換文字に化ける)、隣の `content-length` は 3 のままです。どこにも報告されません。
- **ボディが大きい** — エンベロープはボディをまるごとリニアメモリに載せるのでメモリがボディに比例します。分離した側は 1 つの受信バッファを使い回すので、どれだけ大きくても平坦です。
- **上流のレスポンスを中継している** — 分離した側は全体を抱えずチャンク単位で転送します。

どちらの形も他方の部分集合ではなく、モジュールサイズもどちら向きにも 1% 程度しか違わないため、これはサイズの判断ではありません。ergonomics の判断でもありません: `--emit-js-glue` が両方のホスト側を書き出すので、どちらを選んでも JavaScript は 3 行です。

**ここでデフォルトが移動しました。気づくのは再ビルドのときです。** これ以前はすべての `--no-wasi` リアクタがボディをエンベロープの外へ出していました。フラグなしで再ビルドしたモジュールは今後ボディをエンベロープの中に入れます — 上の 3 ケースにとっては実際の劣化であり、それ以外にとっては何でもありません。`--host-boundary=streaming` を足せばモジュールはバイト単位で以前と同じです。

`--host-boundary` は `--no-wasi` と `.wasm` 出力を必要とし、`--component` や `--no-gc` とは併用できません: その 2 つはすでに in-band です(コンポーネントのホスト関数は canonical ABI を渡り、`--no-gc` にはボディを運ぶパック配列がありません — そのホストインポートが運ぶのはフラットなスカラーと文字列です)。素の WASI コマンドモジュールも同様で、そのホストは `wasmtime run` であり `env.*` インポートを何も満たしません。手書きのリアクター — `clack:clackup` を経由せず自前のエンベロープアダプタを書くもの — は `rontolisp-body-imports` リーダーフィーチャーでビルドに追従します。これらのインポートが存在する場所ちょうどで有効になります:

```lisp
#+rontolisp-body-imports
(rontolisp:wasm-import '%read-request-body :from "env" :as "readRequestBody"
                       :params '() :returns :bytes :async t)
```

## ホストグルーの生成 (--emit-js-glue)

ここまでのすべては宣言から導出されるので、JavaScript 側も同じく導出できます。`--emit-js-glue` はそれをモジュールの隣に書き出します(`out.wasm` -> `out.js`): インポートオブジェクト、双方向の `(ptr, len)` ステージング、呼び出しを囲む `__ronto_alloc` ブラケット、`WebAssembly.Suspending` ラッパー、ビルドが列挙したエクスポートちょうどに対する `WebAssembly.promising` エントリ、そしてサスペンドしうるモジュールが必要とする「一度に 1 呼び出し」のキューです。

```console
$ rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch --emit-js-glue
$ ls worker.*
worker.js  worker.lisp  worker.wasm
```

生成されたファイルが要求するのは、宣言では表現できない唯一のこと — 各ホスト関数が実際に*何をするか*だけです。`host` はインポートごとの素の関数で、インポートモジュールとフィールドをキーとし、普通の JavaScript 値を受け取って返します — `(ptr, len)` ペアではありません:

```js
import { instantiate, suspending } from "./worker.js";

const lisp = instantiate(module, {
  env: {
    fetch: suspending(async (request) => hostFetch(request)),
    readResponseBody: suspending(async () => nextChunk()),
    readRequestBody: () => take(requestBody),
    writeResponseBody: (chunk) => chunks.push(chunk),
  },
});
const reply = await lisp.handleRequest(head);
```

チャンクのソースは最終的に `null` を返さなければなりません。さもないとモジュールは同じオクテットを永久に引き取り続けます — 上の `take()` はボディを一度だけ渡してから終端を報告します。入り切らなかった分はグルーが保持し、次にモジュールへ入るときに捨てます。1 回の呼び出しの*内側*でソースが動くホスト(新しい上流レスポンスなど)は `lisp.drop("env.readResponseBody")` で捨てます — それを知っているのはその側だけだからです。

`suspending()` は、自分のどのエントリが promise を返すかをホストが宣言する方法です。エントリ単位なのは、ラッパーがただではないからです: そのラッパー越しに*同期的に*答えるインポートでも、スタックは park して一度イベントループに戻ります。1 つでもマークすればファイルは JSPI の形に切り替わり — マークされたインポートがラップされ、ビルドが列挙したエントリポイントが `promising` 経由になり、すべての呼び出しが 1 本の promise チェーンに乗ります。1 つもマークしなければ同じファイルが同期ホストを駆動し、エントリポイントは promise ではなく値を返します。マークされていないコールバックが promise を返した場合は、`i32` を待つモジュールに `Promise` を渡す代わりに、名前を挙げて報告されます。

1 回の呼び出しに属するホスト側の状態 — その呼び出し中にモジュールが引き取るもの、呼び出しが残していくもの — は、同じクリティカルセクションの内側で設定します。サスペンドした呼び出しはイベントループに戻るため、そうしないと次のリクエストがそれを動かしてしまうからです:

```js
const reply = await lisp.serially(async (entry) => {
  requestBody = bytes;
  chunks = [];
  return entry.handleRequest(head);
});
```

宣言だけでは取りこぼすインポートも書き出されます: `--host-random` のエントロピー源は、preview1 が `random_get(buf, len)` の意味を固定しているため、要求するのではなく*実装*されます。`--host-output` の `fd_write` も同様で、標準出力を `console.log` へ、標準エラーを `console.error` へ 1 行ずつ書きます。[`--host-timer`](wasm-gc-module.md#waits-in-the-host---host-timer) ではモジュールの待機も実装されます。`env.sleep` はタイマーで、`env.idle` と `env.ring` はキューそのもので実装され、待機に入った呼び出しは順番を次へ譲ります。このとき `instantiate` は promise を返し、`_initialize` も `promising` 経由で入り、`serially` は呼び出しの状態を第 2 引数に取ります。その呼び出しが動いている間、状態は `lisp.context` です。また `:bytes` の結果はモジュールのバッファではなくチャンクで答えます(`null` が終端)。生成されたカーソルが入り切らなかった分を保持するので、チャンクの出どころ — `ReadableStream` か `Uint8Array` か — だけがホストの決めることとして残ります。

**トランスポートがすでに固定しているホスト関数は、このファイルが書き出します。** リアクター境界の 2 つの半分はプログラムの選択ではまったくないので、生成ファイルはそれらをエクスポートします: `--host-fetch` が双方向を固定する `env.fetch` の実装である `defaultHost()` と、`Request` をエンベロープへ、ヘッドを `Response` へ写す `worker(module)` です。これは**どちらの**[境界](#choosing-the-body-boundary---host-boundary)でも成り立ちます: ボディがエンベロープを離れる側でも、その出どころのリーダーは `worker()` がすでに持っている `Request` と、これから組み立てる `Response` なので、ボディのインポートも書き出されます。Worker はこれで 3 行になり、`--emit-worker-entry` がそれを `index.js` としてグルーの隣に書き出します(このフラグは `--emit-js-glue` を含意します):

```console
$ rontolisp worker.lisp -o src/worker.wasm --no-wasi --host-fetch --emit-worker-entry
$ cat src/index.js
// The Worker. rontolisp --emit-worker-entry wrote this file once and never
// overwrites it, so it is yours to edit: worker() takes { remoteAddr, host }
// as its second argument (see ./worker.js).

import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

エントリは Worker が処理を引き取る場所なので、書き出しは**一度だけ**です: すでにある `index.js` は編集の有無にかかわらずそのまま残し、そのことを標準エラーに 1 行で知らせます。名前は `wrangler.jsonc` の `main` が指す `index.js` に固定で、フラグは値を取りません。グルーが `index.js` になる `-o index.wasm` は拒否されます。グルーに `worker()` がないモジュール(`rontolisp:http-handler` のリアクターでないもの)と、グルーが実装しないインポートを持つモジュールも拒否されます。後者では 3 行が最初のリクエストで失敗するからです。

どちらもデフォルトであって置き換えではありません。`worker(module, options)` は `host`(導出されたエントリの上に 1 つずつ重ねるインポートエントリ)と `remoteAddr`(エンベロープの任意項目であるクライアントアドレスを返す `(request, env, ctx) => string`)を受け取ります。後者はランタイム中立なファイルが推測してはならない唯一のものです(Cloudflare では `(r) => r.headers.get("cf-connecting-ip")`)。[`--host-sockets`](wasm-gc-module.md#sockets-from-the-host---host-sockets) を付けたビルドでは、ファイルは `hostSockets(connect)` もエクスポートします。これは `cloudflare:sockets` の `connect()` でモジュールのソケットを実装したもので、`worker()` には代わりの `connect` を渡せます。書き出されないのはプログラム自身が宣言したインポートです: それは `instantiate` が名前で要求し続け、生成ファイル冒頭のスケッチも `worker(module, { host })` に変わります。

このフラグは `--no-wasi` と `.wasm` 出力を必要とします: コンポーネントは自身のバインディングジェネレータ経由でインスタンス化され、`--no-gc` モジュールのホスト関数はエクスポートされたメモリ上のフラットなスカラーと `(ptr, len)` の組を取るだけなので — ステージングもアリーナのブラケットもサスペンドするエントリも導出せず — インポートオブジェクトを直接書けます。両方の境界にまたがる実例が 9 つあります — [examples/cloudflare-workers](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers) 配下のリアクタは 1 つを除きすべてで、`src/worker.js` は生成されてチェックインされ、`src/index.js` は上の 3 行です。`src/index.js` は `build.sh` が `--emit-worker-entry` で書き出し、`worker()` にクライアントアドレスを渡す例だけがチェックインしています。例外は [httpbin](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin) で、理由もそこに書いてあります: `rontolisp:wasm-export` を手で宣言しており、エンベロープ自身のエントリポイントとして認識されるのは*合成された*ブリッジだけなので、`worker()` は書き出されず、ホストは手書きのままです。

### フラットな型の集合に収まらない WIT インターフェース

[`rontolisp:wit-import`](wit-contracts.md#importing-a-wit-interface-wit-import) が束縛する関数の引数と結果がすべてフラットな型の集合(32 ビットまでの整数、浮動小数点数、`bool`、`string`、`list<u8>`、ハンドル)に収まるなら、上の `rontolisp:wasm-import` に下ろされます。`--no-wasi` のモジュールでは、この集合に収まらない関数(`result`、`option`、record、variant、list を使うもの)を 1 つでも束縛すると、そのインターフェースはコンポーネントのコアモジュールと同じ形でインポートされます。インポートモジュールはインターフェースの正規 ID、フィールドは関数の canonical ABI 上の名前、シグネチャはそのフラット化です。

```console
$ wasm-tools print worker.wasm | grep import
  (import "wasi:keyvalue/store@0.2.0-draft2" "open" (func (;0;) (type 62) (param i32 i32 i32)))
  (import "wasi:keyvalue/store@0.2.0-draft2" "[method]bucket.get" (func (;1;) (type 63) (param i32 i32 i32 i32)))
  ...
  (import "wasi:keyvalue/store@0.2.0-draft2" "[resource-drop]bucket" (func (;5;) (type 67) (param i32)))
```

canonical ABI での値の持ち上げと引き下げはグルー自身が行うので、ホスト関数が受け取って返すのは JavaScript の値です。

| WIT | JavaScript |
| --- | --- |
| `bool`、32 ビットまでの整数、`f32`、`f64` | 真偽値、数値 |
| `s64`、`u64` | BigInt |
| `char` | 1 コードポイントの文字列 |
| `string` | 文字列 |
| `list<u8>` | `Uint8Array`(文字列で答えるとその UTF-8 のオクテット) |
| `list<T>`、`tuple<...>` | 配列 |
| `option<T>` | 値、または `undefined` |
| `record` | フィールド名を camelCase にしたキーを持つオブジェクト |
| `variant`、ほかの型の中の `result` | `{ tag, val }`(ケース名とペイロード。`result` のケース名は `ok` と `err`) |
| `enum` | ケース名 |
| リソース | ホスト自身の値 |

結果が `result` の関数は、ok の値で答え、エラー側は、エラーの値を持つグルーの `WitError` を投げて答えます(`throw new WitError({ tag: "other", val: "..." })`)。プログラムからは `rontolisp:wit-error` コンディションとして、Java のプログラムからはバインディングが投げる `WitException`、またはメソッドの宣言に従って返す `WitResult.Err` として見えます。ホスト関数がそれ以外を投げると、その呼び出しはトラップします。リソースの値は JavaScript 側にあるグルーの表に置かれ、モジュールはその添字を持ちます。添字はリソースの解放(`[resource-drop]bucket`、グルーが実装します)で表から外れ、ホストに同名のエントリがあれば、その値を渡して呼びます。`flags`、`stream`、`future`、`async func` は拒否され、`--reentrant` も使えません。

ホストは promise で答えてよいので、グルーが同期的に答えるもの(解放と `wasi:keyvalue` の `open`)を除くこれらのインポートは、サスペンドしうるインポートとして扱います。モジュールは重なった呼び出しを拒否し、ホストがエントリを 1 つでもマークするとすべてのエクスポートが `promising` 経由になり、モジュールのロード経路から到達できる呼び出しは、`:async t` のインポートと同じくコンパイルエラーです。

#### Workers KV で答える `wasi:keyvalue`

`wasi:keyvalue/store@0.2.0-draft2` と `wasi:keyvalue/batch@0.2.0-draft2` には、グルー自身が答えます。`defaultHost()` が各メンバーを Workers KV の名前空間の上で実装し、`worker(module)` が Worker のバインディングをそこに渡すので、状態を `wasi:keyvalue` に置くプログラムは、手で何も書かずに Worker になります。デフォルトのストア(`""`)はバインディング `KEYVALUE`、それ以外の識別子は同じ名前のバインディングです。`worker(module, { keyvalue: { "": "SESSIONS" } })` のように、識別子を別のバインディング名にも、名前空間そのもの(node のホストが用意したものなど)にも対応づけられます。KV の名前空間を指さない識別子は `error::no-such-store` に、失敗した KV の呼び出しはそのメッセージを持つ `error::other` になります。

```jsonc
// wrangler.jsonc: wrangler deploy creates the namespace when it has no id
"kv_namespaces": [{ "binding": "KEYVALUE" }]
```

ストアの性質は Workers KV の性質で決まります。

- **1 回の呼び出しの書き込みは、応答を返してから書きます。** 書き込み 1 回に 0.15〜0.25 秒かかるので、応答は書き込みを待ちません。グルーは呼び出し中の書き込みを手元に置き、呼び出し自身の読み出しにはそこから答え、呼び出しが応答を返した後、キーごとに 1 回だけ応答の裏で書きます(`ctx.waitUntil`)。同じアイソレートで後に続く呼び出しは、書き込み中の値をグルーから読みます。
- **1 つのキーへの書き込みは 1 秒に 1 回までです。** Workers KV は 1 つのキーへの書き込みを 1 秒に 1 回しか受け付けず、2 回目を `429` で拒否します。一方、セッションストアは 1 回のリクエストで同じキーに何度も書きます。キーの次の書き込みは前の書き込みの 1 秒後まで待ち、その間に渡された値は待っている値を置き換えます。`429` で拒否された書き込み(ほかのアイソレートが同じキーに書いた場合)は、Cloudflare が求めるとおり、間隔を延ばしながら再試行します。それでも失敗した書き込みは、応答がすでに出ているのでログに出します(`console.error`)。
- **結果整合です。** 書き込みは書いた拠点では通常すぐに見え、ほかの拠点では最大 60 秒後に見えます。リクエストが 1 つの拠点に届くクライアントは自分の書き込みを読めますが、2 つの拠点に届いたリクエストは古い値を読むことがあります。前の応答の書き込みが終わる前にほかのアイソレートに届いたリクエスト(続けざまに送ったリクエスト)は、書き込み前の値を読みます。同じキーへの 2 つの書き込みでは、後のほうが残ります。
- **`atomics` には答えません。** Workers KV には compare-and-swap もインクリメントもないので、答えれば、持っていない原子性があるように見せることになります。`batch` には、キーごとに 1 回ずつ KV を呼んで答えます。ドラフト自体が `batch` を原子的と定めていないからです。
- **答えるのは `0.2.0-draft2` だけです。** 最初のドラフトでは `list-keys` のカーソルが `u64` ですが、Workers KV のカーソルは文字列です。

[`examples/cloudflare-workers/hello-session-one-source`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/hello-session-one-source) は、セッションをこの方法で保持する Java のサーブレットです。

## 呼び出しのオーバーラップ (--reentrant)

サスペンドしうるモジュールは、デフォルトでは 2 つ目の呼び出しを拒否します:
モジュール内に呼び出しごとの状態の持ち主がいないため、各エクスポートラッパーは
再入ガードを持ち、ビルドの義務行は*呼び出しの直列化*を求めます。これは正しい
挙動ですが、I/O バウンドなワークロードの幅をまるごと失います — 直列化された
1 インスタンスでは、8 個の同時アップストリーム往復に 8 往復ぶんかかります。
リクエストごとの 1 インスタンスはキューを避けられますが、呼び出しごとの
インスタンス化とインスタンスごとの GC ヒープを支払います。

`--reentrant` は、**1 つの**インスタンス上でオーバーラップを健全にする
オプトインです: モジュールが呼び出しごとの状態を自分で持つようになり、ガードは
外され、JSPI ホストはある呼び出しが park している間に別の呼び出しを開始でき
ます。重なるのは park している時間だけで、スタックは常に 1 本ずつ実行される
ため、得られるのは I/O のオーバーラップであり、CPU の並列性ではありません。

何が移り、ホストが何を負うか:

- 動的束縛されるすべてのスペシャル変数は、共有のモジュールグローバルではなく
  呼び出しごとのタスクレコードに住みます。同じ変数を束縛してオーバーラップ
  する 2 つの呼び出しは、それぞれ自分の束縛を読み戻します。
- park をまたいで生存すべきリニアメモリのステージングは、スクラッチスタック
  から再利用される park ブロック(いずれもエクスポートされる
  `__ronto_park_alloc` / `__ronto_park_free`)へ移ります。3 つの規則が
  従います: `:string`/`:s-expr` の**エクスポート結果**の `(ptr, len)` は
  *読み手*がデコード後に解放する park ブロックです。`:string`/`:s-expr` の
  **インポート結果**は park ブロックに書き込む必要があり、モジュールが解放
  します。ホストがエクスポートに渡す `:bytes` 受信バッファも park ブロック
  でなければなりません。
- エントリ呼び出しを囲むアリーナブラケット(`__ronto_alloc_mark` /
  `__ronto_alloc_reset`)は、呼び出しが開始した瞬間に*同期的に*ポップされ
  (引数はエントリ時点で消費済み)、リセットが生存中の park ブロックより
  下へ行くことはありません。

[`--emit-js-glue`](#generating-the-host-glue---emit-js-glue) はこのすべてを書き
出し、キューを外します。生成されたホストには手作業は不要で、手書きホスト
向けには同じ規則をビルドの義務行が述べます。

このフラグにはサスペンドしうるプログラム(`:async t` インポート、または
`--host-fetch` で `rontolisp:fetch` を実際に使用)と `--no-wasi` コア
モジュールが必要です。[`--host-boundary=streaming`](#choosing-the-body-boundary---host-boundary)
とは合成できます: `--reentrant` の下では、すべてのボディインポートが先頭に
`:int` の id を持ちます — リクエストエンベロープは `"call-id"` キーで自分の
呼び出しを、fetch の応答ヘッドは `"body-id"` で自分のボディを名指しします —
これにより各 pull / push は 1 本のホスト側カーソルを共有する代わりに、自分が
属する対象を名乗ります。生成される glue は id を発行し、呼び出しごとの状態を
その id でキー付けします。先頭 id の*ない*ボディインポートはこのフラグの下
では拒否されます。`--dynamic` とは併用できません。
使いどころは、I/O バウンドで*かつ*リクエストごとの 1 インスタンスを許容
できないワークロードです: envelope 境界の Worker 形状での実測では、
100 ms のアップストリーム往復 8 個の同時処理が、直列化の約 800 ms に対して
1 インスタンスで約 125 ms で答えます。
