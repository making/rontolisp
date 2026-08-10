# wasm-GC コアモジュール(デフォルト出力)

デフォルトの出力 — `-o file.wasm` 以外のフラグなし — は、wasm-GC 値モデル上の **WASI Preview 1 コアモジュール**です:

- **wasm-GC** — 整数は `i31ref` として表現されます (fixnum 範囲を超える値は符号付き 64 ビット構造体に、それも超える値はリム表現の多倍長整数にボックス化され、算術は任意の大きさで正確です)。浮動小数点数は `float_struct { f64 }` にボックス化されます。スタック上のすべての値は `(ref eq)` として型付けされます。これが言語全機能(cons セル、シンボル、クロージャ、ハッシュテーブル、`eval` など)を支えるものであり、モジュールが wasmtime 14+(`-W gc`)、Node 22+、現行ブラウザといった wasm-GC 対応ランタイムを必要とする理由です。
- **WASI Preview 1** — モジュールは 8 つの `wasi_snapshot_preview1` 関数(標準出力の `fd_write`、`random_get`、クロック、環境変数など)をインポートし、`_start` エントリポイントを公開するため、`wasmtime run` はプログラムのトップレベルをコマンドのように実行します。

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
# 3
```

エクスポートされた関数は**生のコア関数**です: スカラー(`:int`/`:float`/`:bool`)は素の数値として境界を渡るため、`wasmtime --invoke` や `instance.exports.fact(5)` が直接使えます。メモリ経由の `:string` と `:s-expr` はモジュールのエクスポートする `memory` を通じて `(ptr, len)` ペアを渡し、ホストが引数バイト列を書き込むための `__ronto_alloc(size)` バンプアロケータも併せてエクスポートされます — このプロトコルはメモリを読み書きできるホスト(JavaScript であって `wasmtime --invoke` ではない)を必要とし、[ブラウザガイドの「リアクターモジュールを手書きで呼ぶ」節](wasm-browser.md#リアクターモジュールを手書きで呼ぶ)で端から端まで解説します。モジュールのインスタンス化には依然として 8 つの WASI インポートを満たす必要があります。`wasmtime run` は自動で提供し、ブラウザホストは純粋計算関数に対して no-op スタブを供給できます。あるいは [`--no-wasi`](#no-wasi-reactor-mode) で丸ごと取り除けます。

この形状での `wasm-export` の全体像(運ばれる型、`:as` による改名、アリティ一致、void 戻り値)は、[ホスト境界ガイド](wasm-host-boundary.md)を参照してください。

## 値モデルの動作上の注意

wasm-GC 値モデルに関する 2 つの動作上の注意:

- **パラメータ数の上限。** 関数(`defun` または `lambda`)は最大 **7 つのパラメータ**しか取れません(インタプリタと JVM バックエンドにこの制限はありません)。上限を超えた固定アリティの `defun` は自動的にバンドルされます: コンパイラは最初の 6 パラメータを残し、残りをリストに詰め、すべての直接呼び出しサイトを一致するように書き換えます — そのため幅広いライブラリシグネチャもそのままコンパイルされます。そのような関数の値を `#'name`/`symbol-function` で取るのはコンパイルエラーです(バンドルされた形を知っているのは直接呼び出しだけです)。また、上限を超えた `lambda` や可変長関数は依然としてエラーになります — その場合は自分で引数をリストにまとめてください。可変長関数の rest リストは 1 パラメータと数えられるため、`&rest` 関数は最大 6 つの必須パラメータを宣言でき、直接呼び出しサイトでは任意個の引数を受け取れます。
- **浮動小数点数の印字の形。** WASM ではあらゆる大きさの浮動小数点数が印字できます: 整数部は 2⁶³ まで正確で、それを超える値は近似的な指数形式(`1.0E19`)にフォールバックし、`Infinity`、`-Infinity`、`NaN` は他のバックエンドと同様にその語で印字されます。形の違いが 1 つ残っています: 10⁷ から 2⁶³ までは、インタプリタと JVM が指数表記(`1.5E12`)を使うのに対し、WASM はすべての桁を印字します(`1500000000000.0`)。`rontolisp:json-stringify` もこの形の違いを引き継ぎます。

