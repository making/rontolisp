# progv

`(progv symbols values body...)`

`symbols` と `values`(それぞれリスト)を評価し、各シンボルを対応する値に `body` の実行中はダイナミックに束縛し、脱出時に以前の値を復元します。`values` が `symbols` より短い場合、余ったシンボルは値なしで束縛されます。`body` のエクステントの間は未束縛になり、読むとその名前を持つ `unbound-variable` を通知し、[`boundp`](../functions/boundp.md) は `nil` を返します。代入すると束縛に値が入ります。コンパイルするバックエンドでは、標準の変数(`*print-base*`、`*standard-output*` など)はこの場合 `nil` に束縛されます。[`let`](let.md) と異なり、シンボルは実行時に計算され、スペシャル宣言されている必要はありません。最後の本体フォームの値を返します。

`progv` はすべてのバックエンドで動作します。コンパイラは、プログラム中で静的に判明しているスペシャル変数の集合に対するディスパッチへ展開します。プログラムのスペシャル変数であるシンボルは本来のダイナミック束縛を受け、それ以外のシンボルも束縛の間は `symbol-value` と `boundp` から見えるように束縛されます。どのスペシャル変数もシンボルの中に現れうるので、`progv` を使うコンパイル済みプログラムは、スペシャル変数の読み出しのたびに値がないことを検査します。コンパイルされる WASM プログラムで `progv` を使うと例外処理モードでコンパイルされます。

```lisp
(progv '(a b) '(1 2) (list (symbol-value 'a) (symbol-value 'b))) ; => (1 2)
(defvar *x* 1)
(progv '(*x*) '() (boundp '*x*))                                  ; => NIL
(progv '(*x*) '() (handler-case *x* (unbound-variable () :unbound))) ; => :UNBOUND
```
