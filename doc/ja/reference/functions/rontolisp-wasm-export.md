# rontolisp:wasm-export

`(rontolisp:wasm-export 'name :params '(type...) :returns type)`

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

- エクスポートするトップレベル `defun` を指すクォートされたシンボル。エクスポート
  名は素の Lisp 名 (`fact`) です。
- `:params` — 各引数に対応する境界型指定子のリスト。省略、`nil`、`'()` の場合は
  引数なしを意味します。
- `:returns` — 戻り値の境界型指定子。省略、`nil`、`'()`、`:void` の場合は void の
  戻り値 (Lisp の戻り値は破棄される) を宣言します。

型指定子と境界表現は次のとおりです。

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:sexpr` | `(ptr, len)` | s-expression text in linear memory (any value except a function) |

## 制限事項

- Preview 1 コアモジュールにのみ適用されます。`--component` では no-op (ラッパーは
  生成されません) となり、インタプリタおよび JVM では名前付きシンボルを返すだけ
  です。
- エクスポートできるのはトップレベルの `defun` のみで、宣言した引数の数はその
  アリティと一致しなければなりません。また関数値を引数や戻り値とする関数は対象外
  です。
- エクスポートされる関数は純粋計算です。あらゆる I/O (出力、入力、時刻、乱数、
  トップレベルの I/O フォーム) は `--no-wasi` ではトラップし、それ以外ではサポート
  されません。
- 非 GC バックエンド (`--no-gc`) は `:int`/`:float`/`:bool`/`:string` をサポート
  しますが、cons/リーダ/プリンタのランタイムを必要とする `:sexpr` はサポートしま
  せん。
