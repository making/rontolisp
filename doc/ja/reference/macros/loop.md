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
- リストステップ: `for VAR in LIST [by FN]` と `for VAR on LIST [by FN]`。
- 文字列ステップ: `for VAR across STRING` は `VAR` を各文字に順に束縛します。
- 汎用ステップ: `for VAR = INIT [then STEP]`。
- ローカル変数: `with VAR [= INIT]` (`and` で連結可能)。
- 集約: `collect`、`append`、`nconc`、`sum`、`count`、`maximize`、`minimize`。それぞれ省略可能な `into VAR` を取れます。
- 制御: `while`/`until`、`repeat N`、`do FORM...`、`return EXPR`、`initially FORM...`、`finally FORM...`、および条件節 `when`/`if`/`unless` (省略可能な `else` と `end` を伴う)。

複数の `for` 節は並行してステップするため、最も短いドライバが尽きた時点でループは終了します。これがインデックス付き map の定石です。

```lisp
(loop for x in '(a b c) for i from 0 collect (list i x)) ; => ((0 a) (1 b) (2 c))
```

集約と数値範囲はよくあるケースを直接表現できます。

```lisp
(loop for i from 1 to 10 when (evenp i) sum i) ; => 30
```

`for ... across` は文字列を 1 文字ずつ走査します (ここでランダムアクセス可能なシーケンス型は文字列のみです)。

```lisp
(loop for c across "hello" count (eql c #\l)) ; => 2
```

制限事項 (この第一段階では対象外): 分配束縛、`for` 節同士の並行 `and`、`being`、アナフォリックな `it`、`named`/`loop-finish`、`thereis`/`always`/`never`。`while`/`until` は記述位置にかかわらず反復の先頭で終了判定を行います。`into` を伴わない集約節はすべて同種でなければならず、収集系の節は結果リストをソース順に構築します。
