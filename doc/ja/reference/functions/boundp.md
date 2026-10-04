# boundp

`(boundp symbol)`

`symbol` が束縛された**動的**変数 — グローバルな値を持つ変数(`defvar`/`defparameter`/トップレベルの `setq`)か、動的束縛(スペシャル変数の `let`、同名の関数引数、`progv`)が有効な変数 — を指すとき `t` を、そうでなければ nil を返します。Common Lisp の `boundp` と同様、レキシカルな束縛(通常の変数の `let`、関数引数)は見えません。`t`・`nil`・キーワードは自己評価する定数なので、それらの `boundp` は `t` です。

コンパイルバックエンドでは、シンボルが**リテラル**の `boundp` は、その呼び出しより前の定義で答えが決まる場合、コンパイル時に答えが決まりコストはゼロです。コンパイル済みプログラムは実行時に新しいグローバル変数を生やせないためです。定義で値を与えられない変数 — 値なしで宣言したスペシャル変数や、関数本体か後続のトップレベルの `setq` だけが代入する名前 — は、実行時にその変数自身が答え、eval ランタイムは含まれません。計算されたシンボル(`(boundp (intern name))`)も実行時に判定し、`eval` と同様に埋め込みの eval ランタイムが出力に含まれます — [`symbol-value`](symbol-value.md) と [`fboundp`](fboundp.md) は引数によらず常にそうなります。`--dynamic` でコンパイルした場合、あるいは `eval`/`load` を呼ぶプログラムでは、実行時判定のまま残ります。

関数値(`#'boundp`、たとえば `(mapcar #'boundp names)`)として使うと、全バックエンドで計算されたシンボルの判定になり、実行時に答えます。eval ランタイムが出力に含まれるのは `#'boundp` を名指しするプログラムだけです。

```lisp
(defvar *level* 7)
(boundp '*level*) ; => T
```

```lisp
(boundp '*undefined-var*) ; => NIL
```

```lisp
(boundp :key) ; => T
```

```lisp
(let ((x 1)) (boundp 'x)) ; => NIL
```

値なしで宣言したスペシャル変数は、それを束縛している間だけ束縛されています。

```lisp
(defvar *request*)
(list (boundp '*request*) (let ((*request* :r)) (boundp '*request*)) (boundp '*request*)) ; => (NIL T NIL)
```
