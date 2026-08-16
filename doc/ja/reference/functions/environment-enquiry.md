# lisp-implementation-type lisp-implementation-version software-type software-version machine-type machine-version machine-instance short-site-name long-site-name

`(lisp-implementation-type)` -- `(lisp-implementation-version)` -- `(software-type)` -- `(software-version)` -- `(machine-type)` -- `(machine-version)` -- `(machine-instance)` -- `(short-site-name)` -- `(long-site-name)`

環境問い合わせ関数です。それぞれ文字列または `nil` を返し、ここではすべての答えが定数です。ホストからは何も読まないため、ライブラリがこれらを組み合わせて作る User-Agent やバナーは、`machine-type` を除いてどのバックエンドでも同じ文字列になります。

| 関数 | 返り値 |
| --- | --- |
| `lisp-implementation-type` | `"rontolisp"` |
| `lisp-implementation-version` | プロジェクトのバージョン。`rontolisp --version` や `(getf (rontolisp:version) :version)` が報告するものと同じ文字列 |
| `software-type` | `"Unix"` -- `uiop:os-unix-p` や `uiop:operating-system` と同じ主張。どのバックエンドも POSIX 形のファイルモデルを提示します |
| `software-version` | `nil` |
| `machine-type` | 実行中の成果物が対象とする ABI。インタプリタと JVM バックエンドでは `"JVM"`、2 つの WASM バックエンドでは `"WASM32"` |
| `machine-version` | `nil` |
| `machine-instance` | `nil` |
| `short-site-name` | `nil` |
| `long-site-name` | `nil` |

`machine-type` はホストの CPU ではなく ABI を意図的に名乗ります。クラスファイルも wasm モジュールも CPU 非依存であり、[`uiop:architecture`](../uiop/os.md) が `:jvm` / `:wasm32` を返すのと同じ理由です。rontolisp が知り得ないものはすべて `nil` を返します。これは適切で関連する結果を提供できない場合に Common Lisp が定める答えであり、でっち上げたホスト名やバージョンは「不在」ではなく「答え」になってしまうためです。

```lisp
(list (lisp-implementation-type) (software-type) (machine-type) (machine-version))
; => ("rontolisp" "Unix" "JVM" NIL)
```

バージョンはビルド固有の値なので、検証はせず表示のみとします。

```lisp
(format nil "dexador/1.0 (~A ~A); ~A"
        (lisp-implementation-type) (lisp-implementation-version) (software-type))
```

## バックエンド対応

4 バックエンドすべて。rontolisp ソースによる 1 つの定義で、参照されたプログラムにのみ差し込まれます。バックエンドごとに異なるのは `machine-type` だけです。
