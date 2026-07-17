# 手書きひらがな認識 (rontolisp で CNN を学習 → WASM → ブラウザ canvas)

全 46 文字（五十音 あ〜ん、濁点・拗音は除く）の**畳み込みニューラルネット (CNN)** を
rontolisp で書き、**実際の手書きかな (Kuzushiji-49) と複数フォントの合成字形**で
**オフラインで学習**し、推論だけを WebAssembly にコンパイルして、ブラウザの `<canvas>` に
書いた文字を認識させるデモです。

学習（バックプロパゲーション）はコストが高いので一度だけオフラインで行い、ブラウザでは
**学習済みの重み (`weights.bin`) を読み込んで推論するだけ**にしています。同じ推論プログラムは
インタプリタ・JVM・WASM Preview1・WASM コンポーネントのいずれでも動きます。

ネットワークは新しく書き起こしたものではなく、[`examples/deep-learning-from-scratch`](../../deep-learning-from-scratch)
の **ch07 SimpleConvNet をそのまま**このデモの入力サイズで使っています（`linalg:` の im2col
行列積・CLOS レイヤ・Adam・trainer をすべて再利用）。

```
input   1 x 24 x 24  二値ビットマップ（ブラウザが canvas を縮小したもの）
conv    16 filters, 5x5, pad 2   -> 16 x 24 x 24
pool    2x2 max                  -> 16 x 12 x 12  (= 2304)
affine  2304 -> 64, relu
affine  64 -> 46                 -> softmax（46 クラス）
```

## 精度

同じ held-out テスト集合（K49 テスト分割 4,600 枚 = **実際の手書き**）での比較です。

| モデル | 実手書き (K49 test) | 参考字形 46 字 | 全フォント変種 184 字 |
| --- | --- | --- | --- |
| 旧: 576-20-46 MLP（合成フォントのみで学習・重み焼き込み） | 650 / 4600 (14.1%) | 46 / 46 | — |
| **新: CNN（K49 + 合成フォント）** | **3476 / 4600 (75.6%)** | **46 / 46** | **184 / 184** |

旧デモは「参考字形どおりに書けば当たるが、自由な手書きは外す」テンプレート記憶器でした。
実データを混ぜた CNN にしたことで、**参考字形の 46/46 を保ったまま**、実手書きが
14% → 76% になりました。

## 仕組み

```
[オフライン]  tools/k49/prepare-k49.py   K49 (.npz) をダウンロードし、ブラウザと同じ
                                         前処理で 24x24 二値化 -> data/k49-*.bin
              train.lisp                 dataset.lisp (K49 + 合成字形の水増し) と
                                         net.lisp (ch07 SimpleConvNet) を load し、
                                         Adam で学習 -> weights.bin (RLW1 バイナリ)
              recognize.lisp             同じネットを wasm-export し infer.wasm に
                                         コンパイル

[ブラウザ]    infer.wasm を1回だけインスタンス化 -> _start が weights.bin を読み込む
              canvas 描画 -> JS が 24x24 に縮小・中心化・二値化して平坦化
              -> 文字列 "(0.0 1.0 ...)" を recognize() に渡す（:s-expr ABI）
              -> "pred <i> <romaji>" と各クラスのスコアを受け取り、かなで表示
```

各ファイルは連結ではなく `(load ...)` で合成します。コンパイラではトップレベルのリテラル
`(load ...)` は**コンパイル時インクルード**として展開され、相対パスは**その `load` を書いた
ファイルからの相対**で解決されます（だからデモは deep-learning-from-scratch のレイヤ群を
そのまま読み込めます）。

### 重みを「焼き込まない」

旧デモは重みを Lisp のリテラルとして推論プログラムに焼き込んでいました。JVM バックエンドは
スタックマップを持たないクラス版 50 を出力するため、**焼き込む浮動小数定数が概ね 1.3 万個を
超えるとクラスがロードできなくなり**、隠れ層 20・重み 1.25 万個という
「4 バックエンドすべてで動く上限」がモデルの上限そのものになっていました。

いまは重みを **`weights.bin`（RLW1 バイナリ）から起動時に読み込みます**。定数として焼き込まないので
この上限は効かず、パラメータは 15 万個（604KB）に増えました。コンパイラ側の課題
（大量の定数を焼き込むプログラム一般）は未解決のままですが、**このデモはもう当たりません**。

- 書き出し: `net.lisp` の `save-rlw1`（`write-byte` でビッグエンディアン f32）
- 読み込み: `deep-learning-from-scratch/dataset/rlw1.lisp` の `load-rlw1`（本の学習済み重みと同じ形式）
- ブラウザ: `wasi-shim.js` に**読み込み専用の仮想ファイルシステム**を足し、fetch した
  `weights.bin` を WASI の `path_open`/`fd_read` に見せています（WASM 側は「ファイルを開いて
  読む」だけで、ブラウザを意識しません）。

### なぜ「1 回だけインスタンス化」なのか

起動時の重み読み込みは 15 万パラメータ分の `read-byte`（ブラウザで約 330ms）です。ストロークごとに
モジュールを作り直すとこれを毎回払うことになるため、`recognize.lisp` はネットを
`rontolisp:wasm-export` で**ホストから呼べる関数**として公開しています。ページはモジュールを
1 回だけインスタンス化して `_start`（= 重みロード）を走らせ、以後は `recognize()` を呼ぶだけ
——**1 認識あたり約 27ms**（Chrome 実測）です。

## ビルド

リポジトリルートで JAR を用意し、実データを一度だけ準備してから `gen.sh` を実行します。

