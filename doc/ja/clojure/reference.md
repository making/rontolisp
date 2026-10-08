# リファレンス

Clojure フロントエンドが提供する名前ごとの 1 ページです。実験的サブセットのフォーム・操作・
interop エントリのすべてを、分野ごとにまとめています。**表の各名前は専用のページにリンクして
おり**、そこにシグネチャ、動作、オラクルとの差異（あれば）、動作例があります。拒否される
フォームはここには載せません -- [セマンティクス](semantics.md)にあります。

| ページ | 内容 |
|---|---|
| [構文と定義](reference/syntax.md) | `def`/`defn`/`fn`、束縛、条件分岐、`quote`、`comment`、`declare` |
| [スレッディング](reference/threading.md) | `->`、`->>`、`as->`、`doto` と条件付きスレッダー |
| [seq](reference/seqs.md) | 取れば lazy、そうでなければ strict な seq 群 |
| [反復](reference/iteration.md) | `doseq`/`dotimes`/`for`、`dorun`/`doall`、`run!` |
| [マップ・セット・ベクター](reference/collections.md) | `equal` ハッシュテーブル上の永続コレクション操作 |
| [高階関数](reference/higher-order.md) | `comp`・`partial`・`complement`・`constantly`・`identity`・`memoize`・`trampoline`、`juxt`・`fnil`・`every-pred`・`some-fn`・`min-key`・`max-key` |
| [数値と述語](reference/numbers.md) | 算術、比較、型述語 |
| [型・コレクション述語](reference/predicates.md) | `seq?`/`map?`/`keyword?`/`int?` などの種類の判定、`identical?`、`distinct?`、`extends?` |
| [状態](reference/state.md) | `atom`/`deref`/`swap!` と volatile 三兄弟 |
| [マルチメソッドと階層](reference/multimethods.md) | `defmulti`/`defmethod`、`derive` と階層参照 |
| [プロトコル、レコード、型](reference/protocols.md) | `defprotocol`・`defrecord`・`deftype`、`reify`、`extend` 系と `satisfies?` |
| [エラー](reference/errors.md) | `try`/`catch`/`finally`、`throw`、`ex-info` とその参照 |
| [名前空間](reference/namespaces.md) | `ns` とトップレベル `require`/`use`/`import` |
| [名前とキーワード](reference/names.md) | `name`/`namespace`/`keyword`/`symbol` |
| [(clojure.string)](reference/string.md) | 文字列ライブラリと核の `subs` |
| [(clojure.set)](reference/clojure-set.md) | 関係演算の集合ライブラリ: `union`/`intersection`/`difference`、`select`/`project`/`rename`、`index`/`join`、`subset?`/`superset?` |
| [clojure.walk](reference/clojure-walk.md) | 入れ子のデータの汎用走査: `walk`/`postwalk`/`prewalk`、`-replace` の組、`keywordize-keys`/`stringify-keys` |
| [正規表現](reference/regex.md) | `re-find`/`re-seq`/`re-matches`、`re-matcher`/`re-groups`、`re-pattern` とパターンの `split`/`replace` |
| [Ring アダプター](reference/ring.md) | `ring.adapter.rontolisp/run-server`: Ring ハンドラをすべてのトランスポートで提供 |
| [Ring ユーティリティ](reference/ring-util.md) | 組み込みの `ring.util.*` と `ring.middleware.*`: レスポンスの組み立て、URL とフォームの符号化、パラメーターのミドルウェア |
| [Java interop](reference/interop.md) | `.`、`..`、構築、`memfn`、`proxy` |
| [トランスデューサー](reference/transducers.md) | `transduce`・`eduction`・`sequence`・`completing`、`reduced` とその仲間、`cat`、seq 関数の1引数形 |
| [入出力](reference/io.md) | `spit`・`slurp`・`line-seq`・`clojure.java.io/reader` と `format` |
| [テスト (clojure.test)](reference/test.md) | `deftest`/`is`/`are`/`testing` と `run-tests` の集計ランナー |
| [マクロ](reference/macros.md) | `defmacro`、syntax-quote、`gensym`、`macroexpand-1`、`macroexpand` |
