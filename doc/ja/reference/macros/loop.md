# loop

`(loop clause...)` (拡張形式) または `(loop form...)` (単純形式)

ANSI `loop` マクロの限定的なサブセットです。既存の反復コア (内部のブロック境界で包まれた `do*` 相当のステップ処理) に展開されるため、インタプリタと両コンパイラで同一に動作します。

トップレベルの各サブ形式がすべて複合形式 (リスト) の場合、`loop` は **単純ループ** になります。それらの形式を `return` で脱出するまで永遠に繰り返します。

```lisp
(let ((i 0))
  (loop
    (setq i (+ i 1))
    (when (= i 5) (return i)))) ; => 5
```

それ以外の場合は、節 (clause) から構成される **拡張ループ** です。サポートする節は次のとおりです。

- 数値ステップ: `for VAR from LO [to|upto|below|downto|above HI] [by STEP]` (`upfrom`/`downfrom` も可。`from` のない上限キーワードは 0 から開始)。
- リストステップ: `for VAR in LIST [by FN]` と `for VAR on LIST [by FN]` (`VAR` には分配束縛パターンも指定可能)。
- シーケンスステップ: `for VAR across SEQ` は `VAR` を文字列の各文字、またはベクタの各要素に順に束縛します。
- 汎用ステップ: `for VAR = INIT [then STEP]` (`VAR` には分配束縛パターンも指定可能)。
- ローカル変数: `with VAR [= INIT]` (`VAR` には分配束縛パターンも指定可能。`and` で連結した `with` の束縛は並行に行われます)。
- 型宣言: `for`/`as`/`with` は `VAR` の直後、残りの節より前に、ANSI のいずれかの綴りによる型宣言を省略可能な要素として受け付けます — `of-type TYPE` の明示形式、または `fixnum`/`float`/`t`/`nil` の単純形式です。rontolisp の loop 展開は型を扱わないため、この宣言は解析されるだけで意味を持ちません。
- 集約: `collect`、`append`、`nconc`、`sum`、`count`、`maximize`、`minimize`。それぞれ省略可能な `into VAR` を取れます。
- 終了判定: `thereis EXPR`、`always EXPR`、`never EXPR`。
- 制御: `while`/`until` (記述位置で判定)、`repeat N`、`do FORM...`、`return EXPR`、本体形式内の `(loop-finish)`、`initially FORM...`、`finally FORM...`、および条件節 `when`/`if`/`unless` (省略可能な `else` と `end` を伴い、選択された節では判定値を `it` で参照可能)。

複数の `for` 節は一緒にステップし、最も短いドライバが尽きた時点でループは終了します。これがインデックス付き map の定石です。逐次的な節は順にステップします (後の節の初期化式・ステップ式は直前の節が生成した値を参照でき、最初に尽きたドライバでステップは停止するため、`for x in xs for a = (f x) then (g a x)` は CL と同様に動作します)。

```lisp
(loop for x in '(a b c) for i from 0 collect (list i x)) ; => ((0 A) (1 B) (2 C))
```

`and` は `for` 節をひとつのグループに連結し、初期化とステップを前回の反復の値に対して計算します (`do*` に対する `do` の並行ステップに相当)。

```lisp
(loop for a = 0 then b and b = 1 then (+ a b) repeat 8 collect b) ; => (1 1 2 3 5 8 13 21)
```

`for` 変数は反復ごとに作り直される束縛ではなく、**その場でステップされるひとつの束縛**です。したがってループ終了後も最後の反復の値を保持しており、それが `finally` の見る値であり、本体で生成したクロージャを後から呼び出したときに返る値でもあります (`dolist` は反復ごとに新しく束縛するため、そのクロージャはそれぞれの要素を保持します。両者は異なるのが正しい挙動です)。

```lisp
(mapcar #'funcall (loop for x in '(1 2 3) collect (lambda () x))) ; => (3 3 3)
```

変数への代入はその節自身の終了判定を通過した後にのみ行われます。そのため、ドライバが尽きた節は最後の値を保持したまま、要素が残っていた手前の節だけがステップします。

```lisp
(loop for x in '(1 2 3) for y in '(10 20) finally (return (list x y))) ; => (3 20)
```

