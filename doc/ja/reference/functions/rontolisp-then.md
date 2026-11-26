# rontolisp:then

`(rontolisp:then future function)`

future に対する変換を値として付与します: 入力 future が正常に確定した
とき、`function` を確定値に適用し、その戻り値で確定する **新しい**
future を返します。`function` 自体が future を返した場合、返された
future への `await` はそれを平坦化するので、呼び出し側が
`future<future<T>>` を観測することはありません。上流でエラーが起きた
場合はコールバックはスキップされ、その condition がそのまま返された
future を通じて伝播します。

future が値として境界を越えるときに非同期処理を組み合わせるために使い
ます。呼び出し先が非同期でも、呼び出し側が
`rontolisp:async-defun` である必要はありません:

```lisp
(rontolisp:async-defun some-future-producer () 21)
(defun caller ()
  (rontolisp:then (some-future-producer) (lambda (v) (* 2 v))))
(rontolisp:await (caller))   ; => 42
```

第 1 引数が future 以外の場合は `type-error` になります: JavaScript の
ような「値を解決済み promise に自動昇格させる」挙動はありません。

## バックエンドのサポート

インタプリタ、JVM バックエンド、WASM `--component` で使えます。
Preview 1 WASM は成功パスのみ (エラーは await ではなく呼び出し時に
シグナルされる、非同期サーフェスの縮退同期セマンティクス。エラー伝播
契約は外側の `handler-case` に委ねられます)。`--no-gc` は非同期サーフェス
全体をコンパイル時に拒否します。
