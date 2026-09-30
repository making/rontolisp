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
`hash-map`/`array-map`、`subs`、`println`/`print`/`pr`/`prn`/`str`/`pr-str`、`comment`、
`try`/`catch`/`finally`/`throw`、`ex-info`/`ex-data`/`ex-message`、`atom`/`deref`/
`swap!`/`reset!`/`compare-and-set!`（および `volatile!`/`vswap!`/`vreset!`）、階層付きの
`defmulti`/`defmethod`/`remove-method`/`get-method`（`derive`/`underive`/`isa?`/
`parents`/`ancestors`/`descendants`/`make-hierarchy`/`prefer-method`）、`memfn` と
`proxy`、`clojure.string` 付きの `ns`（`join`/
`split`/`split-lines`/`upper-case`/`lower-case`/`capitalize`/`trim`/`triml`/
`trimr`/`trim-newline`/`blank?`/`starts-with?`/`ends-with?`/`includes?`/
`index-of`/`last-index-of`/`replace`/`replace-first`/`escape`/
`re-quote-replacement`/`reverse`）、Java interop（`.`、`..`、`Class/member`、
`Class.`、`new`）--
を読み込み、すべてのバックエンドで実行できます。部分的な準拠が設計であり、サブセットや
互換性の約束はまだありません。JVM や WebAssembly で Clojure プログラムを試すために
使い、維持が必要なものは Common Lisp で書いてください。interop はインタプリタと
JVM でのみ動作します。wasm バックエンドはそれが低下する `java:` 表面を拒否します。

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
`setq` ですが、引数・`let`/`loop` 束縛・`def` で定義した変数の先頭位置の呼び出しは
値セルの `funcall` です（`(defn call-it [f x] (f x))` が動きます）、`fn` と `#(...)` は `lambda`（`#(...)` の引数は1つの rest リストで運ばれる）、
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
です。`inc`/`dec`/`str`/`pr-str` と seq 動詞（`seq`/`first`/`rest`/`cons`/`count`/`map`/
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
`doseq`/`dotimes`/`for` は名前付きで拒否されます。プロトコル（`defprotocol`、
`defrecord`、`deftype`、`reify`、`extend-protocol` と仲間、`gen-class`）、`set!`、
正規表現リテラル（`#"..."`）、バッククォート、`var`/`#'`、メタデータ（`^`）も同様です。

## 状態・エラー・ディスパッチ・名前空間・interop

atom はタグ付きセル `(:C%ATOM #(value))` で、すべての動詞がそれを読み書きします。
`(atom 1)` が作り、`@a` と `(deref a)` が読み、`(swap! a f x...)` は値と追加引数に
`f` を適用して答えを格納し、`(reset! a v)` は `v` を格納し、
`(compare-and-set! a old new)` は値が `old` と `eql` のときに限り `new` を格納します
（数値は値比較、それ以外は同一性）。それぞれ新しい値を答え（比較は真偽値を答え）、
いずれも関数値として動作するため、`(map deref atoms)` が動きます。

```clojure
(def a (atom 1))
(println @a)                 ; 1
(println (swap! a + 10 20))  ; 31
(println (reset! a 2))       ; 2
(println (compare-and-set! a 2 3)) ; true
```

`try` は `unwind-protect` 内の `handler-case` を守ります。`(try body...
(catch Class var body...)... (finally ...))` です。すべての catch 節は
catch-all の `error` 節に答えます -- クラスは区別されないため、最初の節があらゆる
コンディションを処理します -- catch 変数は Common Lisp のコンディションを束縛します。
`throw` は `error` 経由でシグナルします。`ex-info` 値は自身のコンディションとして
シグナルされ（データを運びます）、それ以外は Clojure 記法で描画されるため、
投げた文字列はメッセージを保ちます。

`(ex-info message data)` はメッセージとデータのスロットを持つコンディションを作ります
（レポートはメッセージを印字するため、捕捉されなかったものはすべてのバックエンドで
同じに読めます）。`(ex-data e)` はマップを答え（他のコンディションには `nil`）、
`(ex-message e)` はメッセージを答えます（それ以外は Clojure 記法で描画されます）。
いずれも関数値として動作するため、`(map ex-data xs)` が動きます。

