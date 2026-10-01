# volatile!

`(volatile! v)`

アトムと同じセルの、compare-and-set のないものです。`deref`/`vswap!`/`vreset!` がその動詞で、いずれも新しい値を返り、関数値として動きます。volatile でないものへの誤用はシグナルを上げ、アトムの動詞を volatile へ、その逆も同様に向けることでも上がります。

```clojure
(println @(volatile! 5)) ; 5
```
