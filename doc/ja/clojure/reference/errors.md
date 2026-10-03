# エラー

try は unwind-protect の中の handler-case です。catch 節はすべて catch-all で -- クラスは区別されず、最初の節がどの条件も扱い、catch 変数は例外を束縛します。例外はどのバックエンドでもコンディションです。`ex-info`、メッセージと cause だけを持つ throwable クラスの構築（`(Exception. "m")`、`(IllegalArgumentException. "m" cause)`）、throw されたホストの `Throwable`、実行時エラーの Common Lisp コンディションのいずれかです。

| Name | Example | Result |
|---|---|---|
| `try` | `(try 1 (catch Exception e 2) (finally nil))` | `1` |
| `throw` | `(try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))` | `42` |
| `ex-info` | `(ex-message (ex-info "boom" {}))` | `boom` |
| `ex-data` | `(ex-data (ex-info "boom" {:code 42}))` | `{:code 42}` |
| `ex-message` | `(ex-message (ex-info "boom" {}))` | `boom` |
| `ex-cause` | `(ex-message (ex-cause (ex-info "a" {} (Exception. "c"))))` | `c` |
| `.getMessage` | `(.getMessage (Exception. "boom"))` | `boom` |
| `assert` | `(assert (= 1 1))` | `nil` |