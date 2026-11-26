# rontolisp:with-mutex

`(rontolisp:with-mutex (mutex-form) body...)`

`mutex-form` を 1 回だけ評価してそのロック
([`rontolisp:make-mutex`](../functions/rontolisp-make-mutex.md) を参照)を獲得し、ボディを
実行し、**あらゆる**脱出時 — シグナルされたエラーによる脱出を含む — にロックを解放します。
最後のボディフォームの値が式全体の値です(ボディが空なら `nil`)。

サーブされるハンドラがリクエスト間で共有される状態を変更するときに使うフォームです。
[`rontolisp:http-handler`](../functions/rontolisp-http-handler.md)
はインタプリタと JVM バックエンドでリクエストごとに 1 つの仮想スレッドを立てるため、
グローバル変数の read-modify-write はそこで実際に競合します。両方の WASM バックエンドは
単一スレッドで動くので獲得と解放は no-op になり、同じソースが 4 つすべてで動きます。

```lisp
(defvar *counter-lock* (rontolisp:make-mutex))
(defvar *counter* 0)
(rontolisp:with-mutex (*counter-lock*)
  (setq *counter* (+ *counter* 1)))  ; => 1
```

## 引数

- mutex を生成するフォームを 1 つだけ持つリスト。ボディの前に 1 回だけ評価されます。
- ボディフォーム。ロックを保持したまま `progn` と同様に順に評価されます。

## 再入可能性

ロックは再入可能なのでネストしても安全です。ロックを取る関数が、同じロックを取る別の関数を
呼び出せます:

```lisp
(let ((m (rontolisp:make-mutex)))
  (rontolisp:with-mutex (m)
    (rontolisp:with-mutex (m) :nested)))  ; => :NESTED
```

## 制限

- Lisp からスレッドを生成する手段はありません。並行性はランタイムに由来します。
- タイムアウト付き/非ブロッキングの変種はありません。獲得は常にブロックします。
- マクロは関数値を持ちません: `#'rontolisp:with-mutex` はエラーです。
