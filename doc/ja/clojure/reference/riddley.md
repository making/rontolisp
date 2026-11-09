# riddley

[riddley](https://github.com/clj-commons/riddley) は、特殊形式とそれが束縛するローカルを区別して
コードを走査するライブラリです。specter の `path` や potemkin の型定義は、渡されたコードを riddley で
走査します。riddley の jar 自体は Clojure コンパイラの内部（`Compiler/LOCAL_ENV` と、コンパイラの
`LocalBinding` を組み立てる Java クラス）を外から操作するもので、rontolisp にはその内部がありません。
そこで `riddley.walk` と `riddley.compiler` を組み込みで提供します。riddley について文書化された
振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、プロジェクトのファイルと同じ手順で読み込み、
すべてのバックエンドで同じように動きます。`deps.edn` では、Maven バージョンが 0.2.2 以下の
`riddley/riddley` がこの組み込み版を指し、何も取得しません（specter が指定するのは 0.1.12 です）。
それより新しいバージョンを選んでいると、riddley の名前空間を読み込む時点でエラーになります。

| var | 振る舞い |
|---|---|
| `riddley.walk/macroexpand-all` | `(macroexpand-all form)`: `form` に含まれるすべてのマクロ呼び出しを、呼び出し位置で見えるローカルを `&env` として展開する |
| `riddley.walk/walk-exprs` | `(walk-exprs predicate handler form)`、`(walk-exprs predicate handler special-form? form)`: `form` をコードとして走査し、マクロ呼び出しをすべて展開する。`predicate` が選んだ式は `handler` の返す値に置き換え、その中へは降りない。それ以外の式は、走査したオペランドから組み立て直す |
| `riddley.walk/macroexpand` | `(macroexpand form)`、`(macroexpand form special-form?)`: 先頭がマクロを指すあいだ `form` を展開する。先頭が見えているローカルを指す場合と、`special-form?` が選ぶ場合は、書かれたまま残す |
| `riddley.walk/special-form?` | シンボルが、`walk-exprs` が走査した特殊形式の先頭かどうか |
| `riddley.compiler/locals` | 見えているローカル。`&env` と同じマップで、走査の外では、プログラムの実行中と、呼び出し位置にローカルがないマクロ本体の中で `nil` |
| `riddley.compiler/with-base-env`、`with-lexical-scoping`、`register-local`、`register-arg` | 走査を始めるスコープ、独立したスコープ、いちばん内側のスコープへのローカルや引数の登録 |
| `riddley.compiler/tag-of` | フォームの `:tag` メタデータが指すクラスの名前のシンボル |

```clojure
(require '[riddley.walk :as rw] '[riddley.compiler :as rc])
(rw/macroexpand-all '(when a (-> b c)))
; => (if a (do (c b)))
(rw/walk-exprs symbol? #(if (contains? (rc/locals) %) (list 'local %) %) '(let [x 1] (list x y)))
; => (let* [x 1] (list (local x) y))
(defmacro env-keys [] `'~(vec (sort (map str (keys &env)))))
(defmacro walked [form] (rw/macroexpand-all form))
(let [outer 1] (walked (let [inner 2] (env-keys))))
; => ["inner" "outer"]
```

マクロ本体の [macroexpand-1](macroexpand-1.md)、`macroexpand`、`clojure.walk/macroexpand-all` も、
呼び出し位置のローカルを `&env` として渡します。riddley がそれに加えるのは、走査するフォームの中で
束縛されるローカルです。上の最後の呼び出しを `clojure.walk/macroexpand-all` で展開すると、
結果は `["outer"]` になります。

## 違い

- インライン展開を持つ関数はありません（[definline](definline.md) は通常の関数を定義します）。
  そのため `(+ n 1)` のような呼び出しは呼び出しのまま残り、riddley が展開する
  `(. clojure.lang.Numbers (add n 1))` にはなりません。
- `locals` の各ローカルの値は `&env` と同じくそのシンボルで、riddley が持つコンパイラの
  `LocalBinding` ではありません。マップのキーになったシンボルはメタデータを持ちません
  （[仕様との差異](../deviations.md)）。
- `walk-exprs` は、riddley と同じく、`predicate` が選んだ `(var x)` フォームを評価します。
  そこまで実行が届くプログラムは、JVM ではコンパイラを抱えたクラスになり（[eval](eval.md)）、
  WebAssembly では実行時にエラーになります。