```clojure
(println (try (throw (ex-info "boom" {:code 42}))
              (catch Exception e (get (ex-data e) :code)))) ; 42
```

マルチメソッドはメソッドテーブルとディスパッチ `defun` です。`(defmulti name
docstring? dispatch-fn :default default?)` が両方を作り（デフォルトのディスパッチ値は
`:default`）、`(defmethod name value [params...] body...)` がメソッドを格納し、
`(remove-method name value)` が削除し、`(get-method name value)` が読みます。
デフォルトにもメソッドがないミスはシグナルします。

階層はディスパッチを広げます。`(derive child parent)` と `(underive child
parent)` はグローバル階層を書き換え（`nil` を答えます）、`(derive h child
parent)` と `(underive h child parent)` は更新された階層値を答えます
（`(make-hierarchy)` が空のものを作ります）。`(isa? child parent)`（階層を先頭に
置く形も可）は `true`/`false` を答え、`(parents child)`、`(ancestors child)`、
`(descendants child)` はセットを答えます。マルチメソッドは `defmulti` が
`:hierarchy` を付けない限りグローバル階層でディスパッチします（`:hierarchy` は
ディスパッチごとに評価される式なので、`def` した var を束縛し直すと効きます）。
ミスではディスパッチ値が派生するすべてのメソッドが候補になり、厳密に最も具体的な
ものが勝ち、`(prefer-method name x y)` が残りの同点を `x` 寄りに解消し、解消できない
同点はシグナルします。

```clojure
(derive :circle :shape)
(defmulti area :shape)
(defmethod area :shape [m] 0)
(defmethod area :circle [m] 1)
(println (area {:shape :circle})) ; 1
```

`(ns name (:require [clojure.string :as s :refer [join]]) (:use ...) (:import ...))`
は節を結線し、何も定義しません。`:as` は別名、`:refer`/`:use` は非修飾名、
`:import` は interop 用のクラス名を登録し、`(:refer-clojure :only/ :exclude ...)`
は見えるコアを狭めます。未知の名前空間の require はエラーです。トップレベルの
`require`/`use`/`import` も同じことをして `nil` を答えます。

```clojure
(ns demo (:require [clojure.string :as s]))
(println (s/join "," ["a" "b"])) ; a,b
(println (s/upper-case "hi"))    ; HI
```

interop は `java:` 表面に低下します（`.kb/java-interop.md`）。`(. obj method
args...)` と `(.method obj args...)` はインスタンスメソッド、`(. Class method
args...)` と `(Class/static args...)` は static、`（Class. args...)` と
`(new Class args...)` は構築、`(Class/FIELD)` は static フィールドの読み出し
（引数なし static メソッドは `(. Class method)` と書きます）、`(.-field obj)` は
インスタンスフィールド、`(.. obj (step args...) name...)` は入れ子です。クラス名は
ドット付きならそのまま、`:import` 経由、または `java.lang` で解決されます。文字列
レシーバは対応するコア操作に答え（Lisp 文字列はホストオブジェクトではありません）、
それ以外は直接 `java:call` に行きます。`(memfn name args...)` は対象と名前付き引数
の関数で、そのメソッドを呼び出します（同じ経路のため、`((memfn toUpperCase) "hi")`
はすべてのバックエンドで `"HI"` を答えます）。`(proxy [interface] []
(method [params...] body...)...)` は `java:proxy`（名前で振り分ける lambda）経由で
単一インターフェースを実装します。スーパークラス・コンストラクタ引数はなく、
メソッドは Java の引数のみを取ります（`this` はありません）。

```clojure
(println (.toUpperCase "hi"))    ; HI
(println (Integer/parseInt "42")) ; 42
(println ((memfn toUpperCase) "hi")) ; HI
```

文字は文字として読まれます（`\a`、小文字の `newline`/`space`/`tab`/
`return`/`backspace`/`formfeed` 名、`\uXXXX`、`\oNNN` -- それ以外は
`Unsupported character` 拒否）。整数は基数付きで読まれます（`0xFF`、`2r101`、
`8r17`、先行 `0` の8進数。形は数だが解析できないものは `Invalid
number` 拒否）。`1M` は正確な比に低下します（`0.1M` は `1/10` であり、10進演算は
正確なまま比として印字されます）。`long` を超える `2N` は bignum です。いずれも
印なしで印字されます。

