# 時計と乱数

プログラムが自分では出せない値が 2 つあります: 今が何時か、そして誰にも予測できない数です。どちらも外から来るので、バックエンドによって振る舞いが変わる組であり、ホストを**持たない**モジュールが答えを用意しなければならない組でもあります。

## 値はどこから来るか

| ビルド | `(random n)` | `rontolisp:random-bytes` | 時計 |
| --- | --- | --- | --- |
| インタプリタ、JVM | JVM の生成器 | 動く | マシンの時計 |
| WASM(デフォルト、Preview 1) | ホストの WASI `random_get` | 動く | WASI `clock_time_get` |
| WASM `--component` | ホストの `wasi:random` | 動く | `wasi:clocks` |
| WASM `--no-wasi` | 組み込みの生成器。**どのインスタンスも同じ列** | シグナル | ホストが `__ronto_set_time` で書き込んだ値。書き込まれるまではシグナル |
| WASM `--no-wasi --host-random` | ホストの `env.random_get` | 動く | 上と同じ |

判断が必要なのは `--no-wasi` の行だけです。それ以外ではどちらの値もホスト自身のものであり、このページの残りは最後のケースの話です。

## 乱数

Common Lisp の [`random`](../reference/functions/random.md) はエントロピー API ではなく
`*random-state*` からの疑似乱数の draw です: 予測不可能性は契約のどこでも約束されておらず、イメージが固定の状態から始まることも許されています。それを約束するのは別の
API である [`rontolisp:random-bytes`](../reference/functions/rontolisp-random-bytes.md) で、本物のエントロピー源があるところでのみ利用できます。

```lisp
(print (list (random 1) (< (random 10) 10)))   ; => (0 T)
```

結果の型は limit に従います — 整数の limit なら整数、浮動小数点数の limit なら浮動小数点数 —
つまり `(random 1)` は常に `0` です。

## 時計

[`get-universal-time`](../reference/functions/get-universal-time.md) は 1900-01-01 GMT からの秒数を、[`get-internal-real-time`](../reference/functions/get-internal-real-time.md)
と [`get-internal-run-time`](../reference/functions/get-internal-run-time.md)
はミリ秒を返します。後者 2 つは差分だけが意味を持ちます。3 つとも**すべてのバックエンドで整数**を返します。

```lisp
(print (list (integerp (get-universal-time)) (>= (get-internal-real-time) 0)))   ; => (T T)
```

[`encode-universal-time`](../reference/functions/encode-universal-time.md) と
[`decode-universal-time`](../reference/functions/decode-universal-time.md)
はその整数とカレンダー要素を純粋な算術で相互変換するので、どこでも同一に振る舞います — ただし意図的な差異が 1
つあります: タイムゾーンを省略すると**マシンのローカルゾーンではなく GMT**
になります。ローカルゾーンをバックエンド横断で得る手段が存在しない(WASI はタイムゾーンを一切公開していない)ためです。

待機は時計のもう半分です。[`sleep`](../reference/functions/sleep.md)
はインタプリタと JVM ではスレッドを停止させ、`--component` では本物のホストタイマーを待ち(CPU
を消費しません)、WASM Preview 1 ではクロックをビジーウェイトします(インポートにクロックはあってもタイマーが無いためです)。`--no-wasi`
ではシグナルします — 後述します。

## ホストを持たないモジュール — `--no-wasi`

[`--no-wasi` モジュール](wasm-gc-module.md#no-wasi-reactor-mode)は何もインポートしないので、どちらの値にも出どころがありません。それに対する扱いは、このフラグ全体が従うルールに従います:
**答えがそのモジュールにとって真であるときスタブは答え、答えることが「本物と区別できない値の捏造」になるときは拒否する。**
そして**ホストが渡してきた値は捏造ではありません** — 以下の 2 つのフックはそのためにあります。

乱数はそれ自体で「答える」側に落ちます。モジュールは自前の生成器を持ち、これは
`random` の契約の内側です — ここでは `make-random-state` が `nil`
を返すため状態オブジェクトは観測できず、「列が繰り返す」ことはホストについての主張ではなく契約の性質だからです。その帰結ははっきり述べておく価値があります:
**シードを与えなければ、同じモジュールのどのインスタンスも同じ列を生成します。** この生成器はエントロピーではないので、`rontolisp:random-bytes`
はそこから引かずにシグナルします。

時計はそれ自体で「拒否する」側に落ちます: 読み値 0 は「時刻なし」ではなく 1970
年であり、モジュールが捏造できる値のどれも「時刻」ではありません。したがってホストが設定するまで、3
つの組み込みはいずれも演算子名を含む捕捉可能なエラーをシグナルします。

ロード中に時計を読むライブラリにはそれを捕捉する呼び出し元がいないので、最初の実行任せにせず**ビルド**が名指しします —
[実行する前にビルドが教えてくれること](wasm-gc-module.md#what-the-build-tells-you-before-you-run-it)を参照してください。

### 生成器にシードを与える — `__ronto_seed_random`

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

### 時計を設定する — `__ronto_set_time`

時計のフックも同じ形で、単位は **Unix エポックからのナノ秒**です:

```js
const instance = new WebAssembly.Instance(module, {});
instance.exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
instance.exports._initialize();
```

`_initialize` の前に呼ぶことが、ロード中にタイムスタンプを取るライブラリをそもそもロード可能にします —
`lack-middleware-session` はトップレベルフォームで時計を読むので、これがないとモジュールは最初のリクエストではなく初期化中に死にます。

この時計は自分では進みません。次に書き込むまで、書き込んだ値を保持します。これは思うほどの制約ではなく(Cloudflare Worker
自身の時計もタイミング攻撃対策としてリクエスト中は凍結されています)、自然なリズムはリクエストごとに 1 回設定することです —
[Worker の例](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers)がそうしています。唯一できないのは待機です:
呼び出しの実行中に時間を経過させる手段がないため、`(sleep n)` はここではシグナルします。

シードフックと同様、これもコアモジュール形態だけにあります。リアクターコンポーネントはトップレベルをインスタンス化時に実行するため、ホストが先に時刻を設定できる瞬間が存在しません。そこでは時計はシグナルし、その旨を述べます。

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
はエクスポートされません — シードすべきモジュール内の状態がもう残っていないからです。`__ronto_set_time`
は影響を受けません: 2 つのサービスは独立で、モジュール内の生成器を持つのは一方だけだからです。

インポートゼロというデフォルトは変わりません。これはオプトインであり、モジュールはホストが**必ず**提供しなければならないインポートを 1
つ持つことになります。プログラムが一度も draw しなければ `--optimize` はそのインポートを落とします。このフラグはコアモジュール専用です:
リアクターコンポーネントは契約として何もインポートせず、素の `--component` ビルドはすでに `wasi:random` を持っています。

`--host-clock` に相当するものはありません。エクスポートするフックが、インポートゼロという性質を失わずに同じ問いに答えられるからです。呼び出しの実行*中*に進む生きた時計が必要になればそれが要りますが、今のところ必要になった事例はありません。

## `random` の再定義

プログラム自身の `(defun random ...)`
はインタプリタからは呼ばれますが、コンパイルバックエンドでは無視され、呼び出し位置には標準の演算子が出力されます(そのことは警告されます) —
[COMMON-LISP 関数の再定義](../reference/function-namespace.md#redefining-a-common-lisp-function)を参照してください。
