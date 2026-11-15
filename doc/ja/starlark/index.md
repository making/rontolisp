# Starlark（実験的）

**実験的。** rontolisp は
[Starlark](https://github.com/bazelbuild/starlark/blob/master/spec.md) を読みます。Starlark は
Bazel のビルドファイルを書くための Python の方言です。読んだプログラムは Common Lisp と同じ
コアフォームに変換されるので、`.star` のプログラムはどのバックエンドでも動きます。準拠は
設計上部分的で、ここにあるものは互換性の保証ではありません。JVM や WebAssembly 上で
Starlark のプログラムを試すために使ってください。ずっと動かし続けたいものは Common Lisp で
書いてください。

`.star` のファイルは Starlark として読まれます。それ以外のファイルと `-e` では
`--source-language starlark` で指定します。言語はファイルごとに選ばれます。
`--starlark-standard spec` を指定すると、既定では緩めているトップレベルと再帰についての
仕様の規則を適用します（[仕様との差異](deviations.md)）。

```bash
rontolisp hello.star                                # interpreter
rontolisp hello.star -o Hello.class && java Hello   # JVM
rontolisp hello.star -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.star -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp -e 'print(1 + 2)' --source-language starlark
```

`--no-gc` は拒否されます。そのバックエンドには構造体もハッシュテーブルもクロージャもない
からです。REPL はまだありません。プログラムを与えずに `--source-language starlark` を
指定すると拒否され、このセクションの例もブラウザでは実行できません。

[`examples/starlark/`](https://github.com/making/rontolisp/tree/develop/examples/starlark)
に、期待される出力付きで実行できるプログラムがあります。

```starlark
def fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a

def describe(name, *args, sep = ", ", **kwargs):
    parts = [str(a) for a in args] + ["%s=%r" % (k, v) for k, v in kwargs.items()]
    return "{}({})".format(name, sep.join(parts))

print([fib(i) for i in range(10)], fib(100))
print(describe("f", 1, 2.5, None, x = "a", y = [1, (2,)]))
words = "the quick brown fox jumps over the lazy dog".split()
print(sorted(words, key = len, reverse = True)[:3], {w: len(w) for w in words if len(w) > 4})
```

```
[0, 1, 1, 2, 3, 5, 8, 13, 21, 34] 354224848179261915075
f(1, 2.5, None, x="a", y=[1, (2,)])
["quick", "brown", "jumps"] {"quick": 5, "brown": 5, "jumps": 5}
```

## 読めるもの

仕様の文法をすべて読みます:

- デフォルト値、`*args`、キーワード専用パラメータ、`**kwargs` を持つ `def`。`lambda`。
  外側の変数を読むネストした関数。
- `if` / `elif` / `else`、`break` と `continue` を持つ `for`、`return`、`pass`。
- 名前、要素（`a[i] = v`）、ターゲットのタプルやリストへの代入と、累算代入（`+=`、`//=`、
  `<<=` など）。
- リスト内包表記と辞書内包表記、条件式、添字とスライス、`and` / `or` / `not`、比較、`in`、
  算術演算子とビット演算子、文字列への `%`。
- `load`。ほかの Starlark ファイル（[モジュール](#modules)）と、Common Lisp のパッケージの
  関数（[Common Lisp との混在](common-lisp.md)）を読み込みます。

値は `None`、`True` と `False`、大きさに制限のない整数、浮動小数点数、文字列、リスト、
タプル、辞書、range、関数です。

## 組み込み関数

| 関数 | 返すもの |
|---|---|
| `abs(x)` | 整数または浮動小数点数の絶対値 |
| `all(x)`、`any(x)` | すべての要素が真か、いずれかの要素が真か |
| `bool(x=False)` | `x` の真偽 |
| `dict(pairs, **kwargs)` | 新しい辞書。辞書またはペアのイテラブルの項目に、名前付き引数を加えたもの |
| `dir(x)` | `x` のメソッド名をソートした新しいリスト |
| `enumerate(x, start=0)` | `(添字, 要素)` のタプルのリスト |
| `fail(*args, sep=" ")` | 何も返しません。引数をエラーとしてプログラムを終了します |
| `float(x=0)` | 整数、浮動小数点数、真偽値、文字列（`"1e3"`、`"inf"`、`"nan"`）から作った浮動小数点数 |
| `getattr(x, name, default)` | `x` に束縛したメソッド `name`。なければ `default`、それもなければエラー |
| `hasattr(x, name)` | `x` がメソッド `name` を持つか |
| `hash(x)` | 文字列のハッシュ値 |
| `int(x, base)` | 整数、浮動小数点数、真偽値、または 2 進から 36 進の文字列から作った整数（`base=0` は `0x`、`0o`、`0b` の接頭辞を読みます） |
| `len(x)` | 文字列、リスト、タプル、辞書、range の要素数 |
| `list(x)`、`tuple(x)` | イテラブルの要素からなる新しいリスト、またはタプル |
| `max(*args, key=None)`、`min(*args, key=None)` | 引数の中、または 1 つだけ渡したイテラブルの中で最大、または最小の要素 |
| `print(*args, sep=" ")` | `None`。引数の `str` と改行を標準出力に書きます |
| `range(stop)`、`range(start, stop, step=1)` | 整数の range |
| `repr(x)`、`str(x)` | `x` を表す文字列。`repr` は文字列を二重引用符で囲みます |
| `reversed(x)` | 逆順の新しいリスト |
| `sorted(x, key=None, reverse=False)` | ソートした新しいリスト。等しい要素の順序は保たれます |
| `type(x)` | `x` の型の名前 |
| `zip(*iterables)` | タプルのリスト。長さは最も短いイテラブルに合わせます |

## メソッド

| 型 | メソッド |
|---|---|
| list | `append`, `clear`, `extend`, `index`, `insert`, `pop`, `remove` |
| dict | `clear`, `get`, `items`, `keys`, `pop`, `popitem`, `setdefault`, `update`, `values` |
| string | `capitalize`, `codepoint_ords`, `codepoints`, `count`, `elem_ords`, `elems`, `endswith`, `find`, `format`, `index`, `isalnum`, `isalpha`, `isdigit`, `islower`, `isspace`, `istitle`, `isupper`, `join`, `lower`, `lstrip`, `partition`, `removeprefix`, `removesuffix`, `replace`, `rfind`, `rindex`, `rpartition`, `rsplit`, `rstrip`, `split`, `splitlines`, `startswith`, `strip`, `title`, `upper` |

メソッドはレシーバに対して呼び出すか、レシーバに束縛された値として取り出します:

```starlark
log = []
push = log.append
push("a")
print(push, log, "banana".count("an"), {"k": 1}.get("missing", 0))
```

```
<built-in method append of list value> ["a"] 2 0
```

リストや辞書は、それを走査するループの実行中には変更できません。ほかのエラーと同じく、
変更しようとするとプログラムが終了します:

```console
$ cat loop.star
xs = [1, 2]
for x in xs:
    xs.append(x)
$ rontolisp loop.star
Unhandled condition: cannot append to list during iteration
  at loop.star:3
```

代入前の変数を読むことも、ローカル変数かグローバル変数かにかかわらず同じくエラーです。
`def` より上に書いたその関数の呼び出しもエラーです。

## モジュール

`load("words.star", "shout", count = "COUNT")` はほかのファイルを実行し、そのグローバル変数を
読み込む側のファイルの名前に束縛します。`shout` はモジュールの `shout` に、`count` は
モジュールの `COUNT` に束縛されます。パスは、この文を書いたファイルからの相対パスです。

- モジュールは、いくつのファイルから読み込まれても、プログラムごとに 1 回だけ実行されます。
  実行されるのは、最初の `load` の位置です。
- モジュールのグローバル変数はそのモジュールだけのものです。2 つのファイルがどちらも
  `helper` を定義できます。
- アンダースコアで始まる名前は読み込めません。モジュール自身が `load` で得た名前も
  読み込めません。
- ファイルがない場合、モジュールが定義していない名前を指定した場合、読み込む側のファイルを
  モジュールが読み込み返す場合は、プログラムの実行前にエラーになります。
- モジュールはコンパイル時に読まれるので、コンパイルしたプログラムにはモジュールが
  含まれます。

**ファイルの実行が終わると、その実行中に作られたリストと辞書は凍結されます。** 凍結された
値を変更しようとするとエラーになります。そのファイルの関数があとで呼び出されたときに作る
リストや辞書は、呼び出した側のものです。凍結されたリストは辞書のキーにできます。

```console
$ cat words.star
print("words runs")
seen = ["start"]
def shout(s):
    return s.upper() + "!"
$ cat main.star
load("words.star", "shout", "seen")
print(shout("hello"), seen, {seen: 1})
seen.append("more")
$ rontolisp main.star
words runs
HELLO! ["start"] {["start"]: 1}
Unhandled condition: cannot append to frozen list
  at main.star:3
```

## このセクション

- [Common Lisp との混在](common-lisp.md) -- 1 つのプログラムの中で、Common Lisp のファイルと
  Starlark のファイルが互いにどう見えるか。
- [仕様との差異](deviations.md) -- このフロントエンドが仕様と異なる点と、まだないもの。
