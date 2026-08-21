# チューニング済み BLAS によるアクセラレーション(`--blas`)

`--blas` は [`linalg`](linear-algebra.md) の行列積を、OS が持つチューニング済み BLAS へ振り向けます。直交する 3 つのアクセラレーションフラグの 1 つです。[`--simd`](simd-acceleration.md) はベクトル化可能な `vec:` / `linalg:` カーネルを CPU のベクトル命令へロワリングし、`--blas` は行列積をライブラリ呼び出しに置き換え、[`--gpu`](gpu-acceleration.md) はその行列積と要素ごとの超越関数を NVIDIA のデバイスへ載せます。3 つのどの組み合わせでも、どれも付けなくても構いません。

`--simd` は行列積に手書きのレーンカーネルを与えます。しかし、デスクトップやサーバーの OS はそれよりはるかに速いものを提供できます。**チューニング済み BLAS** — 行列積がそのマシンのキャッシュ階層向けにブロック化され、行列命令に合わせて書かれたライブラリ — が、すでに OS の中にあるか、パッケージ 1 つで入るからです。`--blas` はそれを見つけ、`linalg` の行列積をそこへ振り向けます。

```bash
rontolisp prog.lisp --blas                  # インタプリタ
rontolisp prog.lisp -o Prog.class --blas    # JVM
```

**チューニング済み BLAS は推奨であって、必須ではありません。** 同梱もダウンロードもしません。BLAS のないマシンでも同じプログラムが同じ出力で動き(ただし遅い)、インタプリタは失敗するのではなく標準エラーにその旨を出します。

- **macOS**: インストール不要です。`Accelerate.framework` はシステムの一部で、`--blas` はそれを見つけます。
- **Linux**: インストールしてください。例えば `sudo apt install libopenblas0-pthread` (Debian / Ubuntu) や `sudo dnf install openblas` (Fedora / RHEL) です。NVIDIA NVPL・Intel MKL・BLIS・ATLAS・Arm Performance Libraries も認識します。
- **Windows やその他**: `RONTOLISP_BLAS` でライブラリを自分で指定するか、フラグなしで実行してください。

`#d` の行列積 1 回あたりの効果(Apple M4 Max・macOS・Accelerate。マシンとライブラリによって変わるので、必ず自分で測ってください):

| n x n | 移植版の定義 | `--simd` | `--blas` |
|---|---|---|---|
| 128 | 1150 ms | 0.55 ms | 0.04 ms |
| 512 | -- | 21 ms | 0.4 ms |
| 1024 | -- | 180 ms | 3.1 ms |

Linux で 20 コアの Arm マシンに OpenBLAS 0.3.26 を入れて同じ測定をすると、全コアを使って `--simd` の 20 倍、1 スレッドに固定して 5.2 倍でした。


## 何が加速され、何が辞退するのか

行列積だけです。`linalg:dot` の行列×行列・行列×ベクトル・ベクトル×行列、したがってそれらの上に書かれた rank 2 以下の `linalg:matmul` と `linalg:solve` です。効果はすべてそこにあります。メモリ律速のメンバー(`sum`、ベクトル×ベクトルの `dot`、要素ごとの算術)はライブラリ呼び出しから何も得られませんし、そちらはすでに `--simd` が担当しています。

それ以外はすべて**辞退**し、それまでどおりのものを実行します — `--simd` も付いていればそのカーネル、なければ移植版の `linalg.lisp` の定義です。一般のボックス化配列、幅の混在、スカラー引数、rank 3 のバッチ積、形状の不一致(同じエラーを送出します)、そしてライブラリ呼び出しに見合わない小さすぎる積が該当します。つまり `--blas` は、プログラムが何を受け付け何を拒むかを変えません。

## 対象バックエンド・スレッド・精度

`--blas` が届くのは**インタプリタ**(ネイティブバイナリを含む)と **JVM のクラス出力**です。チューニング済み BLAS は foreign function API 経由で呼ぶため、それを持たない WASM では `--blas` は黙って無視されるのではなくエラーになります。コンパイルされたクラスは制限付きメソッドを呼ぶので、`java --enable-native-access=ALL-UNNAMED Prog` として実行すると JVM の警告が標準エラーに出ません。

チューニング済み BLAS は**マルチスレッド**です。rontolisp の他のどの部分もそうではありません。`linalg:matmul` 1 回がマシンの全コアを占有しうる、ということです。上の Linux の数字の大部分もそれです。マシンを共有するプログラムでは、ライブラリ自身の環境変数 — `OPENBLAS_NUM_THREADS`・`MKL_NUM_THREADS`・Accelerate なら `VECLIB_MAXIMUM_THREADS` — で上限を設けてください。

ライブラリはリダクションをブロック化して並べ替えるので、**加速された積は移植版の定義と等しいのではなく近い**ものになります。`linalg` の既定の `#d` の幅でそうなります。正確な入力(整数や 2 のべき)であれば結果は完全に一致し、そうでなければ最後の数 ulp が異なります。このフラグは、rontolisp のアクセラレーションの中で唯一、数値的な答えが**マシンにどのライブラリのどのバージョンが入っているか**に依存します。独立したフラグにしてあるのはまさにそのためで、既存の `--simd` ビルドはこれまでどおりの値を計算し続けます。

## どのライブラリが結び付いたのか

ライブラリがあることは、それがチューニング済みであることを意味しません。netlib の**リファレンス**実装は同じシンボルを公開していながら rontolisp が既に持つカーネルより遅く、Debian の `libblas.so.3` はそのどちらをも指しうる alternatives のシンボリックリンクです。そこで `--blas` は、候補がチューニング済み実装だと自己申告した場合にだけ採用し、それ以外は辞退します — 加速なしのビルドより遅くなることこそ、この機能が決してやってはならない唯一のことだからです。

```bash
RONTOLISP_BLAS_VERBOSE=1 rontolisp prog.lisp --blas   # 何が結び付いたか、結び付かなかったならその理由を表示
RONTOLISP_BLAS=/path/to/libopenblas.so.0 rontolisp prog.lisp --blas   # ライブラリを直接指定
```

`RONTOLISP_BLAS` は探索と識別チェックの両方を飛ばすので、この一覧では名前を挙げられないチューニング済みビルドを使う手段にもなります。どちらの変数もコンパイル済みクラスからも読まれます。`.class` を実行するマシン側で確認するにはこれを使ってください。

## 実行できる例

[`examples/ml/blas-matmul.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/blas-matmul.lisp) は、`--simd` と `--blas` の両方が届く唯一の例です。linalg の既定の `double-float` 幅での `linalg:matmul` 1 回だけ、それ以外に何もしないからです。要素はすべて小さい整数なので、積も和もすべて正確で、レーンであれライブラリのブロック化であれ、並べ替えが表示される桁を動かすことはありません。最大 4 通りで実行してください。

```bash
rontolisp examples/ml/blas-matmul.lisp
rontolisp examples/ml/blas-matmul.lisp --simd
rontolisp examples/ml/blas-matmul.lisp --blas
rontolisp examples/ml/blas-matmul.lisp --simd --blas
```

Apple M4 Max で 128x128 の積 1 回あたり、インタプリタは 1848 ms が `--simd` で 0.62 ms、`--blas` で 0.034 ms になり、JVM は 0.37 ms が 0.043 ms になります。foreign function API がなく `--blas` も使えない wasm-GC にコンパイルした場合は、60 ms が `--simd` で 1.4 ms です。加速した実行の時間を測るときはソース中の `*reps*` を上げてください。積 1 回はこの時計が見える 1 ms の刻みよりずっと速く終わります。

