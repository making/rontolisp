# Clojure（実験的）

**実験的。** rontolisp は Clojure の小さなサブセットを読みます -- どのバックエンド上でも
Clojure 型のプログラムが動く十分な大きさです。準拠は設計上部分的で、ここにあるものは
サブセットや互換性の保証ではありません。JVM や WebAssembly 上で Clojure 型のプログラムを
試すために使ってください。ずっと動かし続けたいものは Common Lisp で書いてください。
interop はインタプリタと JVM でのみ動きます:wasm バックエッドは低下先の `java:`
サーフェスを拒否します。

`.clj` と `.cljc` のファイルは Clojure として読まれます（`.cljc` はリーダ条件も読みます）。それ以外の拡張子では
`--source-language clojure` がその旨を示します。言語はファイルごとに選ばれるので、
1 つのプログラムで両方を混在できます。

```bash
rontolisp hello.clj                                # インタプリタ
rontolisp hello.clj -o Hello.class && java Hello   # JVM
rontolisp hello.clj -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.clj -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language clojure       # 任意の拡張子
```

`deps.edn` のプロジェクトは `clj` と同じように実行できます。`rontolisp -M:dev -m my.app args`、
`rontolisp -X:build`、テストは `rontolisp test` です（[セマンティクス](semantics.md)）。

`--no-gc` は名前で拒否されます:そのバックエッドにはペアもシンボルもクロージャもないからです。

```clojure
(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))

(println (fact 10))
(println (reduce + 0 (map #(* % %) (filter odd? '(1 2 3 4 5)))))
```

対応表面の名前ごとのページが[リファレンス](reference.md)です:シーケンシャル分割束縛とマップ
分割束縛付きの核となる束縛・制御フォーム、スレッディングマクロ、すべてのコレクションの
strict なリストビュー上で動く seq 群、`equal` ハッシュテーブル上の永続 map・set 操作、
数値と述語のコア、atom と volatile、階層付き multimethod、`ex-info` 付きの
`try`/`catch`/`finally`、ファイルをまたぐ名前空間（プロジェクトのファイルの `ns`/`require`）と `clojure.string`、`clojure.set`、`clojure.walk`、`clojure.edn`、集計ランナー付きの `clojure.test`、ハンドラをすべてのバックエンドで提供する Ring アダプター（`ring.adapter.rontolisp`）、WASM のホスト境界と WIT 契約（`rontolisp.wasm`、`rontolisp.wit`）、そして Java interop。
各フォームが何に低下するか、何が拒否されるかは[セマンティクス](semantics.md)、リーダの規則は
[構文](syntax.md)、オラクルとの差異は[仕様との差異](deviations.md)にあります。

## このセクション

| ページ | 内容 |
|---|---|
| [REPL](repl.md) | `clojure>` REPL |
| [構文](syntax.md) | リーダ:リテラル、文字、基数整数、キーワード |
| [セマンティクス](semantics.md) | 低下対応表。拒否されるフォーム |
| [仕様との差異](deviations.md) | Clojure との動作の差異 |
| [リファレンス](reference.md) | 対応名ごとのページ |
