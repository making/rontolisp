# rontolisp:catch

`(rontolisp:catch future handler)`

入力 future がエラーで確定したとき、`handler` を condition に適用して
その戻り値で確定する新しい future を返します。正常に確定した場合は
値がそのまま通過します。ハンドラ自身がシグナルを上げた場合、返された
future はその condition を運びます。

```lisp
(rontolisp:async-defun boom () (error "nope"))
(rontolisp:await
  (rontolisp:catch (boom) (lambda (c) (declare (ignore c)) :fallback)))   ; => :FALLBACK
```

future が値として境界を越え、JavaScript の `.catch` 風の単一ハンドラ
フォールバックが欲しいときに使います。型ディスパッチは字句的に
`(handler-case (rontolisp:await f) (my-err (c) ...))` として既に存在
するので、future が本体の中にあるならそちらを使ってください。catch
ハンドラの中で型ディスパッチしたい場合は明示的に書きます:

```console
(rontolisp:catch f (lambda (c)
                     (handler-case (signal c)
                       (my-err (e) ...)
                       (error (e) ...))))
```

第 1 引数が future 以外の場合は `type-error` になります。

### `cl:catch` との名前衝突

Common Lisp の [`catch`](../special-forms/catch.md) /
[`throw`](../special-forms/throw.md) はタグベースの非局所脱出の特殊形式
です。本操作は別パッケージの `rontolisp:catch` です: 修飾名は衝突しません
(`cl:catch` は依然として CL 特殊形式)。`cl-user` (または両方を `:use` する
パッケージ) のユーザーは、本操作を得るには明示的に `rontolisp:` /
`rl:` 接頭辞が必要で、タグベースの特殊形式を得るには明示的な `cl:`
接頭辞 (または `cl-user` 内での裸の名前) が必要です。
`(in-package :rontolisp)` 内での裸の `catch` はそのどちらでもありません:
この名前は `cl` に属し、当該パッケージは `cl` を `:use` していないため、
修飾するまで "Undefined symbol: CATCH (use CL:CATCH)" エラーになります。

## バックエンドのサポート

[`rontolisp:then`](rontolisp-then.md) と同じ: インタプリタ、JVM、
WASM `--component`。Preview 1 WASM は成功パス通過のみ (エラー経路には
component バックエンドが提供する future 化されたエラー-at-await 契約
が必要です)。`--no-gc` はコンパイル時に拒否します。
