# fboundp

`(fboundp function-name)`

`function-name` が呼び出せる・展開できるものを指すとき `t` を返します: 関数(組み込みまたは `defun`)、マクロ(組み込みまたは `defmacro`)、特殊形式、`cadr` のような `car`/`cdr` 合成です。マクロと特殊オペレータにも真を返す Common Lisp の `fboundp` と一致します。`(setf name)` リストも、クォートされたものも実行時に組み立てたものも関数名で、`(setf name)` 関数が定義されていれば `t` です。

コンパイルバックエンドでは、**リテラル**のクォートされた引数はコンパイル時に完全な知識(マクロ・特殊形式込み)で決定されます。計算された引数は実行時に関数レジストリと照合され、そこには本物の関数しか登録されていないため、`(fboundp (intern "cond"))` はコンパイル済みコードでは nil、インタプリタでは `t` になります。`defmacro` マクロも同様にコンパイル時のみの存在です。

関数値(`#'fboundp`、たとえば `(mapcar #'fboundp names)`)として使うと、全バックエンドで計算された引数の判定になり、上記の実行時の答えを返します。eval ランタイムが出力に含まれるのは `#'fboundp` を名指しするプログラムだけです。

`t`、`nil`、キーワードは関数を指さないシンボルなので、全バックエンドで `nil` を返します。

[`fmakunbound`](fmakunbound.md) で失効した名前は、リテラルの呼び出し箇所でも再び `nil` を返します。

トップレベル以外の [`defun`](../special-forms/defun.md#below-the-top-level) が定義する関数は、その定義が実行された時点で束縛されます。リテラルの引数は全バックエンドで、実行前は `nil`、実行後は `t` を返します。その名前を計算された引数で渡すと、コンパイルバックエンドでは `nil` を返します。

```lisp
(fboundp 'car) ; => T
```

```lisp
(fboundp 'cond) ; => T
```

```lisp
(defun greet (n) n)
(fboundp 'greet) ; => T
```

```lisp
(fboundp 'no-such-fn) ; => NIL
```

```lisp
(defun (setf fb-first) (value list) (setf (car list) value))
(list (fboundp '(setf fb-first)) (fboundp '(setf fb-none))) ; => (T NIL)
```
