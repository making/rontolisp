# rontolisp:wit-export

`(rontolisp:wit-export "world.wit" :world name)`

プログラムが **WIT world を実装している**ことを宣言します。world の `export`
項目はコンパイル時にプログラムのトップレベル `defun` と照合され、それが表す
[`rontolisp:wasm-export`](rontolisp-wasm-export.md)
ディレクティブへとローワリングされます。したがって境界型を手で書くことはなく、
`.wit` ファイルとコンパイルされたコンポーネントが乖離することもありません。WIT
が唯一の真実の源です: world がプログラムのエクスポート一覧であり (手書きの
`rontolisp:wasm-export` を併用するとエラーになります)、生成されるコンポーネントは
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
  を基準に解決されます ([`load`](load.md) と同じ)。
- `:world` — 実装する world。素のシンボル (WIT の綴りのまま) または文字列で
  指定します。ファイルが world を 1 つだけ宣言している場合は省略できます。複数
  宣言している場合は 1 つを指定しなければなりません。

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
推測させるのではなく、WIT が宣言します。それ以外の WIT 型 (`record`、`list`、
`option`、`result`、リソースなど) は現時点ではエクスポート境界でコンパイルエラーに
なります。エラーメッセージは、その型のマーシャリングが実装された際に得られる、
確定済みの rontolisp 表現を示します。

## チェックされる内容

すべての違反は、WIT ファイル名と該当エクスポートの行番号を示すコンパイルエラーに
なります。

- world が宣言しているのに対応する `defun` がない —
  `wit/greeter.wit:5: export 'greet' has no matching (defun greet ...) in the program`
- 引数の数の不一致 —
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`
  (エクスポートされる関数は必須引数のみを取ります。`&optional` / `&rest` /
  `&key` は拒否されます)
- エクスポート境界が運べない WIT 型 (固定幅整数族全体と `f64` / `bool` / `string`
  は渡ります。`record` や `list` などはまだ渡りません)
- `--no-gc --component` での `async func` (アダプタのないリアクターには非同期の
  機構がありません)
- コンポーネントモデルのラベル (lower-kebab-case の語) でないエクスポート名、
  エクスポートの重複、予約名 `run` (コンポーネントの `wasi:cli/run`
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
