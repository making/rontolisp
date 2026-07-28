# fboundp

`(fboundp symbol)`

`symbol` が呼び出せる・展開できるものを指すとき `t` を返します: 関数(組み込みまたは `defun`)、マクロ(組み込みまたは `defmacro`)、特殊形式、`cadr` のような `car`/`cdr` 合成です。マクロと特殊オペレータにも真を返す Common Lisp の `fboundp` と一致します。

コンパイルバックエンドでは、**リテラル**のクォートされた引数はコンパイル時に完全な知識(マクロ・特殊形式込み)で決定されます。計算された引数は実行時に関数レジストリと照合され、そこには本物の関数しか登録されていないため、`(fboundp (intern "cond"))` はコンパイル済みコードでは nil、インタプリタでは `t` になります。`defmacro` マクロも同様にコンパイル時のみの存在です。

[`fmakunbound`](fmakunbound.md) で失効した名前は、リテラルの呼び出し箇所でも再び `nil` を返します。

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