`for VAR on` は一見例外に見えますが例外ではありません。この場合、変数そのものがカーソルなので `nil` で終わるのが正しい挙動です。`being the hash-key`/`hash-value` の変数も同様です。数値変数は上限を 1 ステップ超えた値で終わります。

```lisp
(loop for i from 1 to 3 finally (return i)) ; => 4
```

集約と数値範囲はよくあるケースを直接表現できます。

```lisp
(loop for i from 1 to 10 when (evenp i) sum i) ; => 30
```

本体節の後 (または `in`/`on`/`across` のように本体の先頭で変数を代入する `for` の後) に置いた `while`/`until` は、その記述位置で判定するため、現在の要素を参照できます。

```lisp
(loop for x in '(1 2 3 9 4) while (< x 4) collect x) ; => (1 2 3)
```

`when`/`if`/`unless` の内側では、アナフォリックな `it` で判定式の値を参照できます。

```lisp
(loop for x in '(1 nil 3 nil 5) when x collect it) ; => (1 3 5)
```

`thereis` は式が最初に非 nil になった値を返します。`always`/`never` は最初の失敗で `nil` に短絡し、正常終了時には `t` を返します。これらによる早期脱出は `return` と同様に `finally` をスキップします。

```lisp
(loop for x in '(nil nil 7 9) thereis x) ; => 7
```

本体形式内の `(loop-finish)` は反復を正常終了させます。`finally` は実行され、ループの結果値も生成されます (両方をスキップする `return` とは異なります)。

```lisp
(loop for i from 1
      collect i into xs
      do (when (>= i 3) (loop-finish))
      finally (return (length xs))) ; => 3
```

`for`/`with` の変数には分配束縛パターン — 変数のリスト (ネスト可能。`nil` はその位置を無視) — を指定できます。

```lisp
(loop for (a b) in '((1 2) (3 4) (5 6)) collect (+ a b)) ; => (3 7 11)
```

ドットパターンはリストの残りを束縛するため、連想リストをそのまま走査できます。

```lisp
(loop for (k . v) in '((a . 1) (b . 2)) collect (list k v)) ; => ((A 1) (B 2))
```

`for ... across` は文字列を 1 文字ずつ、またはベクタを 1 要素ずつ走査します。

```lisp
(loop for c across "hello" count (eql c #\l)) ; => 2
```

```lisp
(loop for x across #(1 2 3 4 5) collect (* x x)) ; => (1 4 9 16 25)
```

`for VAR being {the|each} {hash-keys|hash-key|hash-values|hash-value} {of|in} TABLE` はハッシュテーブルを反復します。`using (hash-value V)` (または `using (hash-key K)`) でもう一方を束縛できます。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (loop for k being the hash-keys of h using (hash-value v) collect (list k v))) ; => ((A 1))
```

この節はテーブルのスナップショットを取ってそれを走査するため、反復順序はテーブルのものになり、本体でテーブルを変更しても進行中の走査には影響しません。

`being` のパッケージ形式 — `for VAR being {the|each} {symbols|present-symbols|external-symbols} {of|in} PACKAGE` — は受け付けますが **簡易版** です: rontolisp には実行時のインターンテーブルがないため、この節は解析され *空* のシーケンスを反復します。本体は実行されず、集約結果は `nil` になります。ロード時にパッケージを走査するライブラリ (cl-who の hyperdoc テーブルなど) がエラーなくロードできるようにするためのものです:

```lisp
(loop for s being the external-symbols of :cl collect s) ; => NIL
```

変数の後の型宣言は、どちらの綴りでも受け付けられ、無視されます。

```lisp
(loop for v fixnum = 0 then (1+ v) for i from 1 to 3 collect v) ; => (0 1 2)
```

```lisp
(loop for v of-type fixnum = 0 then (1+ v) for i from 1 to 3 collect v) ; => (0 1 2)
```

制限事項: `named`/`return-from` は未対応です。分配束縛パターンはラムダリストキーワードを認識しません (`&optional` などはエラーにはならず、通常の変数として束縛されます)。`(loop-finish)` は文の位置 (式の途中は不可) に置く必要があり、ネストした反復形式の内側では使えません。`thereis`/`always`/`never` はデフォルト結果への集約とは併用できません (`into` を使ってください)。`into` を伴わない集約節はすべて同種でなければならず、収集系の節は結果リストをソース順に構築します。
