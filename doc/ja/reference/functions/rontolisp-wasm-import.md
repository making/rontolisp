# rontolisp:wasm-import

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(type...) :returns type)`

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

`:string` の戻り値は、ホストがリニアメモリに書き込み (バッファはエクスポート
された `__ronto_alloc` で確保)、`(ptr, len)` のペア (JavaScript からは要素数 2 の
配列) として返す必要があります。

## 制限事項

- デフォルトの (wasm-GC) Preview 1 コアモジュールにのみ適用されます。
  `--component` と `--no-gc` はこのディレクティブをエラーで拒否します。
  インタプリタおよび JVM では、宣言した名前を呼び出すとエラーを通知します。
- ディレクティブは `defun` と同様、使用前にトップレベルに置く必要があります。
- コンパイルしたモジュールのインスタンス化には、ホストが宣言済みのすべての
  インポートを提供する必要があります。`wasmtime run` ではインポートモジュール名
  ごとに `--preload <module>=<file>.wasm` が必要で、JavaScript ホストは
  インポートオブジェクトを渡します。
- 引数は最大 7 個です (WASM バックエンド全般のアリティ制限)。
