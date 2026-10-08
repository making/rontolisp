# clojure.stacktrace

スロー可能オブジェクト（throwable）とその原因を出力する名前空間です。`clojure.stacktrace` を require
すると使えます。Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた
Clojure ソースで、すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `root-cause` | `(root-cause tr)`: 原因をたどった最も内側の原因。原因がなければ `tr` 自身 |
| `print-throwable` | `(print-throwable tr)`: `tr` のクラスとメッセージを出力し、続く行に `ex-data` を出力する |
| `print-stack-trace` | `(print-stack-trace tr)`、`(print-stack-trace tr n)`: `print-throwable` に続けてスタックフレーム（`n` があれば先頭の `n` 個）を出力する |
| `print-cause-trace` | `(print-cause-trace tr)`、`(print-cause-trace tr n)`: `tr` と、`Caused by:` に続けて各原因の `print-stack-trace` を出力する |
| `print-trace-element` | `(print-trace-element e)`: スタックトレースの 1 要素。Clojure の関数は `名前空間/名前` |
| `e` | `(e)`: `*e` の根本原因の短いスタックトレース |

```clojure
(require '[clojure.stacktrace :as st])
(def failure (Exception. "outer" (ex-info "boom" {:x 1})))
(ex-message (st/root-cause failure)) ; => "boom"
```

```clojure
(require '[clojure.stacktrace :as st])
(st/print-throwable (ex-info "boom" {:x 1}))
```

```
clojure.lang.ExceptionInfo: boom
{:x 1}
```

## 違い

- ここでのスロー可能オブジェクトはスタックフレームを持たないため、`print-stack-trace`、
  `print-cause-trace`、`e` は、Clojure がそれを作ったコードのフレームを出力するところで
  ` at [empty stack trace]` を出力します。
