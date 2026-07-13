# rontolisp:wasm-export

`(rontolisp:wasm-export 'name :as "alias" :params '(type...) :returns type :async t)`

WebAssembly コアモジュールへコンパイルする際に、トップレベルの `defun` を
ホストから呼び出し可能にし、その引数と戻り値の WASM 境界型を宣言します。これは
通常の関数ではなくコンパイル時のディレクティブです。**インタプリタ**および
**JVM** バックエンドでは、名前付きシンボルをそのまま返す no-op となるため、同じ
ソースがすべてのバックエンドで動作します。詳細は
[WebAssembly へのコンパイル](../../compiling/wasm.md) を参照してください。

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)   ; => fact
```

## 引数

- エクスポートするトップレベル `defun` を指すクォートされたシンボル。`defun`
  名と同じように現在の[パッケージ](../packages.md)で解決されます。
- `:as` — WASM エクスポート名の文字列 (例: `"factorial"`、JavaScript 向け API の
  camelCase 名など)。デフォルトは (パッケージ修飾子を除いた) 素の Lisp 名 (`fact`) です。
- `:params` — 各引数に対応する境界型指定子のリスト。省略、`nil`、`'()` の場合は
  引数なしを意味します。
- `:returns` — 戻り値の境界型指定子。省略、`nil`、`'()`、`:void` の場合は void の
  戻り値 (Lisp の戻り値は破棄される) を宣言します。
- `:async` — `t` の場合、`--component` でエクスポートを**非同期**のコンポーネント
  モデル関数としてリフトします。これにより内部の I/O (`print`、`rontolisp:fetch`
  など) がトラップせず動作します。デフォルトは `nil` (同期・純粋計算のリフト)
  です。意味を持つのは `--component` のみで、Preview 1 / `--no-wasi` のコア
  エクスポートは無視し、`--no-gc --component` は拒否します。

型指定子と境界表現は次のとおりです。

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:long` | `i64` | `--no-gc` only; full 64-bit signed range, matching the scalar backend's internal `i64` (no `wrap`/`extend` at the boundary) |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | s-expression text in linear memory (any value except a function) |

デフォルト (GC) バックエンドでは、`:int` は境界を `i32` として渡りますが、内部の
整数は `i31ref` であるため、`:int` 経由で返す値は 32 ビット境界を超えると切り詰め
られます。非 GC バックエンド (`--no-gc`) では整数を `i64` で計算するため、引数や
戻り値が 32 ビット範囲を超えうる場合は `:long` を使ってください。`wrap`/`extend`
変換なしで全幅を公開します。

## 制限事項

- `--component` では、エクスポートは WAVE
  構文 (`wasmtime run --invoke 'name(args)'`) で呼び出せる**型付き
  コンポーネントモデルエクスポート**になります:
  `:int`/`:float`/`:bool`/void に加えて、`:string` と `:s-expr` は
  コンポーネントモデルの `string` として渡ります (`--no-gc` ではさらに `:long`。
  ただし `:s-expr` はありません)。同期 (デフォルト) のエクスポートは純粋計算で
  なければならず、内部の I/O はトラップします。印字や fetch を行うエクスポート
  には `:async t` を宣言してください。`--no-gc --component` では `:async` は
  拒否されますが、印字は同期エクスポートの中でそのまま動作します (プログラムが
  印字するときだけ配線される、組み込みの WASI 0.2 stdio マイクロアダプタを
  通じて)。エクスポート名は
  lower-kebab-case である必要があります (そうでない場合は
  `:as` で改名します)。また `--wit` を追加すると、コンポーネントの WIT world
  (すべてのエクスポートの型付きシグネチャ入り) が `.wasm` の隣に書き出されます。
  [コンポーネントモデル関数エクスポート](../../compiling/wasm.md#component-model-function-exports-wasm-export)
  と[コンパクトなコンポーネント出力](../../compiling/wasm.md#compact-component-output---no-gc---component)
  を参照してください。インタプリタおよび JVM では名前付きシンボルを返すだけです。
- エクスポートできるのはトップレベルの `defun` のみで、宣言した引数の数はその
  アリティと一致しなければなりません。また関数値を引数や戻り値とする関数は対象外
  です。
- `--component` 以外では、エクスポートされる関数は純粋計算です。あらゆる I/O
  (出力、入力、時刻、乱数、トップレベルの I/O フォーム) は `--no-wasi` では
  トラップし、それ以外ではサポートされません。1 つ例外があります: `--no-gc` では
  `print`/`princ`/`terpri` が、プログラムが印字する場合にのみ追加される単一の
  `fd_write` インポートを通じて動作します
  ([印字](../../compiling/wasm.md#printing-print--princ--terpri)を参照)。
- 非 GC バックエンド (`--no-gc`) は `:int`/`:long`/`:float`/`:bool`/`:string` を
  サポートしますが、cons/リーダ/プリンタのランタイムを必要とする `:s-expr` は
  サポートしません。`:long` は `--no-gc` 専用です。GC バックエンドは (整数が `i64`
  を保持できない `i31ref` であるため) これを拒否し、`--no-gc` を使うよう促します。
