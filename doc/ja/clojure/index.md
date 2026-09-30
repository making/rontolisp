# Clojure (experimental)

**Experimental.** rontolisp は Clojure の小さなサブセット -- `def`/`defn`（単一・多
アリティ）と `declare`、`fn`（名前付き、多アリティ）と `#(...)`、`let`/`loop`/
`recur`（シーケンシャル・マップの分割束縛付き）、`if`/`when`/`cond`/`do`/`and`/
`or`、スレッディング（`->`/`->>`/`as->`/`doto`/`cond->`/`cond->>`/`some->`/
`some->>`）、`first`/`rest`/
`next`/`seq`/`cons`/`list*`/`map`/`filter`/`reduce`/`apply`/`concat`、省略可能なデフォルト付き
`nth` と end 付き `take`/`drop`/`range`、ベクター・キーワード・マップ・セットの
リテラル、
`assoc`/`dissoc`/`get`/`contains?`/`keys`/`vals`/`merge`/`conj`/`disj`/`set`/
`hash-map`/`array-map`、`println`/`print`/`pr`/`prn`/`str` --
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
保たれます）、セットリテラルは各メンバーを自分自身の下に格納した同じテーブル、seq
は任意のコレクションに対する strict なリストビューです（`first`/`rest`/`next`/
`seq`/`cons`/`concat`/`map`/`filter`/`reduce`/`apply`/`nth`/`take`/`drop`
はすべてそれを経由します -- リストはそのまま、ベクターと文字列は coerce され、
マップはエントリごとに1つの2要素ベクター、セットは要素ごとに1つのメンバーを
寄与します）。キーワードは綴りを `(:C%KEYWORD name)` で包んだもので、
大文字小文字を保持するため、`:a` と `:A` は区別されます。コロン付きで印字され、
呼び出し位置（`(:k m)`、省略可能なデフォルト付き）ではマップの参照になります。
end 付き `range` は strict なリストを作ります。end のない `range`
や `lazy-seq`（`cycle`/`repeat`/`repeatedly`/`iterate` とともに）は拒否されます --
ここに遅延 seq はありません。値位置の `nth`/`quot` は Clojure の引数順の lambda
です。`inc`/`dec`/`str` と seq 動詞（`seq`/`first`/`rest`/`cons`/`count`/`map`/
`filter`/`reduce`/`concat`/`take`/`drop`/`range`）も同様に値位置の lambda なので、
高階呼び出しはそれらを裸で取れます。`apply` は先行引数を seq 化した末尾引数の上に
展開します。`count`/`empty?`/`=` はマップとセットに届きます。`get`
は省略可能なデフォルトを取ります。transient（`assoc!` など）は拒否されます。
`::` 自動解決は拒否されます。名前空間付きの `:a/b` は不透明なデータとして全体で
印字・比較されます。

複数のアリティを持つ `defn` はアリティごとの `defun` 1つと引数の個数で選ぶ
ディスパッチ `defun` です（単一の可変長節は固定引数を超える任意の個数を取ります）。
それ以外の個数での呼び出しはシグナルします。複数アリティの `fn` は同じく
ディスパッチする1つの `lambda` で、名前付き `fn` は自己呼び出しのために自分自身を
束縛します。`declare` は後で定義されるものを宣言します。ベクターの束縛パターンは
seq ビューを経由して位置で束縛され（`&` は残りを seq として、`:as` は全体を）、
マップのパターンはテーブル対応の読み出しを経由します（`:keys`/`:syms`/
`:strs`、明示的なローカル、`:as`、`:or` デフォルト）-- `let`、`loop`、
`fn`/`defn` の引数いずれでもです。`->`/`->>` は値を2番目/最後に挿入し、`as->`
は名前を段階的に束縛し直し、`doto` は（不変の）対象を答え、`cond->`/`cond->>`
は真値のテストでのみスレッドし、`some->`/`some->>` は `nil` で止まります（`false`
では止まりません）。`list*` は seq ビュー上の `cons` の右畳み込みです。
`doseq`/`dotimes`/`for` と `defmulti`/`defmethod` は名前付きで拒否されます。

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
`true`/`false`/`nil` と表記し、`str` は `true`/`false`/`""` と表記します。
`println`/`print` の各部分は1つの空白で区切られ（Clojure と同じ）、`str`
は区切りなしで連結されます。`pr`/`prn` は読み戻し可能な側面で、文字列は引用符付きで
印字されます。キーワードはコロン付きで印字され（`:a`）、
大文字小文字を保持します。コレクションは Common Lisp 記法で印字されます
（`[:a :b]` に対して `#((C%KEYWORD a) (C%KEYWORD b))`、入れ子の `true`/`nil` は
`T`/`NIL`）。コレクションに入れ子になったキーワードは `(:C%KEYWORD name)`
ラッパーで表示されます（セットがラッパーで表示されるのと同様）。マップは
`#<HASH-TABLE :TEST EQUAL :COUNT n>` と印字され、セットは
`(C%SET #<HASH-TABLE ...>)` と印字されます。ベクターとテーブルのキーは同一性で
比較されるため、オラクルが答えるベクターキーの参照は外れます。セットリテラルの
重複要素は綴りで拒否されます。seq 系はすべてのコレクションに対する strict な
リストビューで動作します（リストはそのまま渡されます。空の結果は `nil` で、
オラクルの `()` とは異なります。範囲外の `nth` は投げる代わりにデフォルトを
答えます。マップ/セットの seq 順はテーブルの走査順で未規定です。文字列は文字に
seq され Common Lisp 記法で印字されます）。`doseq`/`dotimes`/`for` の内包と
ループ、`defmulti`/`defmethod`、`atom`、遅延 seq、メタデータ、`var` は
ありません。ボディ内の `def` はボディの実行時にグローバルを設定します。ボディ内の
`defn` は文の位置でのみ動作します（複数アリティはトップレベルのみ）。`cond` は
寛容な読みを保ちます。末尾の奇数アームはデフォルトであり、Clojure がシグナルする
箇所です。コレクションリテラルをまたぐスレッディングの段階はシグナルします
（ここではコレクションは関数ではありません）。 lowering エラーは最も内側のフォームの位置を名指しします（ファイルが
既知の場合は `file:line:column`）。
