# ブラウザでの WASM 実行

rontolisp の WASM ビルドをブラウザへ届ける経路は 2 つあります:

- **`jco transpile` によるコンポーネント** — コンポーネントを、エクスポートがそのまま JavaScript 関数になる普通の JavaScript モジュールに変換します。
- **リアクターモジュールを手書きで呼ぶ** — `--no-wasi`(wasm-GC、言語フル機能)であれ `--no-gc`(スカラーのみ)であれ、コアモジュールはどちらもインポートを持たないので、`WebAssembly.instantiate` + `instance.exports` がホスト側のすべてです。しかも両バックエンドでバイト単位まで同じ JavaScript になります。Node とブラウザで同じコードです。

## ブラウザでコンポーネントを実行する(jco)

コンポーネントは wasmtime 専用の成果物ではありません。`jco transpile` はコンポーネントを JavaScript に変換し、その結果はブラウザで動作します — エクスポートはただの JavaScript 関数になります。

この節を通して使う例は `count-vowels` です。文字列を受け取り、その中の母音の個数を返す関数を 1 つエクスポートするだけのプログラムです。

```lisp
;; count-vowels.lisp
(defun vowelp (c)
  (or (char= c #\a) (char= c #\e) (char= c #\i) (char= c #\o) (char= c #\u)
      (char= c #\A) (char= c #\E) (char= c #\I) (char= c #\O) (char= c #\U)))

(defun count-vowels (s)
  (let ((n 0))
    (dotimes (i (length s))
      (when (vowelp (char s i))
        (setq n (+ n 1))))
    n))

(rontolisp:wasm-export 'count-vowels :params '(:string) :returns :int)

(count-vowels "Hello, World!")   ; => 3
```

