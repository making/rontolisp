# チューニング済み BLAS によるアクセラレーション(`--blas`)

`--blas` は行列積 — [`linalg`](linear-algebra.md) のものと [`vec`](simd-acceleration.md) の GEMV — を、OS が持つチューニング済み BLAS へ振り向けます。直交する 3 つのアクセラレーションフラグの 1 つです。[`--simd`](simd-acceleration.md) はベクトル化可能な `vec:` / `linalg:` カーネルを CPU のベクトル命令へロワリングし、`--blas` は行列積をライブラリ呼び出しに置き換え、[`--gpu`](gpu-acceleration.md) はその行列積と要素ごとの超越関数を GPU へ載せます。3 つのどの組み合わせでも、どれも付けなくても構いません。

`--simd` は行列積に手書きのレーンカーネルを与えます。しかし、デスクトップやサーバーの OS はそれよりはるかに速いものを提供できます。**チューニング済み BLAS** — 行列積がそのマシンのキャッシュ階層向けにブロック化され、行列命令に合わせて書かれたライブラリ — が、すでに OS の中にあるか、パッケージ 1 つで入るからです。`--blas` はそれを見つけ、行列積をそこへ振り向けます。

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

行列×**ベクトル**の積は話が別で、マシン依存性がずっと強くなります。同じレーンカーネルと比べて `cblas_sgemv` 1 回は Apple M4 Max では 6〜9 倍ですが、1 スレッドに固定した x86-64 の Xeon ではわずか 1.2〜2.0 倍です — そちらではレーンカーネル自体がもともと 2 倍速いからです。NEON は 128 ビットのベクトルしか持たないのに対し、AVX2 は同じソースに 256 ビットのレジスタを与えます。**配備するマシンで測ってください**。その前に下のスレッドの注意も読んでください。

## 何が加速され、何が辞退するのか

行列積だけです。行列積を持つ 2 つのパッケージの両方で。

- **`linalg:dot`** の行列×行列・行列×ベクトル・ベクトル×行列、したがってそれらの上に書かれた rank 2 以下の `linalg:matmul` と `linalg:solve`。
- **`vec:matvec` と `vec:matvec-into`**、すなわち GEMV。2 つのうち当てはまりがよいのは `vec:matvec-into` のほうです。ライブラリは呼び出し側が渡した書き込み先に直接書くので、この横取りはループだけでなく結果のアロケーションも省きます。

効果はすべてそこにあります。メモリ律速のメンバー(`linalg:sum`、ベクトル×ベクトルの `dot`、`vec:sum`、どちらのパッケージでも要素ごとのカーネル)はライブラリ呼び出しから何も得られませんし、そちらはすでに `--simd` が担当しています。

このフラグが、それが存在する理由であるプログラムに届くようになるのは `vec` 側のおかげです。1 トークンずつデコードする Transformer は、すべての重み行列を別の行列ではなく*ベクトル*に掛けます。したがって `examples/ml/simd-gemv.lisp`・`examples/ml/tiny-llm.lisp`・`examples/llama2/llama2.lisp` は端から端まで GEMV です。**`--blas` 単独で十分です** — `vec` に届かせるために `--simd` を併記する必要はありません。

それ以外はすべて**辞退**し、それまでどおりのものを実行します — `--simd` も付いていればそのカーネル、なければ移植版の `linalg.lisp` / `vec.lisp` の定義です。一般のボックス化配列、幅の混在、スカラー引数、rank 3 のバッチ積、書き込み先が自分の被演算子でもある `vec:matvec-into`、形状の不一致(同じエラーを送出します)、そしてライブラリ呼び出しに見合わない小さすぎる積が該当します。つまり `--blas` は、プログラムが何を受け付け何を拒むかを変えません。

## 対象バックエンド・スレッド・精度

`--blas` が届くのは**インタプリタ**(ネイティブバイナリを含む)と **JVM のクラス出力**です。チューニング済み BLAS は foreign function API 経由で呼ぶため、それを持たない WASM では `--blas` は黙って無視されるのではなくエラーになります。コンパイルされたクラスは制限付きメソッドを呼ぶので、`java --enable-native-access=ALL-UNNAMED Prog` として実行すると JVM の警告が標準エラーに出ません。

チューニング済み BLAS は**マルチスレッド**です。rontolisp の他のどの部分もそうではありません。`linalg:matmul` 1 回がマシンの全コアを占有しうる、ということです。上の Linux の数字の大部分もそれです。マシンを共有するプログラムでは、ライブラリ自身の環境変数 — `OPENBLAS_NUM_THREADS`・`MKL_NUM_THREADS`・Accelerate なら `VECLIB_MAXIMUM_THREADS` — で上限を設けてください。

**GEMV のループでは、上限を設けるのは礼儀の問題ではありません。勝ちと惨敗を分けます。**行列×ベクトルの積はメモリ律速で短いため、マルチスレッドのライブラリは呼び出しごとにスレッドバリアの代償を払い、その呼び出し自身ではそれを償却できません。64 コアのマシンで `examples/llama2/llama2.lisp` に stories15M をデコードさせると、JVM バックエンドで `--simd` が 102〜110 tok/s、`OPENBLAS_NUM_THREADS=1` を付けた `--simd --blas` が **124 tok/s**、ライブラリの既定スレッド数のままの `--simd --blas` は **16 tok/s** でした。rontolisp はこの変数を代わりに設定しません — ライブラリのスレッドプールの大きさを決めるのは利用者です — ので、自分で設定してください。

