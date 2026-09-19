# cond-expand

`(cond-expand (requirement body...)... [(else body...)])`

要件が成り立つ最初の節を、なければ `else` 節を選び、その本体を `begin` と同じように置きます: トップレベルと本体ではその定義はそこでの定義に、先頭の `import` の並びではその `import` はプログラムの `import` に、式の中では最後の形式の値になります。[define-library](define-library.md) の宣言としては、本体はさらに宣言の並びです。要件は次のいずれかです:

- 機能識別子。[features](features.md) が返すもののいずれか;
- `(library name)`。`(import name)` がライブラリを見つけられるとき成り立ちます: このフロントエンドにある標準ライブラリ、それより前の `define-library`、またはライブラリファイル;
- `(and requirement...)`、`(or requirement...)`、`(not requirement)`。

節はプログラムを読む時点で選ばれるので、コンパイルしたプログラムには選ばれた本体だけが入ります。どの節も成り立たず `else` もない `cond-expand` はエラーです。R7RS では未規定です。

```scheme
(cond-expand (ratios (/ 1 3)) (else 0.33)) ; => 1/3
(cond-expand ((and r7rs (not gauche)) 'here) (else 'there)) ; => here
(cond-expand ((library (scheme char)) 'has-char) (else 'no-char)) ; => has-char
```

```scheme
(import (scheme base))
(cond-expand
  ((library (scheme write)) (import (scheme write)))
  (else))
(define (third x)
  (cond-expand
    (exact-closed (define result (/ x 3)))
    (else (define result (* x 1/3))))
  result)
(write (third 2))
(newline)
```

```
2/3
```
