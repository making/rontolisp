# コマンドラインオプション

`rontolisp --help` はすべてのオプションを、所属ごとにグループに分けて説明します。グループは、
どの実行でも使える共通オプション、フロントエンド (ソース言語。ファイルの拡張子か
`--source-language` で決まる) ごとのオプション、バックエンド (`-o` で決まる) ごとの
オプションです。

```bash
rontolisp --help                              # すべてのグループ (--help=all と同じ)
rontolisp --help=frontend:java,backend:wasm   # 共通オプション、Java、WASM
```

`--help=` にはグループ名をカンマ区切りで渡します。共通オプションは常に表示されます。
存在しないグループ名は、存在するグループ名の一覧とともに拒否されます。表示する複数の
グループに属するオプションは、そのうち最初のグループで説明し、残りのグループでは末尾で
名前だけを挙げます (`See backend:jvm for --dynamic, --optimize, ...`)。値は
`--optimize=size` と同じく `=` でつなげて書きます。

どのグループにもないオプションは無視されず、拒否されます
(`unknown option '--simdd' (try: rontolisp --help)`)。サブコマンド `format`、`test`、
`build`、`notices` には、それぞれ専用のヘルプがあります (`rontolisp test --help`)。

## グループ

| グループ | オプション |
|---|---|
| `common` | `-h`, `-v`, `-e`, `-o`, `--source-language`, `--dump-ir` |
| `frontend:common-lisp` | `--system-path`, `--dist`, `--feature`, `--swank` |
| `frontend:clojure` | `-A`, `-M`, `-X`, `--nrepl`, `--java-update-snapshots` |
| `frontend:scheme` | `--scheme-standard` |
| `frontend:starlark` | `--starlark-standard` |
| `frontend:java` | `--java-source-path`, `--java-source-dep`, `--java-metadata-repository`, `--java-repository`, `--java-snapshots`, `--java-update-snapshots`, `--wasi-logging` |
| `backend:interpreter` | `--stack`, `--buffered-output`, `--simd`, `--parallel`, `--blas`, `--gpu`, `--java-classpath`, `--java-dep`, `--warn-java-reflection`, `--swank`, `--nrepl`, `--java-repository`, `--java-snapshots`, `--java-update-snapshots` |
| `backend:jvm` | `--class-name`, `--no-main`, `--maven-coordinates`, `--emit-pom`, `--java-release`, `--java-static`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--simd`, `--parallel`, `--blas`, `--gpu`, `--java-classpath`, `--java-dep`, `--warn-java-reflection`, `--java-repository`, `--java-snapshots`, `--java-update-snapshots` |
| `backend:wasm` | `--no-gc`, `--no-wasi`, `--host-random`, `--host-output`, `--host-fetch`, `--host-sockets`, `--host-timer`, `--host-boundary`, `--reentrant`, `--emit-js-glue`, `--emit-worker-entry`, `--report-locations`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--simd`, `--wasi-logging` |
| `backend:component` | `--component`, `--emit-wit`, `--scaffold-wit`, `--world`, `--no-gc`, `--no-wasi`, `--report-locations`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--wasi-logging` |
| `backend:native` | `--native`, `--native-target`, `--native-cpu`, `--report-locations`, `--dynamic`, `--optimize`, `--no-prune`, `--warnings-as-errors`, `--simd` |

バックエンドは次のとおりです。`interpreter` は `rontolisp` のプロセス内でプログラムを実行します
(`-o` なし)。`jvm` は `-o OUT.class`、`OUT.jar`、`OUT.war` に、`wasm` は `-o OUT.wasm` に、
`component` は `-o OUT.wasm --component` に、`native` は `--native -o OUT` で実行ファイルに
コンパイルします。
