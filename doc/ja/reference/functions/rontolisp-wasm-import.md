# rontolisp:wasm-import

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(type...) :returns type [:async t])`

WASM ホスト (ブラウザの JavaScript、または wasmtime にプリロードされた別の
モジュール) が提供する関数を宣言し、`name` という名前でトップレベルの `defun`
とまったく同じように Lisp から呼び出せるようにします — `#'name`、`funcall`、
`mapcar`、`eval` も使えます。これは通常の関数ではなくコンパイル時の
ディレクティブです。**インタプリタ**および **JVM** バックエンドでは、呼び出すと
エラーを通知するスタブを定義する (呼び出すべきホストが存在しない) ため、同じ
ソースはすべてのバックエンドでロードできます。詳細は
[WASM ホスト境界ガイド](../../guides/wasm-host-boundary.md) を、完全なブラウザ
プログラムは [WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)
を参照してください。

```lisp
(rontolisp:wasm-import 'draw-pixel :from "gl" :as "drawPixel"
                       :params '(:int :int :int) :returns :void)   ; => draw-pixel
```

## 引数

- Lisp から見える関数名を指すクォートされたシンボル。`defun` 名と同じように
  現在の[パッケージ](../packages.md)で解決されるため、`(in-package mylib)`
  の後のディレクティブは `mylib:name` を定義します。
- `:from` — インポートモジュール名 (JavaScript 側のインポートオブジェクトの
  キー、wasmtime では `--preload` 名)。デフォルトは `"env"`。
- `:as` — インポートフィールド名 (そのモジュールオブジェクト内のプロパティ)。
  デフォルトは (パッケージ修飾子を除いた) 素の Lisp 名。
- `:params` — 各引数に対応する境界型指定子のリスト。省略、`nil`、`'()` の場合は
  引数なしを意味します。
- `:returns` — 戻り値の境界型指定子。省略、`nil`、`'()`、`:void` の場合は void の
  戻り値 (Lisp は `nil` を受け取る) を宣言します。

型指定子は [`rontolisp:wasm-export`](rontolisp-wasm-export.md) と共通です。

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:float` | `f64` | an int or ratio argument is converted like the arithmetic built-ins |
| `:bool` | `i32` | `nil` crosses as `0`, anything else as `1`; a non-zero result reads back as `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | the argument is printed to readable text; a result is parsed by the embedded reader |
| `:bytes` | `(ptr, len)` argument / `(ptr, cap) -> len` result | an `(unsigned-byte 8)` vector as raw bytes — no UTF-8 in either direction |

`:string` の戻り値は、ホストがリニアメモリに書き込み (バッファはエクスポート
された `__ronto_alloc` で確保)、`(ptr, len)` のペア (JavaScript からは要素数 2 の
配列) として返す必要があります。

`:bytes` の**結果**は呼び出し側バッファ方式 (`read(2)` の形) です: Lisp
シグネチャの末尾に受信用の `(unsigned-byte 8)` ベクタが 1 つ加わり、ホスト
関数は末尾の `(ptr, cap)` ペア付きで呼ばれます — *`ptr` に最大 `cap` バイトを
書き込み、値の全長を返す*。Lisp の呼び出しはその全長を返すため、バッファより
長い結果は黙った切り詰めではなく、より大きいバッファでのリトライになります。
ラッパーのステージングは返却時にポップされるため、1 つのバッファを使い回す
pull ループはリニアメモリをフラットに保ちます。

```lisp
(rontolisp:wasm-import 'read-chunk :from "env" :as "readChunk"
                       :params '() :returns :bytes)   ; => READ-CHUNK
;; (read-chunk buf) => the chunk's full length; up to (length buf) bytes
;; of buf are overwritten. On the JS side: readChunk(ptr, cap) -> n.
```

## `:async t` — サスペンドしうるホスト関数

`:async t` は、ホストがこの関数を**非同期に**実装しうること — JavaScript
ホストでは `WebAssembly.Suspending` でラップされた関数 (JSPI) — を宣言します。
呼び出しは [`rontolisp:await`](../special-forms/rontolisp-await.md) で解決できる **future** を
返すため、境界が非同期であることを呼び出し側のソースが語れます — このバックエンド
ではまさにこのオプションに脱糖される [`rontolisp:wit-import`](rontolisp-wit-import.md)
の `async func` メンバーと同じ読みです。(この語は意図的に
[`rontolisp:wasm-export`](rontolisp-wasm-export.md) の `:async` と揃えて
あります。WIT は両方向とも `async func` と綴り、方向はディレクティブ自体が
示します。)

```lisp
(rontolisp:wasm-import 'host-fetch :from "env" :as "fetch"
                       :params '(:string) :returns :string :async t)   ; => HOST-FETCH
```

- このバックエンドでは future は**生成時点で settled** です。ホスト呼び出しは
  wasm スタックをブロックする (同期的に、または JSPI でサスペンドして) ため、
  呼び出しが返った時点で値は用意されており、`await` が実際にサスペンドすることは
  ありません。このオプションが買うのはどのバックエンドでも同じに読めるひとつの
  ソースであり、並行性ではありません。
- ビルドはホストが負う義務を出力します: インポートを `WebAssembly.Suspending`
  でラップし、そこへ到達しうるすべてのエクスポート (ビルドが列挙します) を
  `WebAssembly.promising` 経由で呼び出し、呼び出しを直列化する —
  サスペンドしたモジュールは再入されうるためです。再入されたエクスポートは
  両方の呼び出しを静かに壊す代わりに**トラップで拒否**します (サスペンドしうる
  モジュールのすべてのエクスポートラッパーが再入ガードを持ちます)。
  同期的に応答するホストも同様に有効で、その場合も呼び出しは settled 済みの
  future を返します。`--emit-js-glue` はその半分を説明する代わりに書き出します
  ([ホスト境界ガイド](../../guides/wasm-host-boundary.md#generating-the-host-glue---emit-js-glue))。
  ホストに残るのは各関数が何をするかと、そのうちどれがサスペンドするかの宣言
  だけです。
- `--no-wasi` では、トップレベルフォームから到達しうる呼び出しは
  **コンパイルエラー**です。`_initialize` は `promising` が入っていない
  スタックで実行されるため、そこでのサスペンドは誰の名前も出さずにトラップ
  します。呼び出しをエクスポートの背後へ移すか、ホストが同期的に応答するなら
  `:async t` を外してください。

## 制限事項

- デフォルトの (wasm-GC) Preview 1 コアモジュールにのみ適用されます。
  `--component` と `--no-gc` はこのディレクティブをエラーで拒否します。
  インタプリタおよび JVM では、宣言した名前を呼び出すとエラーを通知します。
- ディレクティブは `defun` と同様、使用前にトップレベルに置く必要があります。
- コンパイルしたモジュールのインスタンス化には、ホストが宣言済みのすべての
  インポートを提供する必要があります。`wasmtime run` ではインポートモジュール名
  ごとに `--preload <module>=<file>.wasm` が必要で、JavaScript ホストは
  インポートオブジェクトを渡します。
- 引数は最大 10 個です (WASM バックエンド全般のアリティ制限)。
