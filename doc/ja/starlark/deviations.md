# 仕様との差異

準拠は設計上部分的です。動作が
[仕様](https://github.com/bazelbuild/starlark/blob/master/spec.md)と異なる箇所はここに
挙げたとおりで、どのバックエンドでも同じです。

## 動作が異なる点

**文字列はコードポイントの列です。** `len`、添字、スライスは Unicode のコードポイントを
数えます。仕様の 2 つの文字列モデルは、UTF-8 のバイトか UTF-16 のコード単位を数えます。
`hash` は仕様どおりの値です。

```starlark
print(len("𝄞"), "naïve"[2], "日本語"[::-1], hash("𝄞"))
```

```
1 ï 語本日 1772394
```

**`print` は標準出力に書きます。** 仕様は出力先をホストに任せており、Bazel では
標準エラー出力です。

**既定は寛容な方言です。** `if` と `for` をトップレベルに書けます。グローバル変数に
2 回以上代入できます。関数は自分自身を呼び出せます。Go の実装はそれぞれをオプションにして
います。`--starlark-standard spec` を指定すると、仕様どおり 3 つともエラーになります。
前の 2 つはプログラムの実行前に、同じ `def` や `lambda` の呼び出しの実行中にそれを呼ぶことは
呼び出した時点で（`function f called recursively`）エラーになります。

```console
$ cat count.star
for x in range(2):
    print(x)
$ rontolisp count.star
0
1
$ rontolisp --starlark-standard spec count.star
error: count.star:1:1: for loop not within a function
```

**凍結されるかどうかは、値がいつ作られたかで決まります。** 仕様は、ファイルの実行が
終わった時点でそのグローバル変数から到達できる値を凍結します。ここでは、ファイルの実行中に
作られたリストと辞書を、どのグローバル変数も保持していないものを含めてすべて凍結します。
凍結されたリストは仕様どおり辞書のキーにできますが、凍結された辞書はキーにできません
（Go と Java の実装はどちらも拒否します）。

そのほかの差異は次のとおりです:

- range のスライスはリストを返します。range は開始値を付けて `range(0, 5)` と印字されます。
- `isalpha`、`isdigit`、`isalnum`、`isspace` は ASCII の文字だけを判定します。
- エラーはプログラムを終了させます。標準エラー出力に `Unhandled condition: ` とメッセージが
  出て、続けて発生したファイルと行、およびそれを含む `def` が出ます（WebAssembly では
  [`--report-locations`](../compiling/wasm.md#uncaught-condition-locations---report-locations)
  を付けた場合）。終了ステータスは 0 以外になります。列まで示すのは、プログラムの実行前に
  見つかったエラーだけです。文面は Go の実装に合わせています。
- 数千要素のリスト式、タプル式、辞書式は JVM のクラスにコンパイルできません。JVM の
  メソッドに許される大きさを超えるためです。インタプリタと WebAssembly では動きます。
- エラーの報告は、モジュールの関数をモジュールのパス付きの名前で示します:
  `in sl%(words.star)shout`。
- エラーで止まったモジュールは、あとの `load` で読み込まれても再実行されません。

## まだないもの

- `b"..."` リテラル。プログラムの実行前にその位置で拒否されます。
- `bytes` 型と `set` 型、およびそれらを作る組み込み関数。
- REPL、プレイグラウンドでの言語選択、このセクションのページの Run セル。
