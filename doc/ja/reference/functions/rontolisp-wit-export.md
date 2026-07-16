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
[WIT world の実装](../../compiling/wasm.md#implementing-a-wit-world-wit-export)
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
wasmtime run -W gc=y -W component-model-more-async-builtins=y --invoke 'greet("world")' greet.wasm
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
| `s32` | `:int` | an integer (31-bit signed range) |
| `s64` | `:long` | an integer; needs `--no-gc` (wasm-GC integers are `i31ref`) |
| `f64` | `:float` | a float |
| `bool` | `:bool` | `t` or `nil` |
| `string` | `:string` | a string |
| (no result) | `:void` | the function's value is discarded |

world 中の `async func` はエクスポートを `:async t` としてリフトするため、その中の
I/O (`print`、`rontolisp:fetch` など) はトラップせずに動作します — 非同期かどうかを
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
- エクスポート境界が運べない WIT 型 (wasm-GC バックエンドでの `s64` を含む) —
  `calc.wit:4: export 'square': s64 (n) requires --no-gc (the wasm-GC backend's integers are i31ref)`
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
  (コンポーネントの WASI インポートは、それが構築される固定のアダプタ表面から
  来ます)、インラインの `import name: func(...)` は黙って捨てるのではなく拒否
  されます。プログラムが呼び出す関数は
  [`rontolisp:wit-import`](rontolisp-wit-import.md) でインターフェースから束縛するか、
  [`rontolisp:wasm-import`](rontolisp-wasm-import.md) で手書きします
  (どちらも Preview 1 のみ)。
  そのため、得られるコンポーネントは書いた world よりずっと大きな型を持ちます:
  上の 6 行の world は 149 行のコンポーネント型 (10 個の `wasi:*` インポートと
  `export wasi:cli/run`) にコンパイルされ、`greet` の中で `rontolisp:fetch` を
  呼べばさらに 5 つが黙って加わります。それを見る手段が `--emit-wit` です。
- 実装できるのは素の関数エクスポート (`export name: func(...)`) だけです。
  インターフェースをエクスポートする world はエラーになります。
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
