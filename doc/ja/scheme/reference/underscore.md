# _

`_`

`syntax-rules` の補助構文です。パターン中では何にでも一致し、何も束縛しないので、何度でも書けます。単独では意味を持ちません。

```scheme
(let-syntax ((second (syntax-rules () ((_ _ b . _) b)))) (second 1 2 3)) ; => 2
```
