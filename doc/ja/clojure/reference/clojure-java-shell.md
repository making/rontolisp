# clojure.java.shell

プロセスを実行し、その標準入力、出力、エラー出力、終了コードを扱う名前空間です。
`clojure.java.shell` を require すると使えます。Clojure の同名の名前空間について文書化された
振る舞いをもとに、ホストの `ProcessBuilder` の上に rontolisp 向けに書いた Clojure ソースで、
インタープリターと JVM で動きます。WebAssembly のターゲットには起動するプロセスがないため、
そこではプログラムを変換する時点で `require` を拒否します。

| var | 振る舞い |
|---|---|
| `sh` | `(sh & args)`: 先頭の文字列が示すコマンドを実行し、`{:exit code :out text :err text}` を返す。文字列に続くオプションは、`:in` が標準入力にする文字列、File、リーダー、`:in-enc` が `:in` のテキストの文字セット（UTF-8）、`:out-enc` が `:out` の文字セット（UTF-8）、`:dir` が実行するディレクトリ、`:env` が環境を置き換えるマップ。`:err` はプラットフォームの文字セットで復号する |
| `with-sh-dir` | `(with-sh-dir dir & forms)`: `*sh-dir*` を `dir` に束縛して `forms` を評価する |
| `with-sh-env` | `(with-sh-env env & forms)`: `*sh-env*` を `env` に束縛して `forms` を評価する |
| `*sh-dir*`、`*sh-env*` | `sh` に `:dir` と `:env` がないときの値。`nil` なら現在のもの |

```console
clojure> (require '[clojure.java.shell :refer [sh]])
nil
clojure> (sh "echo" "hello")
{:exit 0, :out "hello\n", :err ""}
clojure> (sh "cat" :in "one\ntwo\n")
{:exit 0, :out "one\ntwo\n", :err ""}
clojure> (:out (sh "pwd" :dir "/tmp"))
"/tmp\n"
```

## 違い

- `:out-enc :bytes` は拒否します。バイト配列がないためです。
- `:in` は文字列、File、リーダーを受け取り、ホストの `InputStream` やバイト配列は受け取りません。
  `:env` はマップを受け取り、`String[]` は受け取りません。