```bash
./mvnw clean package                                       # target/rontolisp-...-exec.jar
python3 examples/browser/hiragana/tools/k49/prepare-k49.py # K49 を DL + 前処理（初回のみ・約80MB）
examples/browser/hiragana/gen.sh                           # 学習 + infer.wasm 生成
```

`gen.sh` は学習を **JVM コンパイル + `--simd`** で走らせます（実測 約 4 分半 / 12 エポック /
39,537 サンプル。畳み込みは im2col で `linalg:matmul` になるので `--simd` がそのまま効きます）。
同じプログラムはインタプリタでも動きますが数時間かかります。

参考字形（`prototypes.lisp` / `glyphs.js` / `samples/`）はコミット済みの生成物です。
フォント・解像度・対象文字を変えたときだけ再生成します（macOS のフォントが必要）。

```bash
examples/browser/hiragana/regen-glyphs.sh
```

## ブラウザで動かす

`fetch` で `.wasm` と `weights.bin` を読むため `http://` で配信してください。

```bash
python3 -m http.server 8000 --directory examples/browser/hiragana
# ブラウザで http://localhost:8000/ を開く
```

マスに字を書くと、書くそばから**リアルタイムで**予測クラスとスコアが更新されます
（ストロークごとに自動認識。「認識」ボタンは手動での再実行用）。

**ブラウザ要件**: WebAssembly GC 対応（Chrome/Edge 119+, Firefox 120+, Safari 18.2+）。

## ブラウザなしで試す（4 バックエンド）

canvas はブラウザ専用ですが、認識ロジックはバックエンド非依存です。`samples/` に各クラスの
参考字形を平坦化したビットマップ（`(0.0 1.0 ...)` 1 行）を置いてあります。`infer.lisp` は
それを標準入力から読み、`weights.bin` を**カレントディレクトリから**開くので、このディレクトリで
実行してください（WASM は `--dir .` のプリオープンが必要）。

```bash
JAR=../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
cd examples/browser/hiragana

# 1. インタプリタ
java -jar $JAR infer.lisp < samples/u.txt                  # -> pred 2 u

# 2. JVM（クラス名は出力ファイル名になるのでパスを含めない）
java -jar $JAR infer.lisp -o Infer.class && java -cp .:$JAR Infer < samples/u.txt

# 3. WASM Preview1
java -jar $JAR infer.lisp -o /tmp/infer-p1.wasm && \
  wasmtime run -W gc --dir . /tmp/infer-p1.wasm < samples/u.txt

# 4. WASM コンポーネント (WASI 0.3)
java -jar $JAR infer.lisp -o /tmp/infer-c.wasm --component && \
  wasmtime run -W gc=y --dir . /tmp/infer-c.wasm < samples/u.txt
```

4 バックエンドとも 46 個の参考字形をすべて正しく分類します（WASM は浮動小数の表示桁数だけが
異なります）。

## ファイル

| ファイル                       | 役割                                                        |
| ------------------------------ | ----------------------------------------------------------- |
| `net.lisp`                     | ネットワーク定義（ch07 SimpleConvNet の再利用）＋ RLW1 の書き出し |
| `dataset.lisp`                 | 学習データ（K49 バイナリの読み込み・合成字形の水増し）        |
| `train.lisp`                   | オフライン学習（Adam）→ `weights.bin` を出力                 |
| `infer.lisp`                   | CLI 推論（stdin のビットマップ → 予測。4 バックエンド共通）   |
| `recognize.lisp`               | ブラウザ用推論（`recognize` を `wasm-export`）→ `infer.wasm` |
| `gen.sh`                       | 学習 → `infer.wasm` 生成のパイプライン                      |
| `tools/k49/prepare-k49.py`     | K49 のダウンロードと前処理（[README](tools/k49/README.md)）  |
| `glyphgen/GlyphGen.java`       | 字形レンダラ（実フォント → 24x24 二値化。`prototypes.lisp` / `glyphs.js` / `samples/` を生成） |
| `regen-glyphs.sh`              | `GlyphGen.java` を実行する薄いラッパ                        |
| `prototypes.lisp`              | 各クラスの 24x24 字形テンプレート（生成物・合成データの種）   |
| `glyphs.js`                    | ブラウザ表示用の参考字形 `GLYPHS`/`KANA`/`ORDER`（生成物）    |
| `index.html`                   | canvas で描いて認識するブラウザページ                       |
| `wasi-shim.js`                 | WASI Preview1 シム（`../wasm-browser/` のコピー ＋ 仮想 FS）  |
| `samples/*.txt`                | ブラウザなし確認用の平坦化ビットマップ（全 46 クラス・生成物） |
| `weights.bin`                  | 学習済みの重み（生成物・コミット対象。ブラウザが fetch する） |
| `infer.wasm`                   | 推論モジュール（生成物・コミット対象）                       |

## 限界

- **K49 は崩し字（古典籍のくずし字）**で、現代の手書きとは字形分布がずれます。合成フォント字形を
  混ぜて現代の字形を押さえていますが、実手書き 76% という数字は、このずれと 24x24 二値・小さな
  CNN という制約の合計です。ネットを大きくすれば伸びますが、学習時間と `weights.bin` が増えます。
- 字形が似たかな（は/ほ/ま、ね/れ/わ、る/ろ、さ/き など）では依然として混同が起きます。
- 濁点・半濁点・拗音（が・ぱ・きゃ 等）は対象外です。
- ブラウザのシムの仮想 FS は**読み込み専用**です（データファイルは読めますが書き込みはできません）。
