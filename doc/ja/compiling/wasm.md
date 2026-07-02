# WASM へのコンパイル

`-o` で `.wasm` で終わる出力パスを `rontolisp` に渡すと、ソースをインタプリタで実行する代わりに WebAssembly バイナリへコンパイルします。JVM バックエンドと同様、ターゲットを選択するのは出力の拡張子であり、バイナリはサードパーティのアセンブラを使わずに手作業で出力されます。結果は wasm-GC 対応の任意のランタイムで実行できます。

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
```

```
3
```

生成される `.wasm` バイナリは次を使用します。

- **wasm-GC** -- 整数は `i31ref` として表現されます。浮動小数点数は `float_struct { f64 }` にボックス化されます。スタック上のすべての値は `(ref eq)` 型です。
- **WASI Preview 1** -- 標準出力への出力には `fd_write` を使用します。

wasmtime 14 以降などの wasm-GC 対応ランタイムが必要です。

WASM バックエンドでは、関数（`defun` または `lambda`）が取れるパラメータは最大 **7 個** です。それより多いアリティはコンパイルエラーになります（インタプリタおよび JVM バックエンドにはこのような制限はありません）。範囲内に収めるには、余分な引数をリストにまとめてください。

デフォルトの出力は、WASI の `_start` エントリーポイントのみを公開する Preview 1 コアモジュールです。以下のセクションでは、WASM 固有のオプションを扱います。個々の関数をホストから呼び出し可能にする（`rontolisp:wasm-export`）、リアクター/ライブラリモジュールのために WASI インポートを除去する（`--no-wasi`）、ツリーシェイキングによってモジュールを縮小する（`--optimize`）、任意のエンジンで動作する素の非 wasm-GC モジュールを出力する（`--no-gc`）、WASI 0.3 コンポーネントを出力する（`--component`）。

## Lisp 関数のエクスポート

デフォルトでは、コンパイルされたモジュールは WASI の `_start` エントリーポイントのみを公開します。個々の Lisp 関数をホスト（`wasmtime --invoke`、JavaScript、または別のモジュール）から直接呼び出せるようにするには、`rontolisp:wasm-export` ディレクティブでマークし、そのパラメータと結果の WASM 境界型を宣言します。

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

型指定子とその境界表現は次のとおりです。

| 指定子 | WASM 境界 | 備考 |
| --- | --- | --- |
| `:int` | `i32` | 31 ビット符号付き範囲（内部の `i31ref`） |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` は `nil`、それ以外の非ゼロ値は `t` |
| `:string` | `(ptr, len)` | リニアメモリ内の UTF-8 バイト列 |
| `:s-expr` | `(ptr, len)` | リニアメモリ内の S 式テキスト（関数以外の任意の値） |