```bash
OPENBLAS_NUM_THREADS=1 rontolisp examples/ml/tiny-llm.lisp --simd --blas
```

とはいえ、後から自分で気付けとは言いません。`--blas` はライブラリに使用スレッド数を尋ね、バリアを償却できないほど小さい積をプログラムが 64 回発行した時点で、設定すべき変数の名前を挙げる 1 行を標準エラーに書きます。積がスレッドを欲しがるだけの大きさであるプログラムには、この 1 行は出ません。同じ 64 コアのマシンで 1024x1024 の行列積はスレッドありのほうが 6 倍*速い*からで、既定値が誤りなのは短い呼び出しに対してだけであり、それを判別できるのは呼び出しだけです。既に上限が設定されているライブラリと、尋ねる手段のないライブラリ(Accelerate は公開していません)は、何も言いません。

ライブラリはリダクションをブロック化して並べ替えるので、**加速された積は移植版の定義と等しいのではなく近い**ものになります。どちらの幅でもそうです。正確な入力(整数や 2 のべき)であれば結果は完全に一致し、そうでなければ最後の数 ulp が異なります — `single-float` ではさらに大きく、GEMV のリダクションは単精度で累算するからです。このフラグは、rontolisp のアクセラレーションの中で唯一、数値的な答えが**マシンにどのライブラリのどのバージョンが入っているか**に依存します。独立したフラグにしてあるのはまさにそのためで、既存の `--simd` ビルドはこれまでどおりの値を計算し続けます。

## どのライブラリが結び付いたのか

ライブラリがあることは、それがチューニング済みであることを意味しません。netlib の**リファレンス**実装は同じシンボルを公開していながら rontolisp が既に持つカーネルより遅く、Debian の `libblas.so.3` はそのどちらをも指しうる alternatives のシンボリックリンクです。そこで `--blas` は、候補がチューニング済み実装だと自己申告した場合にだけ採用し、それ以外は辞退します — 加速なしのビルドより遅くなることこそ、この機能が決してやってはならない唯一のことだからです。

```bash
RONTOLISP_BLAS_VERBOSE=1 rontolisp prog.lisp --blas   # 何が結び付いたか、結び付かなかったならその理由を表示
RONTOLISP_BLAS=/path/to/libopenblas.so.0 rontolisp prog.lisp --blas   # ライブラリを直接指定
```

`RONTOLISP_BLAS` は探索と識別チェックの両方を飛ばすので、この一覧では名前を挙げられないチューニング済みビルドを使う手段にもなります。どちらの変数もコンパイル済みクラスからも読まれます。`.class` を実行するマシン側で確認するにはこれを使ってください。verbose の行には、ライブラリが申告したスレッド数も出ます。尋ねる手段がないライブラリでは `0` です。

## 実行できる例

[`examples/ml/blas-matmul.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/blas-matmul.lisp) は `linalg` 側を単独で取り出したものです。linalg の既定の `double-float` 幅での `linalg:matmul` 1 回だけ、それ以外に何もしません。要素はすべて小さい整数なので、積も和もすべて正確で、レーンであれライブラリのブロック化であれ、並べ替えが表示される桁を動かすことはありません。最大 4 通りで実行してください。

```bash
rontolisp examples/ml/blas-matmul.lisp
rontolisp examples/ml/blas-matmul.lisp --simd
rontolisp examples/ml/blas-matmul.lisp --blas
rontolisp examples/ml/blas-matmul.lisp --simd --blas
```

Apple M4 Max で 128x128 の積 1 回あたり、インタプリタは 1848 ms が `--simd` で 0.62 ms、`--blas` で 0.034 ms になり、JVM は 0.37 ms が 0.043 ms になります。foreign function API がなく `--blas` も使えない wasm-GC にコンパイルした場合は、60 ms が `--simd` で 1.4 ms です。加速した実行の時間を測るときはソース中の `*reps*` を上げてください。積 1 回はこの時計が見える 1 ms の刻みよりずっと速く終わります。

GEMV 側で実行すべきなのは [`examples/ml/simd-gemv.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv.lisp) です。256x256 の `single-float` 行列に対する `vec:matvec` 100 回だけで、他に何もしません。表示されるのは `argmax` のインデックス、つまりそれを生んだすべての積和から導かれる整数で、これが動いてはいけません。

```bash
rontolisp examples/ml/simd-gemv.lisp                                   # スカラー
rontolisp examples/ml/simd-gemv.lisp --blas                            # ライブラリの GEMV
OPENBLAS_NUM_THREADS=1 rontolisp examples/ml/simd-gemv.lisp --simd --blas
```

OpenBLAS を入れた 64 コアの Xeon では、インタプリタは 8964 ms が `--simd` で 187 ms、1 スレッドに固定した `--simd --blas` で 131 ms になります(固定しないと 371 ms に戻ります)。`--blas` 単独では 629 ms です。GEMV はライブラリ呼び出しになっていますが、その隣の `vec:dot` と `vec:scale` はまだ移植版の定義のままで、残り時間の大半はそれだからです。

