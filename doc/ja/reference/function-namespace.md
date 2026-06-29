# 関数名前空間

rontolispはCommon Lispに倣った **Lisp-2** です。関数と変数は別々の名前空間に存在します。

- 裸のシンボルは **変数** として評価されます。`car`
  単独の評価はエラーです(インタプリタでは `The variable car is unbound`、コンパイラではコンパイルエラー)。
- **呼び出し位置** `(f args...)` のシンボルは関数名前空間のみで解決されます。`car`
  という名前の変数が関数 `car` をシャドウすることはありません。`(let ((car 5)) (car (list car 2)))`
  は `5` を返します。
- 関数は `#'name`(`(function name)` のリーダ構文)、`#'(lambda ...)`、`(symbol-function 'name)`
  を通じて **値** になります。これは組み込み演算子(`#'+`, `#'car`, `#'1+`, `#'cadr`)、ユーザ定義の
  `defun`、ラムダで機能します。
- `funcall`/`mapcar`/`reduce` は関数を指す **シンボル**(関数指定子)も受け付けます。`(funcall 'car '(1 2))`
  は `1` を返します。コンパイラはシンボルが引用されたリテラルである場合にこれをサポートします。
- `defun` は関数名前空間に定義し、関数名を返します。`(setq f (lambda ...))` は **変数**
  を関数値に束縛します。これは `(f ...)` ではなく `(funcall f ...)` で呼び出します。
- マクロまたは特殊演算子の `#'`(例: `#'if`, `#'defun`)はエラーです。

関数値は、3つの実行モードすべてで、引数として渡したり、関数から返したり、データ構造に格納したりできます。

**高階関数:**

```lisp
(defun apply-twice (f x) (funcall f (funcall f x)))
(defun square (x) (* x x))
(print (apply-twice #'square 3))    ; => 81
```

**クロージャ(参照によるキャプチャ):**

```lisp
(defun make-counter ()
  (let ((n 0))
    (lambda ()
      (setq n (+ n 1))
      n)))
(setq c (make-counter))
(funcall c) ; => 1
(funcall c) ; => 2
(funcall c) ; => 3
```

**引数としてのラムダ:**

```lisp
(defun apply-twice (f x) (funcall f (funcall f x)))
(print (apply-twice (lambda (x) (+ x 10)) 5))  ; => 25
```

**第一級の値としての組み込み演算子:**

`+`, `car`, `1+` のような組み込み演算子は `#'` を通じて高階関数に渡せます:

```lisp
(print (reduce #'+ '(1 2 3 4 5) :initial-value 0))   ; => 15
(print (reduce #'* '(1 2 3 4 5) :initial-value 1))   ; => 120
(print (mapcar #'car '((1 2) (3 4) (5 6))))          ; => (1 3 5)
(print (mapcar #'1+ '(1 2 3)))                       ; => (2 3 4)
(print (funcall #'+ 3 4))                          ; => 7
(setq my-op #'+)
(print (funcall my-op 10 20))                      ; => 30
(print (funcall (symbol-function 'car) '(9 8)))    ; => 9
```

**コンパイラの制限。** JVM/WASMコンパイラでは、`#'name`
はコンパイル時に判明している関数(ユーザ定義の `defun` と組み込み演算子)に対して解決されます。`#'mapcar`、`#'reduce`、`#'apply`、`#'funcall`
自体は値として利用できません(`#'mapcan` と `#'sort` は利用できます)。`symbol-function`
は引用されたシンボルリテラルの引数を要求します。`--dynamic` モードでは、未解決の `#'name`
は他の未解決参照と同様にランタイムの `eval` 環境に委ねられます。コンパイルされたコードでは、`apply`
はランタイムの `eval` 機構を再利用し(`eval`
ランタイムの発行を強制します)、実際の引数の数でディスパッチするため、適用される関数は一致するアリティを持つ必要があります。二項の組み込みラッパー(例:
`(apply #'+ '(1 2))`、`(apply #'cons 1 '(2))`)や任意の固定アリティのユーザ定義 `defun`
は機能しますが、異なる数に適用された可変長引数の組み込み(例: `(apply #'+ '(1 2 3))`、`(apply #'list ...)`)は機能しません。これは
[コンパイルされた `eval` の制限](../guides/eval-limitations.md)
に一致します。インタプリタにはそのような制限はありません。
