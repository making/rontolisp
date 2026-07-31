# error

`(error datum args...)`

エラーを通知し、外側の [`handler-case`](handler-case.md) に捕捉されなければ実行を中止します。Common Lisp のコンディション designator をサポートします:

- `(error "control" args...)` — 制御文字列は `format` と同じディレクティブ(`~a`、`~s`、`~%` など)を使い、残りの引数を埋めてメッセージを構築します。制御文字列は実行時に計算されたもの(変数、スロット参照、関数呼び出し)でも構いません: 実行時に文字列だった datum はフォーマット制御文字列であり、その後ろの引数はそのフォーマット引数です — リテラルの場合とまったく同じです。
- `(error 'type :initarg value ...)` — 指定したクラス(組み込み、または [`define-condition`](define-condition.md) で定義)のコンディションインスタンスを構築して通知します。メッセージはクラスの `:report` の描画結果です(自身のもの、定義がなければ最も近い祖先のもの)。どの祖先にも `:report` がなく `simple-condition` 系のスロットを持つクラスでは、`:format-control` を `:format-arguments` に対して `format` した結果になります。どこにも `:report` がないクラスは `Condition (type initargs...) was signalled.` 形のままです。このメッセージは、同じコンディションオブジェクトを [`princ`](../functions/princ.md) が出力するテキストと完全に一致します([`define-condition`](define-condition.md) 参照)。
- `(error obj)` — 構築済みのコンディションオブジェクト(例: [`make-condition`](make-condition.md) の結果)を通知します。実行時に文字列だった場合は、それを描画したメッセージとして通知します(最初の項目を参照)。

すべてのバックエンドがコンディションを送出するため、`handler-case` が型でディスパッチできます: インタプリタと JVM はメッセージとコンディションオブジェクトを保持する例外をスローし、wasm-GC バックエンドはプログラムが捕捉フォーム(`handler-case`/`ignore-errors`/`unwind-protect`)を含む場合に WebAssembly 例外をスローし(出力モジュールには `wasmtime -W exceptions=y` が必要)、含まない場合はトラップします。`#'error` は関数値を**持ちます**: インタプリタでは完全な designator プロトコルがそのまま通り(`(apply #'error (list 'my-error :v x))` は型付きコンディションを構築)、コンパイル系バックエンドは datum のみを転送します — シンボル datum はクラス名をメッセージとする素のコンディションを通知し(`handler-case` の `error` 節では引き続き捕捉可能)、後続の initarg/フォーマット引数は捨てられます。

捕捉されない `error` は実行を中止するため、ここでは実行可能な例ではなく静的に示します:

```console
(error "bad value: ~a" x)
(error 'type-error :datum x :expected-type 'integer)
```