純粋な計算だけ(cons も I/O もなし)なので [`--no-gc` のサブセット](wasm-nogc.md#eligible-subset)に収まり、インポートを 1 つも持たないコンポーネントにコンパイルされます。jco はコンポーネントモデルのエクスポート名を camelCase 化するため、`count-vowels` は `countVowels` として現れます。(jco 1.25.2 + Chrome 149 で確認。)同じプログラムを Node ホストと Java ホストから駆動し、エクスポートを `wasm-export` ではなく WIT で宣言したものが [`examples/count-vowels`](https://github.com/making/rontolisp/tree/develop/examples/count-vowels) です。

**`--no-gc --component` は何も必要としません。** その world はインポートを持たないため、jco は自己完結した単一の ES モジュール(コア WASM が base64 で内部に埋め込まれ、`count-vowels` の例で約 90 KB)を、それ自身の `import` 文なしで出力します。ページ側が供給するものは何もありません — シムも、import map も、ポリフィルも不要です:

```bash
rontolisp count-vowels.lisp --no-gc --component -o cv.wasm
npx @bytecodealliance/jco transpile cv.wasm -o dist
```

```html
<script type="module">
  const { countVowels } = await import('./dist/cv.js');
  console.log(countVowels('Hello, World!'));  // 3
</script>
```

**印字する `--no-gc --component` はまだ jco では実行できません。** その[印字マイクロアダプタ](wasm-nogc.md#compact-component-output---no-gc---component)は `wasi:cli/stdout@0.3.0` をインポートし、すべてのエクスポートを async リフトするため、下記の GC コンポーネントと同じ jco のギャップ(jco は async リフトされたエクスポートを呼び出せず、`future` ランタイムも未完成)に当たります — そして WASI 0.3 シムはそもそも Node 専用です。コンポーネントの行き先が jco やブラウザであれば、プログラムを印字なしに保ってください。手書きのインポートオブジェクトを使う[素のモジュールパス](#reactor-modules-by-hand)は影響を受けません。

**wasm-GC の `--component` はロードされ計算もできますが、まだ印字はできません。** Chrome は wasm-GC、JSPI、正準 ABI のいずれにも対応しており、コンポーネントの同期エクスポートは正しい値を返します。残りを阻んでいるのは 2 つのギャップで、どちらも JavaScript 側にあります(wasmtime はすべて実行できます):

- 必要となる WASI 0.3 インポートにブラウザ実装がありません: `@bytecodealliance/preview3-shim` はパッケージの `exports` に `node` 条件しか宣言しておらず、`node:worker_threads`、`node:net`、`node:http` などを取り込みます。したがってページは、jco がモジュール先頭で分割代入する 9 つのメンバー — `environment.getEnvironment`、`stdout.writeViaStream`、`stderr.writeViaStream`、`stdin.readViaStream`、`monotonicClock.now`、`systemClock.now`、`preopens.getDirectories`、`types.Descriptor`、`random.getRandomU64` — の代役を手書きする必要があります。純粋計算のエクスポートであれば、これらは存在しさえすれば十分です。
- 印字はその先、jco 自身の生成コードの中で失敗します。生成コードは `FutureReadableEnd` / `FutureWritableEnd` / `FutureEnd` を*参照*しているのに、そのいずれも定義していません(`ReferenceError: FutureReadableEnd is not defined`)。この経路は `wasi:cli/stdout` の `write-via-stream` から到達します — その WIT の結果型が `future` だからです。これとは別に、jco は非同期エクスポート自体をまだ*呼び出せません*(こちらも 0.3 非同期 ABI のギャップです)。[`:async t`](wasm-component.md#component-model-function-exports-wasm-export) の I/O エクスポートがまさにそれです。

ここでは Node の方が弱いホストです: Node 22 には JSPI がなく(`WebAssembly.Suspending is not a constructor`)、トランスパイルされた GC コンポーネントをインスタンス化することすらできません。Chrome にはできます。

## リアクターモジュールを手書きで呼ぶ

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

任意のエンジンで動くように `--no-gc` でコンパイルします。エクスポートから到達不能なものは指定しなくてもすべて落ちるため、ここではモジュール全体が約 200 バイトになります:

```bash
rontolisp mathkit.lisp --no-gc -o mathkit.wasm
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

言葉だけでなく、実際に確かめます — 同じソースを `--no-wasi` で再コンパイルし、`run.mjs` はそのまま変更せずに実行します:

```bash
rontolisp mathkit.lisp --no-wasi -o mathkit.wasm
node run.mjs
```

```
3628800
12.566370614359172
true
false
```

上記のどれも `--no-gc` 固有の話ではありません: `mathkit.lisp` はそもそも非 GC サブセットから外れていないので、どちらのバックエンドでも問題なくコンパイルできる(数多くある)プログラムの一つに過ぎません。`cons`、`string-upcase`、ハッシュテーブル、`defstruct` など言語のフル機能を必要とするプログラムは、単純に `--no-wasi` を**必要とする**だけです — それはフォールバックでも劣った経路でもなく、wasm-GC 対応のエンジン(Node 22+、現行のあらゆるブラウザ)が動かすバックエンドというだけのことです。

### 文字列の受け渡し(`:string`)

上記のスカラーの例は、`:int`/`:float`/`:bool` が素の数値として境界を渡るため、メモリを必要としません。`:string` は代わりにモジュールのエクスポートする `memory` を通じて `(ptr, len)` ペアを渡します: ホストは(エクスポートされた `__ronto_alloc(size)` バンプアロケータで確保したオフセットに)引数のバイト列をメモリへ書き込み、`(ptr, len)` を渡し、エクスポートが返す `(ptr, len)` をデコードします。

`:string` は `--no-gc` のもとで動作するため、関数が非 GC の文字列サブセット([対象サブセット](wasm-nogc.md#eligible-subset)を参照)に収まっている限り、モジュールは依然として**任意の**エンジンで動作します。プロトコルを示すには挨拶文ビルダーで十分です:

```lisp
;; greetkit.lisp
(defun greet (name) (concatenate 'string "Hello, " name "!"))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greetkit.lisp --no-gc -o greetkit.wasm
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

[`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component) では、同じ `:string` エクスポートが型付きコンポーネントモデル `string` として境界を越えるようになり、上記のホスト側グルーコードはすべて不要になります(正準 ABI がコピーを行い、post-return 関数がヒープを平坦に保ちます)。

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
rontolisp textkit.lisp --no-wasi -o textkit.wasm
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