## REPL

ファイルなしで `--source-language clojure` を付けると Clojure REPL（`clojure> `
プロンプト）が起動します。フォームは複数行にわたれます。別々のプロンプトで入力した
定義は、1つのファイルにあるかのように互いを参照できます。

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
twice
clojure> (twice 21)
42
```

## 逸脱

`false` は `nil` とは別のオブジェクトです（どちらも偽値なので `if`/`when`/`cond`/
`and`/`or`/`not` は同じく扱いますが、`=` や `nil?` は区別します）。`false?`/
`true?`/`boolean?` もそれに応じて答えます。`println`/`print` は3つの値を
`true`/`false`/`nil` と表記し、`str` は `true`/`false`/`""` と表記します。
`println`/`print` の各部分は1つの空白で区切られ（Clojure と同じ）、`str`
は区切りなしで連結されます。`pr`/`prn`/`pr-str` は読み戻し可能な側面で、文字列は
引用符付きで印字されます（`pr-str` は `pr` と同じく各部分を1つの空白で区切ります）。
print 系はオラクルと同じく `nil` を答えます。キーワードはコロン付きで印字され
（`:a`）、大文字小文字を保持します。コレクションは Clojure 記法で印字されます
（`[1 :a s]`、`{:a 1}`、`#{1}`、`(true false nil :k)`）。クォートされたシンボルは
`c%` の後ろから demangle されるため、`'e2e-foo` は `e2e-foo` と印字されます。
`nil` は `nil` のまま（`()` にはなりません）で、マップ/セットの走査順は未規定のまま
（`keys`/`vals` と同じ）なので、確定的に印字できるのは単一エントリのマップと単一
メンバーのセットだけです。循環を閉じる値は datum label 付きで印字されます
（`#0=(1 . #0#)`、Scheme の `write` と同様）。循環のない共有は2回印字されます。
`*print-length*`/`*print-level*` は尊重されず（経由した `println` が元から
`%print-cased` を通っていません）、Clojure 値への `~S`/`~A` は Common Lisp 記法の
ままです（`format` は CL の表面です）。`print-method`/`pprint` はありません。
atom は読み戻し不能な形（`#<Atom value>`）で印字され、関数は `#<procedure>` です。
ベクターとテーブルのキーは同一性で
比較されるため、オラクルが答えるベクターキーの参照は外れます。セットリテラルの
重複要素は綴りで拒否されます。seq 系はすべてのコレクションに対する strict な
リストビューで動作します（リストはそのまま渡されます。空の結果は `nil` で、
オラクルの `()` とは異なります。範囲外の `nth` は投げる代わりにデフォルトを
答えます。マップ/セットの seq 順はテーブルの走査順で未規定です。文字列は文字に
seq され Common Lisp 記法で印字されます）。`doseq`/`dotimes`/`for` の内包と
ループ、プロトコル、`set!`、正規表現リテラル、バッククォート、
`var`、メタデータは
ありません。`ex-info` 値は他のコンディションと同様にコンディションオブジェクト
（`#<C%E-EX-INFO ...>`）として印字されます。catch 節は順に catch-all です（最初があらゆるコンディションを処理します）。
マルチメソッドのディスパッチ値は `equal` テーブルのキーのように比較されます
（ベクターは同一性）。階層の `parents`/`ancestors`/`descendants` はセットを答えます
（`#{...}` で印字されます）。階層経由のディスパッチは厳密に最も
具体的なメソッドを選び、次に `prefer-method` の選択を尊重します。
`split`/`replace` はリテラル文字列にマッチし、パターンではありません（正規表現ランタイムはなく、`#"..."` は拒否されたままです）。`indexOf` は
見つからないとき `nil` ではなくオラクルと同じ `-1` を答えます。ボディ内の `def` はボディの実行時にグローバルを設定します。ボディ内の
`defn` は文の位置でのみ動作します（複数アリティはトップレベルのみ）。`cond` は
寛容な読みを保ちます。末尾の奇数アームはデフォルトであり、Clojure がシグナルする
箇所です。コレクションリテラルをまたぐスレッディングの段階はシグナルします
（ここではコレクションは関数ではありません）。 lowering エラーは最も内側のフォームの位置を名指しします（ファイルが
既知の場合は `file:line:column`）。
