# rontolisp:wit-export

`(rontolisp:wit-export "world.wit" :world name)`

プログラムが **WIT world を実装している**ことを宣言します。world の `export`
項目はコンパイル時にプログラムのトップレベル `defun` と照合され、それが表す
[`rontolisp:wasm-export`](rontolisp-wasm-export.md)
ディレクティブへとローワリングされます。したがって境界型を手で書くことはなく、
`.wit` ファイルとコンパイルされたコンポーネントが乖離することもありません。WIT
が唯一の真実の源です: world がプログラムのエクスポート一覧であり (手書きの
`rontolisp:wasm-export` を併用するとエラーになります)、
[フラットな型](#supported-wit-types)だけを使い、自前の型を定義しない world から
生成されるコンポーネントは、手書きのディレクティブが生成するものとバイト単位で同一です。これは通常の関数では
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
| `flags` | 立っているラベルのキーワードのリスト。`(:read :write)` |
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
トラップします。たとえば `u8` のフィールドに 300 を入れた場合、どのケースやラベルの名前でもない
キーワードを返した場合、`list<T>` の結果としてリスト以外を返した場合です。

コンポーネントは、world とエクスポートするインタフェースが定義する型をすべて宣言します。
フラットな型のエイリアス (`type id = u32`) も宣言し、型だけのインタフェースはその型を
エクスポートします。`--no-gc --component` も、受け渡せるフラットな型について同じことを
します。そのためコンポーネントは world に適合し
(`wasm-tools component targets world.wit out.wasm` が受け付けます)、`--emit-wit` は
それらの型を出力し、別のコンポーネントがそのインタフェースをインポートできます
([`examples/wit/shapes`](https://github.com/making/rontolisp/tree/develop/examples/wit/shapes)
は 2 つのコンポーネントを合成する例です)。

`use` で別のインタフェースから取り込んだ型は、取り込んだ側のスコープ自身の型として、
`use` 句が与えた名前で宣言します。コンポーネントモデルでは形が同じ値の型は同一の型なので、
コンポーネントはそのために何もインポートしません。取り込んだ型が含む型も一緒に宣言します。
エイリアスはそれが指す型として、レコード、バリアント、enum は元の名前で宣言します。

```console
interface geometry {
  record point { x: s32, y: s32 }
  record line { a: point, b: point }
}

interface shapes {
  use geometry.{line};            // line と、それが含む point を宣言する
  length: func(l: line) -> f64;
}
```

world の型はコンポーネントのインポートになるので、world の `use` では、そのような型も
句に書きます (`use geometry.{line, point};`)。スコープがすでに別の型に与えている名前は
`use` 句の行でエラーになるので、句の中で名前を変えます (`point as corner`)。

次のものは受け渡せず、エクスポートの WIT の行を示すコンパイルエラーになります。
`stream`、`future`、world が宣言するリソースのハンドル
([リソース](#resources)) です。`async func` が
受け渡せる型は、ほかの関数と同じです。WASI モジュール
(`-o out.wasm` だけ) と `--no-gc` はフラットな型だけをエクスポートでき、
それ以外の型を含む world を拒否します。

`--no-wasi` のモジュールも、複合型をエクスポートできます。コアのエクスポートは正準 ABI の
コアの値を受け取り、[`--emit-js-glue`](../../guides/wasm-host-boundary.md#wit-interfaces-beyond-the-flat-set)
のファイルがそれを下ろし、持ち上げます。各エクスポートはラベルを camelCase にした名前の
エントリポイントになり、ホストのインポートが受け取るのと同じ JavaScript の値で呼びます
(record はオブジェクト、variant は `{ tag, val }`、none は `undefined`、flags は真偽値のオブジェクト、64 ビットは BigInt)。
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

### リソース

エクスポートするインタフェースが宣言する `resource` は、コンポーネント
(`--component`、`--no-wasi` の有無は問いません) がエクスポートします。`--no-gc --component`
も整数でエクスポートします ([スカラーバックエンドのリソース](#on-the-scalar-backend))。リソースの関数は
`defun` で実装し、名前は [`rontolisp:wit-import`](rontolisp-wit-import.md) が
インポート側で束縛する名前と同じです。

| WIT | `defun` | 引数 |
| --- | --- | --- |
| `counter` の `constructor(...)` | `counter-new` | コンストラクタの引数 |
| メソッド `add: func(...)` | `counter-add` | リソース、続いてメソッドの引数 |
| `merge: static func(...)` | `counter-merge` | 関数の引数 |
| (ハンドルの解放) | `counter-drop` (省略可) | リソース |

**コンストラクタが返した値がリソースです**。構造体、コンス、クロージャなど、どの Lisp の
値でも構いません。ホストが持つのはその値のハンドルです。WIT が `counter`、
`own<counter>`、`borrow<counter>` と書く場所では、引数、結果、レコードのフィールド、
リストの要素のどこであっても、関数はその値を受け取り、その値を返します。

```console
// wit/counters.wit
package example:counters@0.1.0;

interface registry {
  resource counter {
    constructor(name: string, start: u32);
    add: func(by: u32) -> u32;
    merge: static func(a: counter, b: borrow<counter>) -> counter;
  }

  total: func(counters: list<borrow<counter>>) -> u32;
}

world counters {
  export registry;
}
```

```console
(defstruct counter name value)

(defun counter-new (name start) (make-counter :name name :value start))

(defun counter-add (self by) (incf (counter-value self) by))

(defun counter-merge (a b)
  (incf (counter-value a) (counter-value b))
  a)

(defun total (counters) (reduce #'+ counters :key #'counter-value))

(rontolisp:wit-export "wit/counters.wit" :reactor t)
```

- **借用**のハンドル (`borrow<counter>`、メソッド自身のリソース) では、呼び出しの間だけ
  値を使えます。**所有**のハンドル (`counter`、`own<counter>`) では値が関数に移ります。
  呼び出し側のハンドルは無効になり、関数はその値を保持しても、書き換えても、再び返しても
  構いません。所有のハンドルとして返した値には、返すたびに新しいハンドルが作られます。
- プログラムが `counter-drop` を定義していれば、ハンドルの持ち主がハンドルを解放する
  たびに、その値を引数として呼ばれます。エクスポートに所有で渡されたハンドルは解放では
  ないので、呼ばれません。ハンドルがなくなった値は、通常のガベージです。
- エクスポートする別のインタフェースは、`use` 句 (`use registry.{counter};`) でその
  リソースを取り込み、受け取ることも返すこともできます。取り込んだリソースは宣言元と
  同じリソースで、コンポーネントは宣言元のインタフェースを先にエクスポートします。
  ハンドルを含む型を別のインタフェースから取り込む `use` 句は拒否します (句にはリソースを
  書き、型は使う側のインタフェースで定義します)。
- 1 つの `defun` が 2 つのエクスポートの実装になる場合 (メソッド `counter.add` と関数
  `counter-add`) は拒否します。`:names` の表では、リソースの関数をメンバー名
  (`"counter-add"`) で指定します。

#### コンポーネントがインポートするリソース

world がエクスポートしないインタフェースのリソースを、エクスポートするインタフェースや
world 自身が `use` 句で取り込むと、そのリソースはコンポーネントが**インポート**する
リソースになります。コンポーネントはそのためにインタフェースをインポートし、ハンドルは
[`rontolisp:wit-import`](rontolisp-wit-import.md) の束縛が扱うのと同じ整数です。
プログラムはそのインタフェースを `wit-import` で束縛してリソースを呼び出し、束縛と
エクスポートは 1 つのインポートを共有します。

```console
// wit/auditor.wit
package example:counters@0.1.0;

interface registry {
  resource counter {
    add: func(by: u32) -> u32;
  }
}

interface audit {
  use registry.{counter};

  bump: func(c: borrow<counter>, by: u32) -> u32;
}

world auditor {
  import registry;
  export audit;
}
```

```console
(rontolisp:wit-import "wit/auditor.wit"
                      :interface "example:counters/registry"
                      :package reg)

(defun bump (c by) (reg:counter-add c by))

(rontolisp:wit-export "wit/auditor.wit" :reactor t)
```

`registry` をエクスポートする前述のコンポーネントと合成すると、`bump` はそのコンポーネントが
保持するカウンタに加算します。

- **借用**のハンドルは呼び出しの間だけ貸し出されます。関数が値を返したあとで
  エクスポートが解放するので (コンポーネントモデルでは、借用のハンドルを持ったまま
  返った呼び出しはトラップします)、プログラムは解放しません。**所有**のハンドルは
  プログラムのものです。保持しても、別の関数に渡しても、束縛の `counter-drop` で
  解放しても構いません。所有のハンドルとして返したハンドルは、呼び出し側に移ります。
- エクスポートするインタフェースの `use` 句は、関数がそのリソースを使うかどうかに
  かかわらず、そのインタフェースをインポートします。インタフェースがその型も
  エクスポートするからです。world 自身の `use` 句がインタフェースをインポートするのは、
  world の関数がその句で取り込んだリソースを使う場合だけです。
- 次の 2 つは WIT の行で拒否します。world 自身が宣言する `resource` (その関数は world の
  インポートで、`wit-import` では束縛できません。インタフェースで宣言して `use` で
  取り込みます) と、world がエクスポートするインタフェースのリソースを world 自身の
  `use` で取り込む場合です (この句はそのインタフェースをインポートするので、world の関数が
  受け取るのはインポートしたリソースで、world 自身のリソースではありません。関数は
  インタフェースからエクスポートします)。
- `--component --no-wasi` のコンポーネントは何もインポートしないので、このような world を
  拒否します。コンポーネントが自身の WASI の一部としてインポートするインタフェースの
  リソース (`wasi:filesystem/types` の `descriptor`) も、どのコンポーネントでも拒否します。
- [`examples/wit/resource`](https://github.com/making/rontolisp/tree/develop/examples/wit/resource)
  の `reporter.lisp` はこの種のインタフェースの実装で、コマンドとカウンタを保持する
  コンポーネントのあいだに合成します。

インタプリタと JVM では、ディレクティブはこれらの関数もほかの関数と同じように検査し、
何もエクスポートしません。インタフェースの[プロバイダ](rontolisp-wit-provide.md)として
束縛すれば、同じ関数がプロセス内でそのインタフェースの `wit-import` に応え、その場合の
ハンドルは値そのものです。
[`examples/wit/resource`](https://github.com/making/rontolisp/tree/develop/examples/wit/resource)
は、1 つのプログラムを両方の形で実行する例です。

コアモジュール (`-o out.wasm` だけ、`--component` なしの `--no-wasi` または `--no-gc`) は、
リソースを宣言するインタフェースと、コンポーネントがインポートするリソースを取り込む
インタフェースを拒否します。`--no-gc --component` は、コンポーネントがインポートする
リソースを取り込むインタフェースを拒否します。この出力の `wit-import` はリソースを
束縛しないからです。Java のフロントエンドはリソースをクラスとして
（[エクスポートのリソース](../../java/wit.md#resources-of-an-export)）、Clojure の
フロントエンドはこれらの `defun` と同じ名前の var として
（[リソース](../../clojure/reference/wit.md#resources)）エクスポートします。

#### スカラーバックエンドのリソース

`--no-gc --component` は、関数がフラットな型を受け渡すリソースをエクスポートします。
このバックエンドの値は数なので、リソースはコンストラクタが返した整数で、ハンドルの
表現はその整数です。コンポーネントはハンドルの背後に値を保持しません。

```console
// wit/cells.wit
package example:cells@0.1.0;

interface cells {
  resource cell {
    constructor(v: u32);
    get: func() -> u32;
    plus: static func(a: cell, b: borrow<cell>) -> cell;
  }
  live: func() -> u32;
}

world provider {
  export cells;
}
```

```console
(defvar *live* 0)

(defun cell-new (v) (setq *live* (+ *live* 1)) v)
(defun cell-get (self) self)
(defun cell-plus (a b) (+ a b))
(defun cell-drop (c) (setq *live* (- *live* 1)))
(defun live () *live*)

(rontolisp:wit-export "wit/cells.wit")
```

- 整数は `u32` です。コンストラクタが 0 から 4294967295 の範囲外を返すとトラップします。
  メソッドと、借用したハンドルを受け取る関数は、その整数を受け取ります。
- 所有権ごと受け取ったハンドルも整数で届きます。エクスポートは `cell-drop` を呼ばずに
  そのハンドルを解放します。値は関数に移ったからです (`plus` の `a`)。`cell-drop` は、
  保持者がハンドルを解放したときにその整数で呼ばれます。プログラムが `cell-drop` を
  定義しないリソースにはデストラクタがありません。
- ハンドルを受け渡せるのは、引数か戻り値そのものとしてだけです。リスト、option、
  レコードなどの複合型の中のハンドルは複合型と同じく拒否します。戻り値の借用ハンドルは、
  既定のバックエンドと同じく拒否します。
- 標準出力に書くプログラムは拒否します。標準出力に書く `--no-gc --component` の
  エクスポートはすべて `async func` で、コンストラクタは `async` にできないからです。
  `--no-wasi` では標準出力に何も書かないので、この world もコンパイルできます。

## チェックされる内容

すべての違反は、WIT ファイル名と該当エクスポートの行番号を示すコンパイルエラーに
なります。

- world が宣言しているのに対応する `defun` がない —
  `wit/greeter.wit:5: export 'greet' has no matching (defun greet ...) in the program`
- 引数の数の不一致 —
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`
  (エクスポートされる関数は必須引数のみを取ります。`&optional` / `&rest` /
  `&key` は拒否されます)
- エクスポートで受け渡せない WIT 型。WASI モジュールと `--no-gc` ではフラットな型以外
  (`--no-gc --component` では、world がエクスポートするリソースのハンドルそのものも
  受け渡せます)、それ以外の出力でも `stream`、`future`、world が宣言するリソースのハンドル
- リソースの関数に対応する `defun` (`counter-new`、`counter-add`) がない、または引数の数が
  合わない場合。値 1 つを取らない `counter-drop`。コンポーネント以外の出力で `resource` を
  宣言するインタフェースを、既定のバックエンドのコンポーネント以外の出力でコンポーネントが
  インポートするリソースを取り込むインタフェースをエクスポートする場合。world がリソースを
  エクスポートし、標準出力に書く `--no-gc --component` のプログラム
- エクスポートするインタフェースの `use` 句が、ハンドルを含む型を取り込む場合。また、
  取り込んだ型が含む型にスコープ (world) が名前を与えていない `use` 句と、スコープが別の
  型に与えている名前を使う `use` 句。world がエクスポートするインタフェースのリソースを
  world 自身の `use` 句で取り込み、world の関数がそれを使う場合
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
