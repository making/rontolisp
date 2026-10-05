# エラー

try は unwind-protect の中の handler-case です。catch 節は自分が名指すクラスかそのサブクラスの例外を節の順に捕捉し、catch 変数はその例外を束縛します。実行時エラーとランタイムの拒否は、オラクルがその箇所で投げるクラスとして捕捉されます（[try](try.md)）。例外はどのバックエンドでもコンディションです。`ex-info`、メッセージと cause だけを持つ throwable クラスの構築（`(Exception. "m")`、`(IllegalArgumentException. "m" cause)`）、実行時エラーの Common Lisp コンディションのいずれかです。インタプリタと JVM では、Java のメンバが投げた例外と throw されたホストの `Throwable` はホスト自身のオブジェクトで、catch はそれを束縛します。

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