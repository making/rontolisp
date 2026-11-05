# rontolisp:wasm-import

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(type...) [:param-names '(name...)] :returns type [:async t])`

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
                       :params '(:int :int :int) :returns :void)   ; => DRAW-PIXEL
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
- `:param-names` — **コンポーネントモデル**のシグネチャにおけるパラメータ名。
  `:params` の各項目に 1 つずつ、シンボルまたは文字列で指定します。デフォルトは
  `p0`、`p1`、…。読むのは
  [`--no-gc --component`](../../guides/wasm-nogc.md#host-imports-in-a-component)
  だけで、そこではインポートされる関数の型のラベルになります (インポートが WIT
  world を表すときはその名前)。コアモジュールのインポートにパラメータ名は
  ありません。
- `:returns` — 戻り値の境界型指定子。省略、`nil`、`'()`、`:void` の場合は void の
  戻り値 (Lisp は `nil` を受け取る) を宣言します。

型指定子は [`rontolisp:wasm-export`](rontolisp-wasm-export.md) と共通です。

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | an argument must fit the 31-bit signed range (the internal `i31ref`) and traps outside it; a result crosses over the full 32-bit signed range |
| `:u32` | `i32` | the full unsigned range, exactly, and an argument outside it traps; a JavaScript host reads the value as signed, so `n >>> 0` recovers it |
| `:float` | `f64` | an int or ratio argument is converted like the arithmetic built-ins |
| `:bool` | `i32` | `nil` crosses as `0`, anything else as `1`; a non-zero result reads back as `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | the argument is printed to readable text; a result is parsed by the embedded reader |
| `:bytes` | `(ptr, len)` argument / `(ptr, cap) -> len` result | an `(unsigned-byte 8)` vector as raw bytes — no UTF-8 in either direction |
| `:octets` | `(ptr, len)` | imports only: an `(unsigned-byte 8)` vector as a value, raw bytes both ways; an argument may also be a string (its UTF-8 bytes) |

`:string`/`:s-expr` の**引数**は、呼び出しがすでに保持しているメモリへの
`(ptr, len)` ビューです — 読むのは構いませんが、そこへ書き込んではいけません。
バックエンドごとにポインタの正体が何で、なぜ書き込みが安全でないかは
[境界ガイド](../../guides/wasm-host-boundary.md#importing-host-functions)を
参照してください。

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

`:octets` の**結果**は生のバイトに対する `:string` の形です: ホストは
`__ronto_alloc` でバッファを確保してオクテットを書き込み、`(ptr, len)` を返します。
呼び出しはそのオクテットだけを持つ新しい `(unsigned-byte 8)` ベクタを返します
— デコードも受信バッファもありません。
[`rontolisp:wit-import :octets t`](rontolisp-wit-import.md) が Preview 1 で WIT の
`list<u8>` を宣言する型がこれです。

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
  モジュールのすべてのエクスポートラッパーが再入ガードを持ちます。ただし
  [`--reentrant`](../../guides/wasm-host-boundary.md#overlapping-calls---reentrant)
  でコンパイルした場合は例外で、JSPI ホストは呼び出しをオーバーラップできます)。
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

## `"rontolisp:memory"` — モジュール自身のリニアメモリ

この予約された `:from` の値はホスト関数を束縛しません。各ディレクティブはモジュール自身のメモリの操作を指すため、プログラムは出力先のセルを確保し、そのアドレスをホストへ渡し、ホストが書き込んだ値を読み返せます。インポートセクションは増えません。インタプリタと JVM では、通常のインポートと同じくスタブになります。

| `:as` | `:params` | `:returns` | 動作 |
| --- | --- | --- | --- |
| `"alloc"` | `(:u32)` | `:u32` | `__ronto_alloc` と同じくバンプヒープにバイト列を確保します |
| `"mark"` | なし | `:u32` | ヒープの現在の先頭を返します |
| `"reset"` | `(:u32)` | なし | ヒープを `mark` の位置まで戻します |
| `"load8"` / `"store8"` | `(:u32)` / `(:u32 :u32)` | `:u32` / なし | 1 バイトを読み書きします |
| `"load32"` / `"store32"` | `(:u32)` / `(:u32 :u32)` | `:u32` / なし | リトルエンディアンで、アラインメントは問いません |
| `"load64"` / `"store64"` | `(:u32)` / `(:u32 :s64)` | `:s64` / なし | リトルエンディアンで、アラインメントは問いません |
| `"string"` | `(:u32 :u32)` | `:string` | `(ptr, len)` から UTF-8 として新しい文字列を作ります |
| `"octets"` | `(:u32 :u32)` | `:octets` | `(ptr, len)` から新しいバイトベクタを作ります。`--no-gc` では使えません |
| `"search"` | `(:string :u32 :u32)` | `:s32` | 渡した `(ptr, len)` の中で文字列のバイト列が最初に現れる位置を返します。なければ -1 を返します |
| `"address"` | `(:string)` | `:u32` | 文字列の UTF-8 バイト列の場所を返します。読み取り専用で、`--no-gc` でのみ使えます |
| `"size"` | `(:string)` | `:u32` | そのバイト数を返します。`length` が数えるのは文字数です。`--no-gc` でのみ使えます |

Lisp 側の名前は自由に選べますが、フィールド名とシグネチャは固定で、異なる宣言はコンパイルエラーになります。`:async t` と `--no-gc --component` は拒否されます。`:u32` の範囲外のアドレスとメモリ末尾を越えるアクセスはトラップします。それ以外の保護はないため、誤ったアドレスへのストアはそこにある内容を上書きします。

`"alloc"`、`"mark"`、`"reset"` のいずれかを宣言したプログラムは、ヒープを自分で管理します。確保した領域は、ホストからの呼び出しをまたいでも `"reset"` を呼ぶまで解放されません。そのためエクスポートはホストに書き込ませるアドレスを返せますが、長く動くインスタンスでは一時領域をプログラム自身で解放する必要があります。`--no-gc` では、スカラーを返すエクスポートが戻るときに行うリセットもなくなります。プログラムが自前のアロケータ(1 つの引数を `"alloc"` へそのまま渡す関数)をエクスポートし、エクスポートにもホストインポートにも `:string` がない場合、`--no-gc` は `__ronto_alloc` 系のエクスポートを省きます。ホストはそのアロケータを使えるためです。

```lisp
(rontolisp:wasm-import '%alloc :from "rontolisp:memory" :as "alloc"
                       :params '(:u32) :returns :u32)
(rontolisp:wasm-import '%load32 :from "rontolisp:memory" :as "load32"
                       :params '(:u32) :returns :u32)
(rontolisp:wasm-import '%string :from "rontolisp:memory" :as "string"
                       :params '(:u32 :u32) :returns :string)
;; A C-shaped host function: answers through two char**/size_t* out-pointers.
(rontolisp:wasm-import '%get-value :from "env" :as "get_value"
                       :params '(:string :u32 :u32) :returns :s32)

(defun get-value (key)
  (let ((cells (%alloc 8)))
    (when (= 0 (%get-value key cells (+ cells 4)))
      (%string (%load32 cells) (%load32 (+ cells 4))))))
```

[Proxy-Wasm のサンプル](https://github.com/making/rontolisp/tree/develop/examples/proxy-wasm)は、これを使って SDK 全体を構築しています。

## 制限事項

- デフォルト (wasm-GC) バックエンドではコアモジュール専用です。`--component` は
  このディレクティブをエラーで拒否します (コンポーネントはインターフェースを
  [`rontolisp:wit-import`](rontolisp-wit-import.md) で束縛します)。インタプリタ
  および JVM では、宣言した名前を呼び出すとエラーを通知します。
- [`--no-gc`](../../guides/wasm-nogc.md#host-imports-rontolispwasm-import) は
  このディレクティブを受け付けます。型の語彙は独自で、固定幅整数のファミリ全体
  (ハウス整数が `i64` のため)、`:float`、`:bool`、`:string`、`:void` を運びます。
  `:s-expr`、`:bytes`、`:octets`、`:async t` は運べません。
  [`--no-gc --component`](../../guides/wasm-nogc.md#host-imports-in-a-component)
  では到達するインポートがコンポーネントのインポートになるため、`:from` は
  lower-kebab-case のラベルか WIT インターフェース id、`:as` と `:param-names`
  の各項目は lower-kebab-case のラベルでなければなりません。
- ディレクティブは `defun` と同様、使用前にトップレベルに置く必要があります。
- コンパイルしたモジュールのインスタンス化には、ホストが宣言済みのすべての
  インポートを提供する必要があります。`wasmtime run` ではインポートモジュール名
  ごとに `--preload <module>=<file>.wasm` が必要で、JavaScript ホストは
  インポートオブジェクトを渡します。
- 引数は最大 10 個です (WASM バックエンド全般のアリティ制限)。
