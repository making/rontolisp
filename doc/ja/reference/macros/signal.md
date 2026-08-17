# signal

`(signal datum args...)`

[`error`](error.md) と同じ designator サーフェスで**非致命的**なコンディションを通知します: リテラルまたは計算された制御文字列(後ろの引数がそのフォーマット引数。`simple-condition` を構築)、クォートされたコンディション型シンボル + initarg、またはコンディションオブジェクト。確立済みの [`handler-case`](handler-case.md) にコンディションへマッチする節があればシグナルはそこへ制御を移し、なければ -- ハンドラが全くない場合も、節がどれもマッチしない場合も -- `signal` は nil を返して実行を継続します(Common Lisp のフォールスルー、CLHS 9.1.4.1)。節がマッチしない `handler-case` は辞退され、後続のマッチするコンディションに対しては引き続き有効です。これは `--no-gc` を除くすべてのバックエンドで動作します(`--no-gc` のコンパイラは捕捉を拒否するため、そこでは `signal` は常に引数を評価して nil を返します)。

```lisp
(signal "nothing is listening") ; => NIL
```

```lisp
(handler-case (progn (signal "caught mid-flight") :not-raised)
  (condition (c) :raised)) ; => :RAISED
```

```lisp
(handler-case (progn (signal "nobody handles this") :fell-through)
  (type-error (c) :caught)) ; => :FELL-THROUGH
```
