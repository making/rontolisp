# 乱数 (random)

Common Lisp の [`random`](../reference/functions/random.md) はエントロピー API ではなく
`*random-state*` からの疑似乱数の draw です: 予測不可能性は契約のどこでも約束されておらず、イメージが固定の状態から始まることも許されています。それを約束するのは別の
API である [`rontolisp:random-bytes`](../reference/functions/rontolisp-random-bytes.md) で、本物のエントロピー源があるところでのみ利用できます。

```lisp
(print (list (random 1) (< (random 10) 10)))   ; => (0 T)
```

結果の型は limit に従います — 整数の limit なら整数、浮動小数点数の limit なら浮動小数点数 —
つまり `(random 1)` は常に `0` です。

## 乱数はどこから来るか

| ビルド | `(random n)` | `rontolisp:random-bytes` |
| --- | --- | --- |
| インタプリタ、JVM | JVM の生成器 | 動く |
| WASM(デフォルト、Preview 1) | ホストの WASI `random_get` | 動く |
| WASM `--component` | ホストの `wasi:random` | 動く |
| WASM `--no-wasi` | 組み込みの生成器。**どのインスタンスも同じ列** | シグナル |
| WASM `--no-wasi --host-random` | ホストの `env.random_get` | 動く |

判断が必要なのは最後の 2 行だけです。それ以外はいずれもホスト自身の生成器です。

## リアクター自前の生成器 (`--no-wasi`)

[`--no-wasi` モジュール](wasm-gc-module.md#no-wasiリアクターモード)は何もインポートしないので、到達できるホストの生成器がありません。代わりに自前の生成器を持ちます。これは
`random` の契約の内側です — ここでは `make-random-state` が `nil`
を返すため状態オブジェクトは観測できず、「列が繰り返す」ことはホストについての主張ではなく契約の性質だからです。その帰結ははっきり述べておく価値があります:
**シードを与えなければ、同じモジュールのどのインスタンスも同じ列を生成します。** この生成器はエントロピーではないので、`rontolisp:random-bytes`
はそこから引かずにシグナルします。

抜け道は 2 つあり、それぞれ別の問いに答えます。

### ホストからシードを与える — `__ronto_seed_random`

モジュールはデフォルトではホストの random を*インポート*できません: コア WebAssembly のインポートは省略可能ではないので、エントロピーをインポートするとフラグの目的そのもの(`{}`
でインスタンス化できること)が壊れます。代わりにフックをエクスポートします。**`_initialize`
の前に**一度呼べば、ライブラリのロード時の `(random ...)` すらそのシードから引きます:

```js
const instance = new WebAssembly.Instance(module, {});
instance.exports.__ronto_seed_random(
  new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0],
);
instance.exports._initialize();
```

呼ばなければ決定的な列のままです。このフックはコアモジュール形態だけにあります — リアクターコンポーネント(`--component --no-wasi`)はトップレベルをインスタンス化時に実行するため、最初の
draw より前の隙間が存在しません。

シードを与えると列はインスタンスごとに予測困難になりますが、`rontolisp:random-bytes` が有効になることは**ありません**:
この生成器は 1 出力から逆算できるため、シード済みの列は暗号学的に強くはなく、エントロピーを約束する API は CSPRNG
に見えるだけのものを渡すより拒否し続けます。

### ホストから直接引く — `--host-random`

`--host-random` は組み込みの生成器をホスト呼び出しに置き換えます。つまりすべての draw
がホストのエントロピーになります — quickload
したライブラリの中の draw も含めて。ライブラリ側はバイトの出どころを知りません:

```bash
rontolisp app.lisp --no-wasi --host-random -o app.wasm
```

モジュールはこのときちょうど 1 つ、`env.random_get(buf, len) -> errno` をインポートします。これは preview1
のシグネチャそのものなので、すでに WASI 実装を持つホストはそれをそのまま渡せます。JavaScript からならプロパティ 1 つです:

```js
const instance = await WebAssembly.instantiate(module, {
  env: {
    random_get(ptr, len) {
      crypto.getRandomValues(new Uint8Array(instance.exports.memory.buffer, ptr, len));
      return 0;                                  // errno 0 = success
    },
  },
});
instance.exports._initialize();
```

エントロピーが本当にホストのものなので、ここでは `rontolisp:random-bytes` が動きます。`__ronto_seed_random`
はエクスポートされません — シードすべきモジュール内の状態がもう残っていないからです。

インポートゼロというデフォルトは変わりません。これはオプトインであり、モジュールはホストが**必ず**提供しなければならないインポートを 1
つ持つことになります。プログラムが一度も draw しなければ `--optimize` はそのインポートを落とします。このフラグはコアモジュール専用です:
リアクターコンポーネントは契約として何もインポートせず、素の `--component` ビルドはすでに `wasi:random` を持っています。

## `random` の再定義

プログラム自身の `(defun random ...)`
はインタプリタからは呼ばれますが、コンパイルバックエンドでは無視され、呼び出し位置には標準の演算子が出力されます(そのことは警告されます) —
[COMMON-LISP 関数の再定義](../reference/function-namespace.md#common-lisp-関数の再定義)を参照してください。
