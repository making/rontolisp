# エラー

try は unwind-protect の中の handler-case です。catch 節はすべて catch-all で -- クラスは区別されず、最初の節がどの条件も扱い、catch 変数は Common Lisp の条件を束縛します。

| Name | Example | Result |
|---|---|---|
| `try` | `(try 1 (catch Exception e 2) (finally nil))` | `1` |
| `throw` | `(try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))` | `42` |
| `ex-info` | `(ex-message (ex-info "boom" {}))` | `boom` |
| `ex-data` | `(ex-data (ex-info "boom" {:code 42}))` | `{:code 42}` |
| `ex-message` | `(ex-message (ex-info "boom" {}))` | `boom` |