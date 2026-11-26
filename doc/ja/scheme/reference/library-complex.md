# (scheme complex)

数の塔の複素数半分、直交形式と極形式の構成子とアクセサです。複素数は `#C(real imag)` と印字され、リテラルも同じ綴りで読み戻せます。虚部が正確数の 0 であるリテラルは実数そのものになります（`#C(1 0)` は `1`）。Gauche は `1.0+2.0i` と印字し、`(make-rectangular 1 0)` に `1.0` を返します。これは deviations ページに記した偏差です。

| 名前 | 例 | 結果 |
|---|---|---|
| `make-rectangular` | `(make-rectangular 1 2)` | `#C(1 2)` |
| `make-polar` | `(make-polar 1 0.7853981633974483)` | `#C(0.7071067811865476 0.7071067811865475)` |
| `real-part` | `(real-part #C(1 2))` | `1` |
| `imag-part` | `(imag-part #C(1 2))` | `2` |
| `magnitude` | `(magnitude #C(3 4))` | `5.0` |
| `angle` | `(angle #C(1 1))` | `0.7853981633974483` |
