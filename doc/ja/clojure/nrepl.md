# nREPL

`rontolisp --nrepl` は Clojure の [REPL](repl.md) を [nREPL](https://nrepl.org) で
提供します。nREPL はエディタ（CIDER、Calva、Conjure）や `clj -M -m nrepl.cmdline --connect`
が話すプロトコルです。サーバは localhost で待ち受け、`--nrepl=PORT` で指定しない限り
空いているポートを使います。起動すると nREPL 自身のサーバと同じ行を出力し（エディタは
ここからポートを読みます）、作業ディレクトリの `.nrepl-port` にポートを書きます。この
ファイルはサーバの終了時に消えます。`deps.edn` のエイリアスは REPL と同じく効きます
（`rontolisp -A:dev --nrepl`）。

```console
$ rontolisp --nrepl
nREPL server started on port 41831 on host localhost - nrepl://localhost:41831
$ clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' -M -m nrepl.cmdline --connect --port 41831
user=> (defn g [] 1)
#'user/g
user=> (defn f [] (g))
#'user/f
user=> (defn g [] 2)
#'user/g
user=> (f)
2
```

サーバは、nREPL 1.3 の既定のハンドラが持つ操作のうちエディタが使うものに、nREPL と同じ
形で答えます:

| 操作 | 応答 |
|---|---|
| `clone`、`close`、`ls-sessions` | セッション |
| `eval` | フォームごとの値と、その時点の名前空間。`*out*` と `*err*` は `out` と `err` の応答になる。失敗したフォームは `err` に報告し、後続のフォームは実行される |
| `load-file` | ファイルの内容を読み込む。ファイルの名前空間はその読み込みだけのもので、最後の値を返す |
| `interrupt` | 実行中の評価は次の関数呼び出しかループの 1 段で止まり、セッションは続く |
| `completions` | 接頭辞を補完する var、名前空間、特殊形式。`-` や `.` で区切った部分ごとに照合する |
| `describe` | 操作とバージョン |

セッションは nREPL と同じく 1 つの実行環境を共有します。あるセッションが定義したものを
別のセッションから呼べます。`*ns*`、`*1`、`*2`、`*3`、`*e` と、`set!` で変えるフラグ
（`*print-length*`、`*warn-on-reflection*`）はセッションごとに持ちます。

答えない操作は、`stdin`（`*in*` を読む評価は入力の終わりを読みます）、`lookup`、
ミドルウェアの操作、cider-nrepl の操作です。エディタはそれぞれの代替手段で動きます。報告の
位置には `eval` のクラス名が入りません（nREPL が `at user/eval1234 (REPL:1)` と書くところは
`at (REPL:1)` です）。
