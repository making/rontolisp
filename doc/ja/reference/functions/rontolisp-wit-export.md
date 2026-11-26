# rontolisp:wit-export

`(rontolisp:wit-export "world.wit" :world name)`

プログラムが **WIT world を実装している**ことを宣言します。world の `export`
項目はコンパイル時にプログラムのトップレベル `defun` と照合され、それが表す
[`rontolisp:wasm-export`](rontolisp-wasm-export.md)
ディレクティブへとローワリングされます。したがって境界型を手で書くことはなく、
`.wit` ファイルとコンパイルされたコンポーネントが乖離することもありません。WIT
が唯一の真実の源です: world がプログラムのエクスポート一覧であり (手書きの
`rontolisp:wasm-export` を併用するとエラーになります)、
[フラットな型](#supported-wit-types)だけの world から生成されるコンポーネントは
手書きのディレクティブが生成するものとバイト単位で同一です。これは通常の関数では
なくコンパイル時のディレクティブです。**インタプリタ**および **JVM**
バックエンドでも同じ契約チェックを行ったうえで `nil` を返すため、同じソースが
すべてのバックエンドで動作します。詳細は
[WIT world の実装](../../guides/wit-contracts.md#implementing-a-wit-world-wit-export)
を参照してください。

このディレクティブはディスク上の `.wit` ファイルを読むため、例は静的に示します。

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
wasmtime run --invoke 'greet("world")' greet.wasm
# "Hello, world!"
```

## 引数

- WIT ファイルのパス (文字列)。相対パスは、それを書いたソースファイルのディレクトリ
  を基準に解決されます ([`load`](load.md) と同じ)。WIT パッケージのディレクトリも
  指定でき、[`rontolisp:wit-import`](rontolisp-wit-import.md) と同じように読みます。
- `:world` — 実装する world。素のシンボル (WIT の綴りのまま) または文字列で
  指定します。ファイルが world を 1 つだけ宣言している場合は省略できます。複数
  宣言している場合は 1 つを指定しなければなりません。パッケージのディレクトリでは、
  数えて探すのはそのパッケージ自身の world で、`deps/` のものは含みません。
- `:reactor` — `t` にすると、プログラムは world のエクスポートを入口とするリアクタに
  なります。`--component` のビルドは `wasi:cli/run` を持たず、エクスポートが最初に
  呼ばれたときに、その本体より前にプログラムのトップレベルを一度だけ実行します。
  そのため、ホストが直接呼ぶエクスポートからも、トップレベルが設定したものが読めます
  ([リアクタ](../../guides/wit-contracts.md#a-reactor-reactor-t))。指定しなければ、
  エクスポートを呼ぶホストはトップレベルを実行しません。WASI のコマンドモジュール
  (`-o out.wasm` だけ、または `--native`)では拒否されます。`--no-wasi` と `--no-gc` の
  出力は、もともとリアクタです。インタプリタと JVM は無視します。
- `:octets` — `t` を指定すると、`list<u8>` の引数を、UTF-8 として復号した文字列ではなく、
  オクテットそのままの `(unsigned-byte 8)` ベクタで受け取ります
  (逆向きの [`rontolisp:wit-import`](rontolisp-wit-import.md#arguments) にある同名の
  オプションと同じ意味です)。
- `:envelope` — `t` を指定すると、WIT の結果が `result` の関数は、ok の値を返して error 側で
  `rontolisp:wit-error` を通知する代わりに、`(:ok . 値)` か `(:error . 値)` を自分で
  返します ([複合型](#compound-types))。Clojure フロントエンドの `rontolisp.wit/export` と
  Java フロントエンドの `@WitExport` がこれを渡します。

それ以外はすべて world から得られます。`rontolisp:wasm-export` の `:params`、
`:param-names`、`:returns`、`:async` はすべて world から埋められるため、`defun`
側には境界型が一切現れません。

## サポートする WIT 型

| WIT type | Boundary type | Lisp value |
| --- | --- | --- |
| `s8` `s16` `s32` | `:s8` `:s16` `:s32` | an integer |
| `u8` `u16` `u32` | `:u8` `:u16` `:u32` | an integer |
| `s64` `u64` | `:s64` `:u64` | an integer; a `u64` value of 2^63 or more traps at the boundary |
| `f64` | `:float` | a float |
| `bool` | `:bool` | `t` or `nil` |
| `string` | `:string` | a string |
| (no result) | `:void` | the function's value is discarded |

固定幅整数の族がすべて渡るため、コンポーネントモデルの標準的なチュートリアル
world がそのまま (無編集で) コンパイルできます。

```console
// wit/adder.wit
package docs:adder@0.1.0;

interface add {
  add: func(x: u32, y: u32) -> u32;
}

world adder {
  export add;
}
```

各型はその範囲を正確に運ぶか、さもなければ呼び出しをトラップさせます。`u32` で
返した負数は `4294967295` として届くのではなく拒否されます。コンポーネントモデルに
整数の部分型関係はないため、これは見た目の問題ではありません。`u32` を `s32` として
リフトしたコンポーネントは、`wasm-tools component targets` にも `jco` にも
`bindgen` ベースのホストにも、自分自身の world に対して拒否されます。

world 中の `async func` はエクスポートを `:async t` としてリフトするため、その中では
ブロッキング待機が常に合法です: 同期エクスポート内の I/O も通常は動作します
(非同期組み込みはホストが即座に受理する限りブロックせずに完了します) が、BLOCKED を
報告するホストではトラップし、非同期リフトはその残余リスクを取り除きます —
非同期かどうかを
推測させるのではなく、WIT が宣言します。

### 複合型

コンポーネント (`--component`) のエクスポートでは、上の表以外の WIT 型も受け渡せます。
Lisp の値との対応は [`rontolisp:wit-import`](rontolisp-wit-import.md) と同じです。

| WIT 型 | Lisp の値 |
| --- | --- |
| `record` | キーワードの plist。`(:x 1 :y 2)` |
| `enum` | キーワード。`:green` |
| `variant` | ケース名のキーワード。ペイロードを持つケースは `(キーワード . ペイロード)` |
| `tuple` | リスト |
| `list<T>` | リスト |
| `list<u8>` | 文字列か `(unsigned-byte 8)` ベクタ。引数は文字列で届き、`:octets t` を指定するとベクタで届く |
| `option<T>` | 値そのもの。none は `nil` |
| 関数の結果としての `result<T, E>` | 返した値が ok 側。error 側はペイロードを付けて `rontolisp:wit-error` を通知する |
| それ以外の位置の `result<T, E>` | `(:ok . 値)` か `(:error . 値)` |
| `f32` `char` | 浮動小数点数、文字 |

```console
// wit/shapes.wit
package example:shapes@0.1.0;

interface geometry {
  record point { x: s32, y: s32 }
  variant shape { circle(f64), rect(point), dot }

  flip: func(p: point) -> point;
  area: func(s: shape) -> result<f64, string>;
}

world shapes {
  export geometry;
}
```

```console
(defun flip (p) (list :x (getf p :y) :y (getf p :x)))

(defun area (s)
  (cond ((eq s :dot) (error 'rontolisp:wit-error :payload "a dot has no area"))
        ((eq (car s) :circle) (* 3.0d0 (cdr s) (cdr s)))
        (t (float (* (getf (cdr s) :x) (getf (cdr s) :y)) 1.0d0))))

(rontolisp:wit-export "wit/shapes.wit" :reactor t)
```

```bash
rontolisp shapes.lisp --component -o shapes.wasm
wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
# {x: 2, y: 1}
wasmtime run --invoke 'area(dot)' shapes.wasm
# err("a dot has no area")
```

結果が `result` の関数は、ok 側の値を返し、error 側では `rontolisp:wit-error` を
通知します。これは[プロバイダ](rontolisp-wit-provide.md)の書き方と同じです。それ以外の
コンディションを捕まえずに終えると、呼び出しはトラップします。宣言された型で表せない値も
トラップします。たとえば `u8` のフィールドに 300 を入れた場合、どのケースの名前でもない
キーワードを返した場合、`list<T>` の結果としてリスト以外を返した場合です。コンポーネントは、
world とエクスポートするインタフェースが定義する型をすべて宣言します。そのため
`--emit-wit` はそれらの型を出力し、別のコンポーネントがそのインタフェースをインポート
できます
([`examples/wit/shapes`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
は 2 つのコンポーネントを合成する例です)。

次のものは受け渡せず、エクスポートの WIT の行を示すコンパイルエラーになります。`flags`、
リソースのハンドル、`stream`、`future`、インタフェースが `use` で別のインタフェースから
取り込んだ型、`async func` の中の複合型です。WASI モジュール (`-o out.wasm` だけ) と
`--no-gc` はフラットな型だけをエクスポートでき、それ以外の型を含む world を拒否します。

`--no-wasi` のモジュールも複合型をエクスポートできます。コアのエクスポートは正準 ABI の
コアの値を受け取り、[`--emit-js-glue`](../../guides/wasm-host-boundary.md#wit-interfaces-beyond-the-flat-set)
のファイルがそれを下ろし、持ち上げます。各エクスポートはラベルを camelCase にした名前の
エントリポイントになり、ホストのインポートが受け取るのと同じ JavaScript の値で呼びます
(record はオブジェクト、variant は `{ tag, val }`、none は `undefined`、64 ビットは BigInt)。
結果が `result` なら ok 側の値を返し、error 側はグルーの `WitError` として投げます。
`--reentrant` はこのエクスポートを拒否します。

```js
import { instantiate, WitError } from "./shapes.js";

const lisp = instantiate(new WebAssembly.Module(bytes));
lisp.flip({ x: 1, y: 2 }); // { x: 2, y: 1 }
try {
  lisp.area({ tag: "dot" });
} catch (error) {
  if (error instanceof WitError) console.log(error.payload); // a dot has no area
}
```

## チェックされる内容

すべての違反は、WIT ファイル名と該当エクスポートの行番号を示すコンパイルエラーに
なります。

- world が宣言しているのに対応する `defun` がない —
  `wit/greeter.wit:5: export 'greet' has no matching (defun greet ...) in the program`
- 引数の数の不一致 —
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`
  (エクスポートされる関数は必須引数のみを取ります。`&optional` / `&rest` /
  `&key` は拒否されます)
- エクスポートで受け渡せない WIT 型。WASI モジュールと `--no-gc` ではフラットな型以外、
  それ以外の出力でも `flags`、ハンドル、`stream`、`future`、別のインタフェースの型
- コンポーネントモデルのラベル (lower-kebab-case の語) でないエクスポート名、
  エクスポートの重複 (WIT リーダーが先に `wasm-tools component wit` の文面で拒否します:
  `` export `a` conflicts with prior func of same name ``)、予約名 `run` (コンポーネントの `wasi:cli/run`
  エントリポイント)
- エクスポートを 1 つも宣言しない world、ファイルに存在しない `:world`、および
  ファイルが複数の world を宣言しているときの `:world` の省略

world がエクスポート一覧そのものであるため、手書きの形式との併用もエラーです:
`rontolisp:wit-export` があるプログラム中の `rontolisp:wasm-export`、および
world と `rontolisp:http-handler` の併用 (serve モードのコンポーネントは
`wasi:http/handler@0.3.0` だけをエクスポートします)。

## 制限事項

- 契約となるのは world の**エクスポート**側だけです。`import` 項目は無視され
  (コンポーネントの WASI インポートは world からではなくビルドから来ます)、インラインの `import name: func(...)` は黙って捨てるのではなく拒否
  されます。プログラムが呼び出す関数は
  [`rontolisp:wit-import`](rontolisp-wit-import.md) でインターフェースから束縛するか、
  [`rontolisp:wasm-import`](rontolisp-wasm-import.md) で手書きします
  (どちらも Preview 1 のみ)。
  そのため、得られるコンポーネントは書いた world よりずっと大きな型を持ちます:
  上の 6 行の world は 149 行のコンポーネント型 (10 個の `wasi:*` インポートと
  `export wasi:cli/run`) にコンパイルされ、`greet` の中で `rontolisp:fetch` を
  呼べばさらに 5 つが黙って加わります。それを見る手段が `--emit-wit` です。
- world がエクスポートできるのは、素の関数か、**同じファイル内で定義された
  インターフェース**です: ファイル内の `interface add { ... }` を参照する
  `export add;` や、インライン `export ops: interface { ... }` は、関数ごとに
  実装され、本物の `docs:adder/add` インスタンスエクスポートを生成します(参照:
  [インターフェースをエクスポートする](../../guides/wit-contracts.md#exporting-an-interface))。
  ファイルが定義しないインターフェース(素の `wasi:*` 参照)を指すエクスポートは
  依然としてエラーです。
- 同じラベルのエクスポート 2 つ(world の関数とインターフェースの関数、または 2 つの
  インターフェースの関数)は、どちらもそのラベルが綴る `defun` で、コンポーネントは
  それぞれをエクスポートします。`wasmtime run --invoke 'docs:adder/add.add@0.1.0(20, 22)'`
  はインターフェースの関数を、素の `add(20, 22)` は world の関数を呼びます。コア
  モジュール(Preview 1、`--no-wasi`)は関数をラベルだけでエクスポートするため、これを
  拒否します。
- `:s-expr` に対応する WIT の綴りはないため、任意の S 式を境界で受け渡すエクスポート
  には引き続き手書きの
  [`rontolisp:wasm-export`](rontolisp-wasm-export.md) が必要です (したがって
  world を持たないプログラムになります)。
- **インタプリタ**ではディレクティブは順に評価される通常のフォームなので、**それ
  までに**定義された関数しか見えません。ファイルの末尾に置いてください
  (コンパイルパスは先にすべてのトップレベル `defun` を収集するため、位置は
  問いません)。
- `--emit-wit` を追加するとコンポーネントの実際の型が書き出され、そのエクスポート
  行は渡した world を引数名も含めて再現します — ただし偶然ではなく構成上そうなり
  ます: それらの行は world *から*導出されるので、world と食い違いようがありません。
  出力する価値があるのはインポート側であって、プログラムのチェックとしてではあり
  ません (それは `wit-export` 自身の仕事であり、すべてのバックエンドで走ります)。
  入力ファイルとの意図的な違いが 2 つあります: `///` ドキュメントコメントは失われ
  ること (コンポーネントの型はそれを保持しません)、そして出力される world は常に
  `package root:component; world root` であることです。