デフォルトの wasm-GC 出力は 5 つすべての指定子をサポートします（上記の `:int` の範囲は内部の `i31ref` です）。非 GC バックエンド（[`--no-gc`](#non-gc-output---no-gc)）は `:int`/`:float`/`:bool`/`:string` をサポートします（内部の整数範囲はより広くなります）が、cons/リーダー/プリンターのランタイムを必要とする `:s-expr` はサポートしません。

副作用のある関数は、`:returns` を省略する（あるいは `nil`、`'()`、`:void` として指定する）ことで **void** の結果を宣言できます。その場合、ラッパーは Lisp の戻り値を破棄し、WASM の結果を持ちません。同様に、`:params` を省略するか `nil` または `'()` とした場合は引数なしを意味します。

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

パラメータと結果がすべてスカラー（`:int`/`:float`/`:bool`）である関数は素の数値シグネチャを持つため、`wasmtime --invoke` から直接呼び出せます。メモリを介する `:string` および `:s-expr` 指定子は、モジュールがエクスポートする `memory` を通じてポインタ/長さのペアを受け渡すため、それを読み書きできるホスト（例えば JavaScript）が必要です。入力用に、モジュールはバンプアロケータ `__ronto_alloc(size)` もエクスポートします。これは引数のバイト列を書き込むためのスクラッチ領域のオフセットを返します。

```js
const { instance } = await WebAssembly.instantiate(bytes, { wasi_snapshot_preview1: stubs });
const ex = instance.exports, mem = ex.memory;
const b = new TextEncoder().encode('("a" "b" "c")');
const ptr = ex.__ronto_alloc(b.length);
new Uint8Array(mem.buffer, ptr, b.length).set(b);
const [rptr, rlen] = ex.rev(ptr, b.length);          // (rontolisp:wasm-export 'rev :params '(:s-expr) :returns :s-expr)
new TextDecoder().decode(new Uint8Array(mem.buffer, rptr, rlen)); // => ("c" "b" "a")
```

制限:

- このディレクティブは Preview 1 コアモジュールにのみ適用されます。`--component` のもとでは no-op です（ラッパーは出力されません）。インタプリタおよび JVM バックエンドでも no-op です（指定されたシンボルを返すだけです）。そのため、同じソースがすべてのバックエンドで動作します。
- エクスポートできるのはトップレベルの `defun` のみで、宣言されたパラメータ数はそのアリティと一致しなければならず、関数値を受け取ったり返したりする関数は対象外です。
- エクスポート名は裸の Lisp 名（`fact`）です。引数の書き方はホストに依存します（`wasmtime --invoke fact module.wasm 5`、`instance.exports.fact(5)` など）。
- デフォルトでは、モジュールのインスタンス化には依然として 8 つの `wasi_snapshot_preview1` インポートを満たす必要があります。`wasmtime run` はそれらを自動的に提供し、ブラウザホストは純粋計算関数に対して no-op スタブを供給できます。それらを除去するには `--no-wasi`（[後述](#no-wasi-reactor-mode)）を追加します。

## No-WASI（リアクター）モード

`--no-wasi` を追加すると、WASI 関数を **一切** インポートしない Preview 1 モジュールが出力されるため、ホストはインポートオブジェクトをまったく与えずにインスタンス化できます。これは、唯一の接点がエクスポートされた Lisp 関数である「リアクター」/ライブラリモジュールです。

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

リアクターは JavaScript からも同様に簡単に駆動できます。**インポートオブジェクトがない** ため、ホスト側は「インスタンス化してからエクスポートを呼び出す」だけです（`WebAssembly.instantiate(bytes).then(({ instance }) => instance.exports.fact(5))`）。コピー＆ペーストして実行できる完全な Node + ブラウザの例は、このページ末尾の [付録](#appendix-calling-a-module-from-javascript) にあります。

8 つの WASI インポートスロットは内部のトラップスタブで埋められるため、すべての関数インデックスは固定されたままです（その他のコード生成の変更はありません）。このモードは **純粋計算** のエクスポート専用です。あらゆる I/O（`print`/`read`/`open`/`getenv`/時刻/`random`、出力を行うトップレベルフォームを含む）はスタブに到達して **トラップ** します。これは Preview 1 専用です。`--no-wasi` は `--component` のもとでは無視されます。

このモジュールは（WASI コマンドではなく）リアクターであるため、そのトップレベル初期化処理は `_start` ではなく **`_initialize`** としてエクスポートされます。ホストはインスタンス化後に一度 `_initialize` を呼び出して、トップレベルフォーム（エクスポートされた関数が読み取る `defvar`/`defparameter`/`setq` のグローバル）を実行すべきです。トップレベルの状態を保持しない純粋計算リアクターでは省略できます。

## 最適化（ツリーシェイキング）

デフォルトでは、関数インデックスが固定されているため、コンパイルされたモジュールはプログラムが実際に使用するものに関係なく **ランタイム全体**（プリンター、有理数、文字列、リーダーおよび `eval` ヘルパー、WASI インポートスロットなど）を埋め込みます。`--optimize` を追加すると、モジュールのルート（そのエクスポートと `_start`/`_initialize` エントリ）から到達できないすべての関数を除去し、残った関数を再番号付けします。未使用の WASI インポートも削除されるため、純粋計算リアクターモジュールはわずかな数の関数にまで縮小します。

```bash
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~1 KB module
```

上記の `fact` の例では、モジュールは約 26 KB から約 1.3 KB に縮小します。`--optimize` はオプトインであり、動作を保存します。実際の `call` 命令からコールグラフを辿るため、到達可能なものはすべて（組み込みの `eval`/`load` がディスパッチするコードを含めて）保持されます。`--component` のもとでは **効果がありません**（WASI 0.3 アダプターはコアの固定されたインポート/インデックスレイアウトに依存しています）。同じフラグは [JVM 出力](jvm.md)のデッドコード除去も行います。

## 非 GC 出力（`--no-gc`）

デフォルトの出力は、上記の最適化されたリアクターであっても、依然として **wasm-GC 対応** のランタイムを必要とします。なぜなら、すべての値が GC ヒープ型（`i31ref`、float 構造体、`(ref eq)`）だからです。代わりに素の **MVP** モジュールを出力するには `--no-gc` を追加します。rec グループなし、`struct`/`array`/`i31` 型なし、`eqref` なし、インポートなしです（プログラムが文字列を使用する場合にのみ素のリニアメモリが追加されます。[後述](#strings)を参照）。これはインポートオブジェクトなしでインスタンス化でき、**`-W gc` なし** で任意の MVP クラスのランタイムで動作します。

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, no -W gc needed
```

これは、各値をボックス化されていない wasm スカラーへ直接ローワリングし、文字列には小さなリニアメモリ表現を加えることで実現しています。したがって、対象となるサブセットは別の言語ではなく、この言語の制限版です。

### 対象となるサブセット

関数が対象となるのは、その **推移的なコールグラフ全体** がこのサブセット内に収まる場合だけです。

- 数値と真偽値: 算術演算（`+ - * / mod rem 1+ 1- abs min max sqrt`）、整数ビット演算子（`logand logior logxor lognot ash`）、比較と述語（`= < <= > >= not zerop plusp minusp evenp oddp`）。
- 制御と束縛: `if`/`when`/`unless`/`cond`/`progn`/`let`/`let*`、再帰、および他の対象関数への呼び出し。
- 反復とローカルの変更: `dotimes`/`do`/`do*` とその基盤となる `while`/`setq`/`return`、および自由に再代入される let/`do` で束縛された変数。`loop` は cons を生成しない節（数値 `for`、`sum`/`count`/`maximize`/`minimize`、`repeat`/`while`/`until`/`do`/`return`）に限り対象です。`collect`/`append`/`nconc` や `for ... in`/`on` の節はリストを割り当てるため対象外です。
- 浮動小数点/整数の変換: `float truncate floor ceiling round`。
- 文字列: 文字列リテラルと `(concatenate 'string ...)`。

ヒープオブジェクトを割り当てるその他のもの（cons/リスト、文字、シンボル、ベクター、ハッシュテーブル、`eval`/`apply`、I/O、`dolist`/リスト反復、自由変数やグローバルへの代入）は、その関数を対象外にします。黙ってミスコンパイルするのではなく、これは違反した演算を名指しする **コンパイルエラー** になるため、境界は明示的なままです。

サポートされる境界指定子は `:int`、`:float`、`:bool`、`:string`（および `:void`/省略）です。`:s-expr` は **サポートされません** — それには、このバックエンドが意図的に省略している cons/リーダー/プリンターのランタイムが必要だからです。

### 数値モデル

各値の wasm 型は静的型推論によって選択されます。整数は `i64` を、浮動小数点数は `f64` を使用します。型はエクスポート境界指定子を起点として、コールグラフ上の不動点計算で推論され、整数と浮動小数点が出会う箇所（例えば `(* 3.14 n)`）では整数が `f64` に昇格されます。`i64` を使用することで整数演算は 2^63 まで厳密になります。これは GC バックエンドの `i31` の fixnum と、全 `f64` ローワリング（厳密なのは 2^53 まで）が提供できるものの両方よりはるかに広い範囲です。例えば `a*a - (a-1)*(a+1)` は、中間結果が 2^53 を超えても正確に `1` のままです。

推論は自動的に拡張（widen）もします。let/`do` で束縛された変数は、その初期化子とそれに代入されるすべての値の join を取るため、浮動小数点と合計される整数アキュムレータは `f64` になります。

```lisp
(defun sum-squares (n)        ; sum of i*i for i in 0..n-1, as a float
  (let ((acc 0))              ; acc starts as an integer 0 ...
    (dotimes (i n)
      (setq acc (+ acc (* (float i) (float i)))))  ; ... and widens to f64 here
    acc))
(sum-squares 5)  ; => 30.0
```

`--no-gc` のもとでは、これにより `acc`（および戻り値）は `f64` と推論される一方、ループカウンタ `i` は `i64` のままになります。

有理数型は存在しないため、完全な Common Lisp および GC バックエンドとは 2 つの点で異なります。`/` は浮動小数点除算であり（`1/3` のような比は生成されません）、真偽値コンテキストにおいて値が偽となるのはちょうどそれがゼロのときです（Common Lisp は `nil` のみを偽として扱います）。**境界** 指定子はホスト幅のままです。`:int`/`:bool` は（GC バックエンドと同様）32 ビットの `i32` として渡されるため、32 ビット範囲を超える戻り値はラップします。広い `i64` の範囲は内部計算にのみ適用されます。このモードが対象とする数値カーネル（階乗、数学/金融関数、バリデータ）では、結果はインタプリタおよび GC バックエンドと一致します。

### 文字列

文字列はリニアメモリ内の `[length][bytes]` ヘッダーへの `i32` ポインタであり、`(concatenate 'string ...)` は新しいバッファをバンプアロケートします。そのため、文字列を組み立てるのは単なるアキュムレータループです。

```lisp
(defun stars (n)               ; an n-character run of '*'
  (let ((out ""))
    (dotimes (k n)
      (setq out (concatenate 'string out "*")))
    out))
(stars 5)  ; => "*****"
```

文字列を使用するモジュールは（拡張可能な）リニアメモリを持ち、その `memory` と `__ronto_alloc(size)` バンプアロケータを関数とともにエクスポートします。`:string` パラメータはホストがメモリに書き込む `(ptr, len)` ペアとして渡され、`:string` の結果も同じ方法で返されます。そのため、文字列を返すエクスポートは、`wasmtime --invoke` だけではなく、エクスポートされたメモリを読み書きできるホスト（JavaScript、小さな Node スクリプト、ブラウザのプレイグラウンド）を必要とします。[付録](#passing-strings-string) で JS 側を詳しく説明します。

これにより、ASCII アートのマンデルブロレンダラを wasm-GC なしで実行できます。[`examples/mandelbrot-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/mandelbrot-nogc.lisp) は浮動小数点のエスケープタイムループを維持しつつ、描画したグリッドを出力する代わりに 1 つの文字列として返します。

```console
$ rontolisp examples/mandelbrot-nogc.lisp --no-gc --optimize -o mandelbrot.wasm
$ node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("mandelbrot.wasm"), {})).instance.exports;
  const [p, n] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
  process.stdout.write(Buffer.from(new Uint8Array(ex.memory.buffer, p, n)).toString());
})()'
```

### 組み合わせ

`--no-gc` は純粋計算リアクターです。`--no-wasi` と同様に何もインポートせず、各 `rontolisp:wasm-export` 関数をその名前でエクスポートします（そのため I/O はできず、出力を行うトップレベルフォームは拒否されます）。`--optimize` と組み合わせられますが、`--component` とは組み合わせられません（そのパスは wasm-GC に依存します）。JavaScript からの呼び出しは GC リアクターと同じ「インスタンス化してからエクスポートを呼び出す」です（[付録](#appendix-calling-a-module-from-javascript) を参照）。ただしここではモジュールは wasm-GC サポートを必要とせず **任意の** WebAssembly エンジンで動作します。

## WASI 0.3 コンポーネント

`--component` を追加すると、Preview 1 コアモジュールの代わりに WASI 0.3（Preview 3）**コンポーネント** が出力されます。コンポーネントは `wasi:cli/stdout@0.3.0` を通じて出力します。

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y hello.wasm
```

```
3
```

WASI 0.3 では、すべてのバイト I/O が組み込みのコンポーネントモデルの `stream<u8>` / `future<T>` 型と非同期正準 ABI を通じて流れます。rontolisp は同じ Preview 1 コアモジュールを変更せずに保持し（依然として 8 つの `wasi_snapshot_preview1` 関数をインポートします）、**アダプター** コアモジュールが `stream.new`/`stream.read`/`stream.write` と `future.read` を使ってそれらを WASI 0.3（`wasi:cli`、`wasi:filesystem`、`wasi:clocks`、`wasi:random`）の上に実装します。コンポーネントの `wasi:cli/run@0.3.0` エクスポート（`async func`）は **スタックフル** な非同期エクスポートとしてリフトされるため、同期的な stream/future の組み込みが協調的にブロックし、アダプターは直線的なコードのままになります。3 つの `component-model-async*` フラグがこれらの機能（スタックフル非同期リフト + 同期 stream/future 組み込み）を有効にします。

wasmtime の起動コマンドは出力の種類を **選択しません**。`wasmtime run` は wasmtime のデフォルトサブコマンドで、コアモジュールとコンポーネントを自動検出するため、`wasmtime run -W gc` は前のセクションの Preview 1 の `hello.wasm` も同様に実行します。Preview 1 コアモジュールと WASI 0.3 コンポーネントのどちらが生成されるかを決めるのは、コンパイル時の `--component` フラグだけです。（実際の違いは、コンポーネントは実行できるが Preview 1 コアモジュールは実行できないコンポーネント専用ランタイムで現れます。）

デフォルトの出力（`--component` なし）は Preview 1 コアモジュールのままなので、既存の使い方には何も変化はありません。

ファイル I/O はコンポーネントモードでも動作します。これは `wasi:filesystem@0.3.0`（`read-via-stream` / `append-via-stream`、`stream`/`future` を通じて駆動）の上に実装されています。Preview 1 と同様に、ファイルアクセスには `--dir` が必要です。

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . fileio.wasm
# "hello"
```

コンポーネントモードの注意点と現在の制限:

- WASI 0.3 のコンポーネントモデル非同期サポートを備えたランタイムが必要です。**wasmtime 46 以降**（`-W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y` を渡します）。
- `print`/標準出力、標準入力（`wasi:cli/stdin@0.3.0` を介した `read`、引数 0 個の `read-line`）、およびファイル I/O（`open`、`close`、`write-line`、ストリーム `read-line`、`load`、`with-open-file`）はすべて動作します。ファイルアクセスには `--dir` が必要です（パスは最初の preopen ディレクトリに対して解決されます）。
- `random` は `wasi:random@0.3.0` から実際のエントロピーを取得します（Preview 1 はホストの `random_get` を使用します）。そのため `(random N)` は実行ごとに異なります。`get-universal-time` / `get-internal-real-time` / `get-internal-run-time` は `wasi:clocks@0.3.0`（`system-clock`/`monotonic-clock`）を読み取り、`getenv` は `wasi:cli/environment@0.3.0` を読み取ります。
- 送信 HTTP（`rontolisp:fetch`）はコンポーネントモードで動作しますが、**ハイブリッド** です。基盤の I/O は WASI 0.3 のままですが、fetch 自体は `wasi:http@0.2` + `wasi:io@0.2` をインポートします（非同期の `wasi:http@0.3` はまだ上流に存在しません。`.todo/02-upgrade-fetch-to-wasi-http-0.3.md` を参照）。fetch コンポーネントは非同期フラグに加えて `-S http=y` を付けて実行します。fetch を使わないコンポーネントは `wasi:http` をインポートしないため、`-S http` は不要です。
- それ以外の点では、コンパイルされた Lisp はサポートされる機能について Preview 1 の出力と同一に動作します。

## 付録: JavaScript からのモジュール呼び出し

リアクターモジュール（`--no-wasi` または `--no-gc`）は何もインポートしないため、ホスト側はすべて「インスタンス化してからエクスポートを呼び出す」だけです。そしてそれは Node とブラウザで同じコードです。以下は、最初から最後までコピー＆ペーストできる完全な例です。3 つのエクスポートからなる小さなキットから始めます。

```lisp
;; mathkit.lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
(defun area (r) (* 3.141592653589793 r r))
(defun in-range (x lo hi) (if (< x lo) nil (if (> x hi) nil t)))
(rontolisp:wasm-export 'fact     :params '(:int)           :returns :int)
(rontolisp:wasm-export 'area     :params '(:float)         :returns :float)
(rontolisp:wasm-export 'in-range :params '(:int :int :int) :returns :bool)
```

`--no-gc`（任意のエンジンで動作）と `--optimize`（エクスポートから到達できないものをすべて除去 — ここではモジュール全体が約 200 バイト）でコンパイルします。

```bash
rontolisp mathkit.lisp --no-gc --optimize -o mathkit.wasm
```

Node 18 以降では、これを `run.mjs` として保存し、`node run.mjs` を実行します。

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

ブラウザはバイトの読み込み方が異なるだけです。`instantiateStreaming` は `fetch` を直接受け取ります。そのため、ページ全体は次のようになります。

```html
<!doctype html>
<script type="module">
  const { instance } = await WebAssembly.instantiateStreaming(fetch('./mathkit.wasm'));
  const ex = instance.exports;
  document.body.textContent = `fact(10) = ${ex.fact(10)}, area(2) = ${ex.area(2)}`;
</script>
```

知っておくと役立つ境界の詳細をいくつか挙げます。

- `in-range` のようなハイフン付きの Lisp 名は有効な JavaScript 識別子ではないため、ブラケットアクセスで参照します。`ex['in-range'](...)`。
- `:int`/`:float` は素の JS 数値として渡されます。`:bool` は `i32`（`0`/`1`）として渡されるため、本物の JS の真偽値にするには `Boolean(...)` でラップします。
- **`--no-gc`** モジュールは **任意の** WebAssembly エンジンで動作します。GC の **`--no-wasi`** モジュールは wasm-GC 対応のもの（Node 22 以降、現行のブラウザ）が必要です。上記の JavaScript は両方でバイト単位まで同一です。コンパイルフラグを入れ替えるだけで、他には何も変わりません。

### 文字列の受け渡し（`:string`）

上記のスカラーの例は、`:int`/`:float`/`:bool` が素の数値として境界を越えるため、メモリを必要としません。一方 `:string` は、モジュールがエクスポートする `memory` を通じて `(ptr, len)` ペアを受け渡します。ホストは引数のバイト列を（エクスポートされた `__ronto_alloc(size)` バンプアロケータが予約したオフセットに）メモリへ書き込み、`(ptr, len)` を渡し、その後エクスポートが返す `(ptr, len)` をデコードします。

`:string` は `--no-gc` のもとで動作するため、関数が非 GC の文字列サブセット（文字列リテラルと `(concatenate 'string ...)`）に収まっている限り、モジュールは依然として **任意の** エンジンで動作します。プロトコルを示すには挨拶文ビルダーで十分です。

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

より高機能な文字列関数（`string-upcase`、`subseq`、`string=` など）は非 GC サブセットの外にあります。それらを使うには代わりに wasm-GC バックエンド（`--no-wasi`）向けにコンパイルすることになります。境界プロトコルは同一で、エンジンが wasm-GC 対応でなければならないだけです。以下の `:s-expr` の例がそのパスを示します。

### リストの受け渡し（`:s-expr`）

`:s-expr` は **任意の** Lisp 値を S 式の *テキスト* として運びます。モジュールは組み込みのリーダーで入力を解析し、結果を同じ `(ptr, len)` / `__ronto_alloc` プロトコルで返します。そのリーダー/プリンター/cons の機構は **wasm-GC 専用** であるため、`:s-expr`（および上記のより高機能な文字列関数）には `--no-wasi` と wasm-GC 対応のエンジン（Node 22 以降、現行のブラウザ）が必要です。

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

ブラウザでは読み込みの行だけが変わります（`WebAssembly.instantiateStreaming(fetch(...))`）。`write`/`read`/`memory`/`__ronto_alloc` のロジックは同一です。多値 `(ptr, len)` を返す関数は JS では 2 要素の配列として現れるため、`read(...ex.shout(...))` のようになります。
