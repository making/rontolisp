# WASM 非 GC 出力(`--no-gc`)

GC 値モデルの出力は — 最適化されたリアクターであっても — すべての値が GC ヒープ型(`i31ref`、float 構造体、`(ref eq)`)であるため、依然として **wasm-GC 対応**ランタイムを必要とします。`--no-gc` を追加すると、代わりに素の **MVP** モジュールが出力されます: rec グループなし、`struct`/`array`/`i31` 型なし、`eqref` なし、インポートなしです(素のリニアメモリはプログラムが文字列を使うときのみ追加され — [後述](#strings) — 単一の `fd_write` インポートは[印字](#printing-print--princ--terpri)するときのみ追加されます)。印字しないモジュールはインポートオブジェクトなしでインスタンス化でき、**`-W gc` なし**で任意の MVP クラスのランタイムで動作します:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, ~76 bytes, no -W gc needed
```

これは、各値をアンボックスな wasm スカラーへ直接ローワリングし、文字列には小さなリニアメモリ表現を加えることで達成されます — そのため対象サブセットは言語の制限であって、別の言語ではありません。プログラムの形も制限されます: トップレベルには `defun` と `rontolisp:wasm-export` ディレクティブ**のみ**を置けます(純粋計算リアクターであり、`_start` はありません)。境界指定子は `:int`、`:long`、`:float`、`:bool`、`:string`(および `:void`/省略)です。`:s-expr` は**非対応**です — このバックエンドが意図的に省いている cons/リーダー/プリンターのランタイムを必要とするためです。

数値ベクトルカーネル([`vec:` パッケージ](simd-acceleration.md))も `--no-gc` で動作し、デフォルトでは素のスカラーループへローワリングされます — そのためベクトルプログラムも上記の「任意の MVP ランタイムで動く」性質を保ちます。[`--simd`](../compiling/wasm.md#simd-acceleration---simd) を追加すると、それらのカーネルはネイティブの WebAssembly SIMD(`v128`)へローワリングされ、SIMD プロポーザル対応のランタイム(wasmtime ではデフォルト有効)が必要になります。

## 対象となるサブセット

関数が対象となるのは、その**推移的な呼び出しグラフ全体**が次のサブセットに収まる場合だけです:

- 数値とブール: 算術(`+ - * / mod rem 1+ 1- abs min max sqrt`)、整数ビット演算(`logand logior logxor lognot ash`)、比較と述語(`= < <= > >= not zerop plusp minusp evenp oddp`);
- 制御と束縛: `if`/`when`/`unless`/`cond`/`progn`/`let`/`let*`、再帰、他の対象関数の呼び出し;
- 反復とローカルな変更: `dotimes`/`do`/`do*` とその基盤の `while`/`setq`/`return`。let/`do` 束縛変数は自由に再代入できます。`loop` は非 cons 化節(数値 `for`、`sum`/`count`/`maximize`/`minimize`、`repeat`/`while`/`until`/`do`/`return`)に限り対象です — `collect`/`append`/`nconc` と `for ... in`/`on` の節はリストを確保するため対象外です;
- 浮動小数点/整数変換: `float truncate floor ceiling round`;
- 文字列と文字: 文字列リテラル、文字リテラル、`(concatenate 'string ...)`、`length`、`subseq`、`string=`、`char`、`char-code`/`code-char`、`char=`、および(整数・浮動小数点数・文字列の)`princ-to-string`。独立した文字型はありません: 文字はそのコードポイントで表現されるため、移植性のあるイディオム `(char= (char s i) #\x)` と `(char-code (char s i))` は他のバックエンドとまったく同じように振る舞い、素の `(char s i)` が `:int` 境界を越えるとコードが見えます;
- 印字: `print`、`princ`、`terpri`(省略可能なストリーム引数なし) — [後述](#printing-print--princ--terpri)を参照;
- メモリ回収: [`rontolisp:with-arena`](#reclaiming-from-lisp-rontolispwith-arena)。

それ以外のヒープオブジェクトを確保するもの(cons/リスト、シンボル、ベクタ、ハッシュテーブル、`eval`/`apply`、I/O、`dolist`/リスト反復、自由変数やグローバルへの代入、`&optional`/`&rest`/`&key` などのラムダリストキーワード — rest リストは cons です)は関数を対象外にします。黙って誤コンパイルするのではなく、問題の操作を名指しする**コンパイルエラー**になるため、境界は明示的なままです。

## 数値モデル

各値の wasm 型は静的型推論で選ばれます: 整数は `i64`、浮動小数点数は `f64` を使います。型はエクスポートの境界指定子を種として呼び出しグラフ上の不動点で推論され、整数と浮動小数点数が出会う場所(例えば `(* 3.14 n)`)では整数が `f64` へ昇格します。`i64` を使うことで整数演算は 2^63 まで正確です (GC バックエンドはさらに先へ進み、任意の大きさの多倍長整数へ昇格します) — 全 `f64` ローワリング(2^53 までしか正確でない)よりもはるかに広く、例えば `a*a - (a-1)*(a+1)` は中間値が 2^53 を超えても正確に `1` のままです。

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

有理数型はないため、完全な Common Lisp とも GC バックエンドとも異なる点が 2 つあります: `/` は浮動小数点除算であり(`1/3` の比はありません)、ブール文脈で偽になるのは値がちょうどゼロのときです(Common Lisp は `nil` だけを偽として扱います)。**境界**指定子はホスト幅のままです — `:int`/`:bool` は(GC バックエンドと同様)32 ビットの `i32` として渡るため、32 ビット範囲外の戻り値はラップします。広い `i64` 範囲は内部計算にのみ適用されます。パラメータや結果が 32 ビット範囲を超えうるときは `:long` を宣言してください — `wrap`/`extend` なしの `i64` として境界を渡ります。このモードが対象とする数値カーネル(階乗、数学/金融関数、バリデータ)については、結果はインタプリタおよび GC バックエンドと一致します。

## 文字列

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

文字列を使用するモジュールは(拡張可能な)リニアメモリを持ち、その `memory` と `__ronto_alloc(size)` バンプアロケータを関数とともにエクスポートします。`:string` パラメータはホストがメモリに書き込む `(ptr, len)` ペアとして渡され、`:string` の結果も同じ方法で返されます — そのため文字列を返すエクスポートは、`wasmtime --invoke` だけではなく、エクスポートされたメモリを読み書きできるホスト(JavaScript、小さな Node スクリプト、ブラウザのプレイグラウンド)を必要とします。[ブラウザガイド](wasm-browser.md#passing-strings-string)で JS 側を詳しく説明し、[`--no-gc --component`](#compact-component-output---no-gc---component) はこの手動プロトコルを丸ごと不要にします。

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

エクスポートの型は、この Lisp ファイルにはまったく書かれていません: チェックインされた world([`mandelbrot_component.wit`](https://github.com/making/rontolisp/blob/develop/examples/console/mandelbrot_component.wit))が `export mandelbrot: func(x0: f64, ..., max-iter: s32) -> string;` を宣言し、[`rontolisp:wit-export`](wit-contracts.md#implementing-a-wit-world-wit-export) が「このプログラムはそれを実装する」と言います。1 つのディレクティブが両方のビルドに効きます: 上のコアモジュールは、それが置き換えた手書きの `wasm-export` とバイト単位で同一であり、同じソースがコンポーネントとしてもコンパイルされ、そちらでは `wasmtime run --invoke 'mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30)'` がホスト側のメモリ操作コードもランタイムフラグもなしに文字列を返します。

## 印字(`print` / `princ` / `terpri`)

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

## メモリの回収(アリーナ API)

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

wasm-GC バックエンドも同じ `__ronto_alloc_mark`/`__ronto_alloc_reset` の対を、同じホスト側レシピでエクスポートします([wasm-GC のアリーナ API](wasm-gc-module.md#reclaiming-the-hosts-buffer-the-arena-api)を参照)。ただしそちらで回収が必要なのは*ホストの*バッファだけです — Lisp 側が確保したものはエンジンが面倒を見ます。スカラー戻り値の自動リセットは `--no-gc` 専用です: 非 GC サブセットには cons もクロージャもハッシュテーブルもグローバル `setq` もなく、呼び出しが確保したものが呼び出しより長生きし得ないからこそ健全なのです。

## Lisp からの回収(`rontolisp:with-arena`)

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

## コンパクトなコンポーネント出力(`--no-gc --component`)

`--component` を追加すると、同じ MVP コアモジュールが **WASM コンポーネント**としてラップされ、エクスポートは正準 ABI を通じて WAVE 構文で呼び出せる型付きコンポーネントモデルエクスポートになります。印字しないコアモジュールはインポートゼロなので、ラップに WASI アダプタも共有メモリモジュールも wasm-GC も不要です — 小さなプログラムならコンポーネント全体が数百バイトに収まり、**wasmtime のフラグを一切必要とせず**動作します:

```bash
rontolisp fact.lisp --no-gc --component -o fact.wasm
wasmtime run --invoke 'fact(5)' fact.wasm
# 120
```

型付き WIT シグネチャは各指定子をそれ自身の WIT 名で運びます(`:s32` → `s32`、`:u32` → `u32`、…、このバックエンドだけがリフトできる `:s64`/`:u64` まで。`:int` と `:long` は `:s32`/`:s64` の従来からの別名です)。加えて `:float` → `f64`、`:bool` → `bool`、`:string` → `string` で、`:returns` 省略は結果なしです。コンポーネントは jco でもトランスパイルでき(`jco transpile`、64 ビット型は JavaScript の BigInt として現れます)、wasm-GC サポートなしで任意のコンポーネントモデルホスト上で動作します。

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

[印字](#printing-print--princ--terpri)もここで動作します: 印字するプログラムには組み込みの **print マイクロアダプタ** — コアの単一の `fd_write` インポートを WASI 0.3(`wasi:cli/stdout` の `write-via-stream` と非同期の stream/future 組み込み)の上に実装する 3 つの小さな固定コアモジュール — が、プログラムが印字するときだけ配線されます。WASI 0.3 に同期書き込みは存在しないため、印字するプログラムのエクスポートは **async リフト**になります(WIT world では `async func` と表示されます)— それでもフラグゼロという性質は維持されます: 使うのはすべて base component-model async で、wasmtime 46+ ではデフォルトで有効です(これが*印字する*コンポーネントの wasmtime 下限になります。印字しないコンポーネントはインポートを一切持たず、より古いホストでも動きます)。印字出力はインタプリタとバイト単位で一致します — 先の `show.lisp` を使うと:

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
- コンポーネントは純粋なリアクターです: `wasi:cli/run` エントリはありません(トップレベルでは何も実行されません)。エクスポート内の印字は上記のマイクロアダプタで動作します。それ以外の I/O は通常どおり `--no-gc` サブセットの外です。`:async t` は拒否されます — 印字するプログラムのエクスポートは自動的に async リフトされ、それ以外にエクスポートがサスペンドし得るものは存在しません。
- エクスポート名は lower-kebab-case のコンポーネントモデル名でなければなりません。その文法から外れる Lisp 名については、コンパイラが `:as` での改名を求めます。
- `--optimize` は組み合わせられます: コアモジュールはラップの前にツリーシェイキングされます。
- [`--emit-wit`](wit-contracts.md#emitting-the-wit-world---emit-wit) も組み合わせられ、型付きエクスポートだけの小さなインポートなし world(プログラムが印字するときは `wasi:cli/stdout@0.3.0` インポートと `async func` のエクスポート署名付き)を書き出します。
