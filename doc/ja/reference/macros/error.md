# error

`(error datum args...)`

エラーを通知し、外側の [`handler-case`](handler-case.md) に捕捉されなければ実行を中止します。Common Lisp のコンディション designator をサポートします:

- `(error "control" args...)` — 制御文字列は `format` と同じディレクティブ(`~a`、`~s`、`~%` など)を使うリテラルでなければならず、残りの引数を埋めてメッセージを構築します。
- `(error 'type :initarg value ...)` — 指定したクラス(組み込み、または [`define-condition`](define-condition.md) で定義)のコンディションインスタンスを構築して通知します。メッセージはクラスに `:report` が定義されていればその描画結果、なければ `Condition (type initargs...) was signalled.` 形です。`simple-*` クラスでは指定された `:format-control` がメッセージになります。
- `(error obj)` — 構築済みのコンディションオブジェクト(例: [`make-condition`](make-condition.md) の結果)を通知します。実行時に文字列だった場合はそのままメッセージとして通知します。

すべてのバックエンドがコンディションを送出するため、`handler-case` が型でディスパッチできます: インタプリタと JVM はメッセージとコンディションオブジェクトを保持する例外をスローし、wasm-GC バックエンドはプログラムが捕捉フォーム(`handler-case`/`ignore-errors`/`unwind-protect`)を含む場合に WebAssembly 例外をスローし(出力モジュールには `wasmtime -W exceptions=y` が必要)、含まない場合はトラップします。`format` と同様に `error` は関数値を持たないマクロなので、`#'error` はサポートされません。

捕捉されない `error` は実行を中止するため、ここでは実行可能な例ではなく静的に示します:

```console
(error "bad value: ~a" x)
(error 'type-error :datum x :expected-type 'integer)
```
