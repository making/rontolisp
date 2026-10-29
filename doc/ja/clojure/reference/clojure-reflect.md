# clojure.reflect

ホストのクラスの基底、フラグ、メンバーをデータとして返す名前空間です。`clojure.reflect` を
require すると使えます。Clojure の同名の名前空間について文書化された振る舞いをもとに、ホストの
`java.lang.reflect` の上に rontolisp 向けに書いた Clojure ソースで、インタープリターと JVM で
リフレクションします。WebAssembly のターゲットにはホストのクラスがないため、そこでは名前空間は
読み込めますが、リフレクションは呼び出した時点で `UnsupportedOperationException` を投げます。

| var | 振る舞い |
|---|---|
| `type-reflect` | `(type-reflect typeref & options)`: クラスまたはシンボルの `typeref` が示す型の `{:bases :flags :members}` を返す。`:bases` はスーパークラスとインターフェースのシンボルの集合（なければ `nil`）、`:flags` はクラスのフラグのキーワード、`:members` は宣言されたコンストラクター、メソッド、フィールドの集合。オプションは、`:ancestors true` ですべての祖先のシンボルを `:ancestors` に加え、そのメンバーを `:members` に合わせる。`:reflector` は問い合わせる `Reflector`（既定はコンテキストクラスローダー上の `JavaReflector`） |
| `reflect` | `(reflect obj & options)`: `obj` がクラスならその、そうでなければそのクラスの `type-reflect` |
| `Constructor`、`Method`、`Field` | メンバーのレコード。`:name` はシンボル、`:return-type`/`:type`/`:declaring-class` はシンボル、`:parameter-types`/`:exception-types` はシンボルのベクター、`:flags` はキーワードの集合。`->Method`、`map->Method` などで作る |
| `typename` | Java の綴りでの型名。`"java.lang.String"`、配列型は次元ごとに `[]` を付ける。シンボルでは配列型を次元ごとの `<>` で綴る |
| `flag-descriptors` | 報告するフラグ。それぞれ `{:name :flag :contexts}` で、キーワード、ビット、意味を持つ文脈（`:class`、`:method`、`:field`） |
| `Reflector`、`do-reflect` | `type-reflect` が問い合わせるプロトコル。`->JavaReflector` はクラスローダー上のものを作る |
| `TypeReference` | `typename` のプロトコル。クラスとシンボルに拡張してある |
| `ClassResolver`、`resolve-class` | `(resolve-class loader name)`: `name` が示す型のクラスファイルを、クラスローダーを通して入力ストリームで返す |

```console
clojure> (require '[clojure.reflect :as reflect])
nil
clojure> (reflect/type-reflect Runnable)
{:bases nil, :flags #{:public :interface :abstract}, :members #{#clojure.reflect.Method{:name run, :return-type void, :declaring-class java.lang.Runnable, :parameter-types [], :exception-types [], :flags #{:public :abstract}}}}
clojure> (sort (map :name (filter #(contains? (:flags %) :varargs) (:members (reflect/reflect "")))))
(format format formatted join)
clojure> (reflect/typename 'java.lang.String<>)
"java.lang.String[]"
```

## 違い

- `->AsmReflector` は組み込まれていません。clojure.jar に同梱の ASM ライブラリでクラスファイルを
  読むためです。
- Clojure のコレクションのクラスはホストにない `clojure.lang` のクラスなので、その `reflect` は
  `ClassNotFoundException` を投げます。型付き配列はベクターなので、`(int-array 0)` の `reflect`
  も同じです。配列のクラスは `(Class/forName "[I")` のように名前で指定します。
- `Long` と `Double`、および数値のクラスはここではキーワードです。`reflect` と `type-reflect` は
  それを、それが示すクラスとして扱います。
