# clojure.main

Clojure の REPL とスクリプト実行のエラーレポートと、それらが共有する補助関数です。`clojure.main` は
Clojure と同じくプログラムより先に読み込まれているため、`clojure.main/demunge` は `require` なしで
使えます。Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた
Clojure ソースで、すべてのバックエンドで同じように動きます。

| var | 振る舞い |
|---|---|
| `ex-triage` | `(ex-triage data)`: `Throwable->map` が返すデータから、エラーのフェーズ、クラス、原因、シンボル、ソース、パス、行、列を `:clojure.error/` のキーで返す |
| `ex-str` | `(ex-str triage)`: エラーのレポート。フェーズと位置を示す 1 行と、それに続く原因 |
| `err->msg` | `(err->msg e)`: `(Throwable->map e)` の `ex-triage` の `ex-str` |
| `repl-caught` | `(repl-caught e)`: `(err->msg e)` を `*err*` に出力する |
| `repl-exception` | `(repl-exception e)`: `e` の根本原因 |
| `root-cause`、`demunge`、`stack-element-str` | [clojure.repl](clojure-repl.md) と同じ |
| `repl-prompt` | `(repl-prompt)`: 現在の名前空間と `=> ` を出力する |
| `repl-requires` | Clojure の REPL が起動時に require する libspec |
| `with-read-known` | `(with-read-known & body)`: `*read-eval*` が `:unknown` なら true にして `body` を評価する |

```clojure
(require '[clojure.main :as m])
(print (m/ex-str (m/ex-triage {:via [{:type 'java.lang.ArithmeticException :message "Divide by zero"}]
                               :trace '[[user$eval1$fn__2 invoke "NO_SOURCE_FILE" 7]]})))
```

```
Execution error (ArithmeticException) at user/eval1$fn (REPL:7).
Divide by zero
```

## 組み込まれていないもの

実行時にはコンパイラが動かないため、`repl`、`main`、`load-script` はプログラムを変換する時点で
名前を挙げて拒否します。REPL を構成する `repl-read`、`renumbering-read`、`skip-whitespace`、
`skip-if-eol`、`with-bindings`、`report-error` も同様です。

## 違い

- ここでの throwable はスタックフレームを持たないため、その triage はシンボル、ソース、行を
  含みません。`err->msg` と `repl-caught` は `(REPL:1)` の位置として報告します。
- `:clojure.error/path` はデータが与えたソースパスそのままです。Clojure は作業ディレクトリの下の
  パスを相対パスにします。
- spec の問題を含む `ex-str` は拒否します。`clojure.spec.alpha` は組み込まれていません。
