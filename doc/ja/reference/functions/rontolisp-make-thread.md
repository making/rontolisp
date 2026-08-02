# rontolisp:make-thread

`(rontolisp:make-thread function &optional bindings)`

引数なしの `function` を実行する新しい(仮想)スレッドを生成し、**不透明なスレッド
ハンドル**を即座に返します。ハンドルは
[`rontolisp:join-thread`](rontolisp-join-thread.md)、
[`rontolisp:thread-alive-p`](rontolisp-thread-alive-p.md)、
[`rontolisp:destroy-thread`](rontolisp-destroy-thread.md) に渡し、
[`rontolisp:threadp`](rontolisp-threadp.md) で判定します。mutex のハンドルと同様に
実体はバックエンドごとに異なるため、表示や順序付けは移植可能ではありません。

`bindings` は `(symbol . value)` ペアの alist で、各ペアは `function` の実行前に
**新しい**スレッド内でスレッドスコープの動的束縛として確立されます。生成された
スレッドは生成元の動的束縛を一切引き継ぎません。ここに項目がなければ、すべての
スペシャル変数はグローバル値を読みます。`*standard-output*` をこの方法で束縛すると
新しいスレッドの print ファミリが指定したストリームに向かいます —
`bordeaux-threads`/`bt2` ライブラリ(および Clack のハンドラ)が使う形です。

```lisp
(defvar *cap* (make-string-output-stream))
(rontolisp:join-thread
 (rontolisp:make-thread (lambda () (princ "from the thread"))
                        (list (cons '*standard-output* *cap*))))
(get-output-stream-string *cap*) ; => "from the thread"
```

スレッドはインタプリタと JVM バックエンドで実体があります。両方の WASM バックエンドは
構造上シングルスレッドであり、この関数はコンパイルされません。そこでは
`bordeaux-threads`/`bt2` シムの `make-thread` が呼び出し時に明確なエラーを通知します。

## 制限

- WASM: 利用不可(上記参照)— そこでは Clack アプリを `:use-thread nil` で実行します。
- 束縛の値はそのまま使われます。upstream の `bordeaux-threads` と異なり、新しい
  スレッド内でのフォーム評価はありません(`bt2:make-thread` シムは
  `:initial-bindings` に `quote` フォームと自己評価値を受け付け、それ以外は
  エラーを通知します)。
- これらのプリミティブは関数値を持ちません: `#'rontolisp:make-thread` はエラーです。
