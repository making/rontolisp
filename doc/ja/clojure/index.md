# Clojure (experimental)

**Experimental.** rontolisp は Clojure の小さなサブセット -- `def`/`defn`、`fn` と
`#(...)`、`let`/`loop`/`recur`、`if`/`when`/`cond`/`do`/`and`/`or`、`map`/`filter`/
`reduce`/`apply`/`concat`、ベクターとキーワードリテラル、`println`/`print`/`str` --
を読み込み、すべてのバックエンドで実行できます。部分的な準拠が設計であり、サブセットや
互換性の約束はまだありません。JVM や WebAssembly で Clojure プログラムを試すために
使い、維持が必要なものは Common Lisp で書いてください。

`.clj` ファイルは Clojure として読まれます。`--source-language clojure` は他の拡張子の
ファイルにもそう指示します。言語はファイルごとに選ばれるため、2つの言語を混在できます。

```bash
rontolisp hello.clj                                # インタプリタ
rontolisp hello.clj -o Hello.class && java Hello   # JVM
rontolisp hello.clj -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.clj -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language clojure       # 任意の拡張子
```

`--no-gc` は拒否されます。そのバックエンドにはペアもシンボルもクロージャもありません。

```clojure
(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))

(println (fact 10))
(println (reduce + 0 (map #(* % %) (filter odd? '(1 2 3 4 5)))))
```

## 低下対応

すべての識別子は `c%` の背後にマングルされるため、Clojure の名前がコアフォームや
組み込みと衝突しません。`defn` は `defun`（直接呼び出し）、`def` はトップレベルの
`setq`、`fn` と `#(...)` は `lambda`（`#(...)` の引数は1つの rest リストで運ばれる）、
`let` は `let*`、`loop`/`recur` は `labels` の自己呼び出し、ベクターリテラルは
`vector` 呼び出し、seq 関数はリスト操作です。

## REPL

ファイルなしで `--source-language clojure` を付けると Clojure REPL（`clojure> `
プロンプト）が起動します。フォームは複数行にわたれます。別々のプロンプトで入力した
定義は、1つのファイルにあるかのように互いを参照できます。

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
clojure> (twice 21)
42
```

## 逸脱

`false` は `nil` に畳み込まれます（どちらも偽）。キーワードは大文字で印字され
（`:a` に対して `:A`）、大文字小文字を区別せず衝突します。マップとセットのリテラルは
拒否されます。seq 系はリストに対してのみ動作します。`println` は各部分を区切りなしで
連結し、コレクションは Common Lisp 記法で印字されます（`[:a :b]` に対して
`#(A B)`）。分割束縛、スレッディングマクロ、`atom`、遅延 seq、メタデータ、`var` は
ありません。エラーにはまだソース位置が付きません。
