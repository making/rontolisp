# clojure.math

double を扱う関数と、オーバーフローを拒否する long の算術の名前空間です。`clojure.math` を require
すると使えます。Clojure の同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた
Clojure ソースです。double の関数はインタプリタ、JVM、2 つの WASM ターゲットのどれでも
`java.lang.StrictMath` と同じビットを返すので、プログラムはどのバックエンドでも同じ桁を出力します
（`Math/sin` は Java interop で、WASM バックエンドにはありません）。

| var | 振る舞い |
|---|---|
| `E`, `PI` | e と円周率に最も近い double |
| `sin`, `cos`, `tan` | `(sin a)`: ラジアンで表した角 `a` の正弦 |
| `asin`, `acos`, `atan` | `(asin a)`: 正弦が `a` になる角。`a` が [-1, 1] の外なら `##NaN` |
| `atan2` | `(atan2 y x)`: x 軸の正の向きから点 (x, y) までの角。[-pi, pi] の範囲 |
| `sinh`, `cosh`, `tanh` | 双曲線関数 |
| `exp`, `expm1` | `(exp a)`: e の `a` 乗。`(expm1 x)`: e^x - 1 を 0 の近くでも精度よく求める |
| `log`, `log10`, `log1p` | 自然対数と常用対数。0 では `##-Inf`、負では `##NaN`。`(log1p x)`: 1 + x の対数を 0 の近くでも精度よく求める |
| `pow`, `sqrt`, `cbrt`, `hypot` | `(pow a b)`: `a` の `b` 乗。負の `a` と整数でない `b` には `##NaN`。`(hypot x y)`: 途中でオーバーフローせずに sqrt(x^2 + y^2) を求める |
| `to-radians`, `to-degrees` | 角を度とラジアンのあいだで換算する |
| `ceil`, `floor`, `rint` | 引数以上、以下、最も近い整数値の double（`rint` は等距離なら偶数）。結果が 0 なら引数の符号を保つ |
| `round` | `(round a)`: `a` に最も近い long。等距離なら大きいほう。`##NaN` には `0`、long の範囲を超える double には範囲の近いほうの端 |
| `IEEE-remainder` | `(IEEE-remainder dividend divisor)`: 被除数から除数の `n` 倍を引いた値。`n` は商に最も近い整数 |
| `signum`, `copy-sign` | `(signum d)`: `d` の符号に応じて `1.0` か `-1.0`。0 と `##NaN` はそのまま。`(copy-sign magnitude sign)` |
| `ulp`, `get-exponent` | `(ulp d)`: 0 から遠ざかる向きの隣の double までの距離。`(get-exponent d)`: バイアスを除いた 2 進指数 |
| `next-after`, `next-up`, `next-down` | 指定した向き、`##Inf`、`##-Inf` に向かって隣にある double |
| `scalb` | `(scalb d n)`: `d` に 2^n を掛け、丸めを 1 回だけ行った値 |
| `add-exact`, `subtract-exact`, `multiply-exact` | `(add-exact x y)`: long の和。long の範囲を超えると `ArithmeticException`（`long overflow`） |
| `increment-exact`, `decrement-exact`, `negate-exact` | long に 1 を足した値、1 を引いた値、符号を反転した値。範囲の超過は `add-exact` と同じく拒否する |
| `floor-div`, `floor-mod` | `(floor-div x y)`: x / y 以下で最大の long。`(floor-mod x y)`: その剰余で、y の符号を持つ。`y` が 0 なら `ArithmeticException`（`/ by zero`） |
| `random` | [0.0, 1.0) から一様に引いた double |

double の関数は各引数を `double` と同じように変換します。long、比、10 進数は数として受け付け、
`nil` は `NullPointerException`、それ以外は `ClassCastException` になります。long の関数は double
と比を切り捨て、文字をその文字コードとして受け取り、`##NaN` を 0 とし、long の範囲を超える値を
`IllegalArgumentException` で拒否します。`scalb` の指数は int の範囲を超える値も
`ArithmeticException`（`integer overflow`）で拒否します。

```clojure
(require '[clojure.math :as m])
(m/sqrt 2)
; => 1.4142135623730951
(m/pow 2 10)
; => 1024.0
(m/log -1.0)
; => ##NaN
(m/ceil -0.5)
; => -0.0
(m/round 2.5)
; => 3
(m/floor-div -7 2)
; => -4
(m/scalb 1.0 -1074)
; => 4.9E-324
```

```clojure
(require '[clojure.math :as m])
(try (m/add-exact 9223372036854775807 1) (catch ArithmeticException e (ex-message e)))
; => "long overflow"
(map m/next-up [1.0 -0.0])
; => (1.0000000000000002 4.9E-324)
```

## 違い

- double の関数は `java.lang.StrictMath` と同じビットを返します。Clojure の関数が呼ぶ
  `java.lang.Math` は、x86-64 では `sin`、`cos`、`tan`、`exp`、`log`、`log10`、`cbrt`、`tanh`、
  `pow` の結果が引数の 1〜8 パーセントで最後の桁だけ異なります。`(m/exp 1)` はここでは
  `2.7182818284590455`、Clojure では `2.718281828459045` です。ほかの関数の結果は一致します。
- `copy-sign` は `##NaN` の符号を正として読みます。Clojure では NaN がたまたま持つ符号ビットに
  従います。
- 比は最も近い double に変換します。Clojure は先に有効数字 16 桁に丸めるので、`(m/sin 2/3)` の
  引数はここでは `0.6666666666666666`、Clojure では `0.6666666666666667` です。
- `scalb` は double の指数を、Clojure がローカル変数の指数を変換するのと同じように変換します。
  int の範囲を超えると `ArithmeticException` になり、Clojure でリテラルの double を指数に書いた
  場合の `IllegalArgumentException` とは異なります。関数値として呼んでも文字は long の文字コード
  になります（`(map m/floor-div [\a] [2])`）。Clojure の関数値はこれを拒否します。
