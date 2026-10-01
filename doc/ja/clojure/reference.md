# リファレンス

Clojure フロントエンドが提供する名前ごとの 1 ページです。実験的サブセットのフォーム・操作・
interop エントリのすべてを、分野ごとにまとめています。**表の各名前は専用のページにリンクして
おり**、そこにシグネチャ、動作、オラクルとの差異（あれば）、動作例があります。拒否される
フォームはここには載せません -- [セマンティクス](semantics.md)にあります。

| ページ | 内容 |
|---|---|
| [構文と定義](reference/syntax.md) | `def`/`defn`/`fn`、束縛、条件分岐、`quote`、`comment`、`declare` |
| [スレッディング](reference/threading.md) | `->`、`->>`、`as->`、`doto` と条件付きスレッダー |
| [seq](reference/seqs.md) | すべてのコレクションの strict なリストビュー上の seq 群 |
| [反復](reference/iteration.md) | `doseq`/`dotimes`/`for` と strict な `dorun`/`doall` |
| [マップ・セット・ベクター](reference/collections.md) | `equal` ハッシュテーブル上の永続コレクション操作 |
| [数値と述語](reference/numbers.md) | 算術、比較、型述語 |
| [状態](reference/state.md) | `atom`/`deref`/`swap!` と volatile 三兄弟 |
| [マルチメソッドと階層](reference/multimethods.md) | `defmulti`/`defmethod`、`derive` と階層参照 |
| [エラー](reference/errors.md) | `try`/`catch`/`finally`、`throw`、`ex-info` とその参照 |
| [名前空間](reference/namespaces.md) | `ns` とトップレベル `require`/`use`/`import` |
| [(clojure.string)](reference/string.md) | 文字列ライブラリと核の `subs` |
| [Java interop](reference/interop.md) | `.`、`..`、構築、`memfn`、`proxy` |
