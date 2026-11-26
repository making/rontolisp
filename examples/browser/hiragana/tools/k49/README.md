# 実データ (Kuzushiji-49) の取得と前処理

デモの CNN は、**実際の手書きかな**と合成フォント字形の混合で学習します。その実データ側を
用意するのがこのツールです。学習そのものは rontolisp（`../../train.lisp`）で行うので、
ここでやるのは 2 つだけです。

1. Kuzushiji-49 の `.npz`（約 80MB）をダウンロードしてキャッシュする
2. 各画像を**ブラウザと同じ前処理**（インクの外接矩形で切り出し → 縦横比を保って 24x24 に
   中心化 → 0.35 で二値化）にかけ、rontolisp が `read-byte` で読める素朴なバイナリに書き出す

rontolisp は `.npz`（NumPy の zip）を読めないので、この 2 つだけを外部で済ませます。

## 必要なもの

- Python 3 + NumPy + Pillow（`pip install numpy pillow`）

## 使い方

```bash
python3 examples/browser/hiragana/tools/k49/prepare-k49.py
```

出力（`.gitignore` 済み。リポジトリにはコミットしません）:

| ファイル | 中身 |
| --- | --- |
| `tools/k49/data/*.npz` | ダウンロードした元データのキャッシュ |
| `../../data/k49-train.bin` | 学習用（既定: 1 クラス最大 800 枚 → 36,777 枚） |
| `../../data/k49-test.bin` | 評価用（既定: 1 クラス最大 100 枚 → 4,600 枚） |

主なオプション:

```bash
python3 prepare-k49.py --per-class-cap 0        # クラス上限なし（全 23 万枚。学習は長くなる）
                       --test-per-class-cap 200
```

## HKB1 フォーマット

`dataset.lisp` の `k49-load` が先頭から順に読むだけの形式です（シークは使いません）。

```
"HKB1"                    4 bytes
count                     u32 big-endian
grid                      u8  (24)
count 個のレコード:
  label                   u8  (0..45、デモのかな順)
  pixels                  grid*grid bytes (0 か 1、行優先)
```

## クラス

K49 の 49 クラスのうち、合成字形に対応がない **ゐ (wi) / ゑ (we) / 繰り返し記号 ゝ** を落とし、
残り 46 クラスをデモの五十音順（`*romaji*`）に振り直しています。

## ライセンス

Kuzushiji-49 (c) ROIS-DS 人文学オープンデータ共同利用センター (CODH),
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) —
<http://codh.rois.ac.jp/kmnist/>