## ホストのバッファの回収(アリーナ API)

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

このブラケットは、[`count-vowels` の例](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)が `--no-gc` について Node と [Endive](https://endive.run)(Java)で示しているものと同一です — 境界のプロトコルはバックエンドで変わらず、変わるのは関数の中に何を書けるかだけです。[`--component`](wasm-component.md) ではアリーナ API はなく、囲むものもありません: 正準 ABI の `post-return` が引数の文字列を解放してくれます。

## No-WASI(リアクター)モード

`--no-wasi` を追加すると、WASI 関数を**一切**インポートしない Preview 1 モジュールが出力され、ホストはインポートオブジェクトなしでインスタンス化できます — エクスポートされた Lisp 関数だけを表面とする「リアクター」/ライブラリモジュールです:

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

リアクターは JavaScript からも同様に簡単に駆動できます: **インポートオブジェクトがない**ため、ホスト側は「インスタンス化してからエクスポートを呼び出す」だけです(`WebAssembly.instantiate(bytes).then(({ instance }) => instance.exports.fact(5))`)。コピー＆ペーストして実行できる完全な Node + ブラウザの例は、[ブラウザガイドのリアクターモジュールの節](wasm-browser.md#リアクターモジュールを手書きで呼ぶ)にあります。

WASI インポートスロットは内部のスタブで埋められるため、すべての関数インデックスは固定のままです(他のコード生成に変更はありません)。それらのスタブの振る舞いは 1 つのルールに従います: **答えがそのモジュールにとって真であるときスタブは答え、答えることが「本物と区別できない値の捏造」になるときは拒否する。** ただし**ホストが渡してきた値は捏造ではなく**、時計と乱数はそれで供給されます。リアクターには実際に出力先も環境変数もファイルも存在しないので、それらには答えます。一方、入力のバイトはリアクターだからといって真になるわけではないので、そちらには答えません。

| プログラムがすること | `--no-wasi` での結果 |
| --- | --- |
| `print`、`format t`、`*error-output*` への書き込み | **破棄**(シンク)。呼び出しは正常に返る |
| `(uiop:getenv "X")` | `nil` — 環境変数は空 |
| `probe-file`、`directory`、`load` | 何も見つからない(`nil`、または捕捉可能なエラー) |
| `with-open-file`、`open` | WASI を名指しする**捕捉可能なエラーをシグナル** |
| `(random n)`、`(random 1.0)` | 動く — 組み込みの生成器、または `--host-random` でホストの生成器 |
| `rontolisp:random-bytes` | `--host-random` で本物のエントロピーを供給しない限り**シグナル** |
| `get-universal-time` などの時計 | ホストが `__ronto_set_time` で設定した時刻。設定されるまでは**シグナル** |
| `(sleep n)` | **シグナル** — ここでは時間を経過させる手段がない |
| `read`、`read-line`、`read-char`(標準入力) | **トラップ** |

シグナルするものはすべて呼び出し時に signal するので、`ignore-errors` や `handler-case` で囲めばそのまま動き、ファイル読み込みや時計参照の分岐がデッドコードであるライブラリはコンパイル・実行できます。トラップするのは標準入力だけで、トラップは捕捉できません — `--no-wasi` モジュールが報告ではなく死ぬのはここだけです。

出力がシンクであることは、ロード時にログを出すライブラリをリアクターに quickload できる理由そのものです — 代替案はログ 1 行のためにインスタンスを落とすことでした。テキストが必要なら、エクスポートの戻り値として返してください。

選択の余地があるサービスは時計と乱数の 2 つです。どちらもモジュールが自分では作れない値だからです。コアモジュールはそれぞれにフックを 1
つずつエクスポートします — `__ronto_set_time`(Unix エポックからのナノ秒)と `__ronto_seed_random` —
これらを **`_initialize` の前に**呼ぶことが、ロード中にタイムスタンプを取ったり draw
したりするライブラリをそもそもロード可能にします。また `--host-random` は `random` をホストのインポートに向けます。シードを与えなければ生成器は同じ列を繰り返し、時刻を設定しなければ時計は
1970 年を報告するのではなくシグナルし、次に書き込むまで書き込んだ値を保持します(そのため `(sleep n)`
はここではシグナルします — 時間を経過させる手段がありません)。**リアクターコンポーネント**にはどちらのフックもありません:
トップレベルがインスタンス化時に走るので、ホストが先回りできる隙間が存在しないためです。JavaScript
を含む全体は[時計と乱数のガイド](clock-and-random.md)にあります。

`--component` と組み合わせると、同じ契約が**リアクターコンポーネント**を生成します — 何もインポートせず、トップレベルフォームをインスタンス化時に実行するコンポーネントです。[コンポーネントガイド](wasm-component.md#リアクターコンポーネント--component---no-wasi)を参照してください。

`--no-wasi` コンパイルはソースを `:rontolisp-reactor` フィーチャー有効で読みます。`clack:clackup ... :server :rontolisp` のプログラムがここで **serve する**リアクターになるのはこの仕組みです: ハンドラバックエンドがアプリケーションを保存し、コンパイラがホストがリクエストごとに呼ぶ `handle-request` エクスポートを合成します — [Clack ガイド](clack.md)の「ホストから呼ばれる場合」を参照してください。

モジュールはリアクター(WASI コマンドではない)なので、トップレベルの初期化子は `_start` ではなく **`_initialize`** としてエクスポートされます。ホストはインスタンス化後に一度 `_initialize` を呼んでトップレベルフォーム(エクスポートされた関数が読む `defvar`/`defparameter`/`setq` のグローバル)を実行すべきです。トップレベル状態を持たない純粋計算リアクターは省略できます。

### 実行する前にビルドが教えてくれること

**トップレベルフォーム**から到達する拒否だけは、ここまでのすべての例外です:
コンディションを捕捉する呼び出し元がなく、メッセージは出力シンクに消えるため、インスタンスは `_initialize`
の中で、誰も名指ししない素の `RuntimeError: unreachable`
で死にます。ロード経路がどの原始関数に到達しうるかはビルドが既に知っている事実なので、ビルドがそれを述べます — 原始関数ごとに 1 行、そこへ至った呼び出し連鎖つきで:

```console
$ rontolisp app.lisp --no-wasi -o app.wasm
.../session/state/cookie.lisp:25:12: warning: GET-UNIVERSAL-TIME is reachable from a top-level
form of this --no-wasi module (the top-level (DEFSTRUCT COOKIE-STATE)), so it can run while the
module LOADS -- where nothing catches it and the host sees only RuntimeError: unreachable. The
module imports no clock: its time is whatever the host writes through the exported
__ronto_set_time hook (nanoseconds since the Unix epoch), so call that BEFORE _initialize --
until something does, reading it signals
```

持っておく価値が最も高いのは時計の行です: ロード中に時計を読むプログラムは — 先に時刻を設定するホストの上でなら —
*ロードできる*ので、これは拒否ではなく**ホスト側の義務**であり、事前に教えられるのはビルドだけです。エントロピーも同じ読み方で、`--host-random`
を名指しします。

**エクスポート**からしか到達できない原始関数については何も言いません。それは呼び出し元が捕捉できる通常の呼び出し時コンディションだからです。到達可能性は静的ですが、**引数**に対して盲目ではありません:
呼び出しは各引数について呼び出し側が述べていること — `#'app`、リテラル、`(defvar *app* (make-instance 'ningle:app))` — を持ち回るので、その値が取り得ない型の
`typecase` 分岐はロードパス上にありません。`clack` プログラムがどれも `clackup` の `(clackup "app.lisp")`
ファイル分岐(リアクターが通ることはない分岐)について沈黙するのはこの仕組みです。呼び出し側が何も述べていなければ何も除外されず、行はそのまま出ます。`handler-case` や `ignore-errors`
で囲まれた拒否はそもそも報告されません。プログラムが既に対処しているからです。
