# Clojure (experimental)

**Experimental.** rontolisp は Clojure の小さなサブセット -- `def`/`defn`、`fn` と
`#(...)`、`let`/`loop`/`recur`、`if`/`when`/`cond`/`do`/`and`/`or`、`map`/`filter`/
`reduce`/`apply`/`concat`、ベクター・キーワード・マップ・セットのリテラル、
`assoc`/`dissoc`/`get`/`contains?`/`keys`/`vals`/`merge`/`conj`/`disj`/`set`/
`hash-map`/`array-map`、`println`/`print`/`str` --
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
`vector` 呼び出し、マップリテラルは `equal` ハッシュテーブル（インプレースでは
決して変更されません -- すべての動詞が新しいテーブルを作るため、永続性が観測可能に
保たれます）、セットリテラルは各メンバーを自分自身の下に格納した同じテーブル、
seq 関数はリスト操作です。キーワードは綴りを `(:C%KEYWORD name)` で包んだもので、
大文字小文字を保持するため、`:a` と `:A` は区別されます。コロン付きで印字され、
呼び出し位置（`(:k m)`、省略可能なデフォルト付き）ではマップの参照になります。
`count`/`empty?`/`=` はマップとセットに届きます。`get`
は省略可能なデフォルトを取ります。transient（`assoc!` など）は拒否されます。
`::` 自動解決は拒否されます。名前空間付きの `:a/b` は不透明なデータとして全体で
印字・比較されます。

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

`false` は `nil` とは別のオブジェクトです（どちらも偽値なので `if`/`when`/`cond`/
`and`/`or`/`not` は同じく扱いますが、`=` や `nil?` は区別します）。`false?`/
`true?`/`boolean?` もそれに応じて答えます。`println`/`print` は3つの値を
`true`/`false`/`nil` と表記し、`str` は `true`/`false`/`""` と表記しますが、各部分は
相変わらず区切りなしで連結されます。キーワードはコロン付きで印字され（`:a`）、
大文字小文字を保持します。コレクションは Common Lisp 記法で印字されます
（`[:a :b]` に対して `#((C%KEYWORD a) (C%KEYWORD b))`、入れ子の `true`/`nil` は
`T`/`NIL`）。コレクションに入れ子になったキーワードは `(:C%KEYWORD name)`
ラッパーで表示されます（セットがラッパーで表示されるのと同様）。マップは
`#<HASH-TABLE :TEST EQUAL :COUNT n>` と印字され、セットは
`(C%SET #<HASH-TABLE ...>)` と印字されます。ベクターとテーブルのキーは同一性で
比較されるため、オラクルが答えるベクターキーの参照は外れます。セットリテラルの
重複要素は綴りで拒否されます。seq 系はリストに対してのみ動作します。分割束縛、
スレッディングマクロ、`atom`、遅延 seq、メタデータ、`var` は
ありません。エラーにはまだソース位置が付きません。
