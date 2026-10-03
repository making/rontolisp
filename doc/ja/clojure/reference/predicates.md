# 型・コレクション述語

値の種類を調べる述語です。どれも `true` か `false` を返し、`extends?` 以外は同じ引数をとる関数としても使えます。
答えは値の表現に従います（[仕様との差異](../deviations.md)）。`nil` は空リストであり、strict な入力に対して
操作が返す seq はリストであり、decimal と `N` のリテラルは通常の有理数として読まれます。ここに
値が存在しない種類の述語（`delay?`・`future?`・`decimal?` など）は `false` を返します。ソート済みの
マップとセットはどの述語にもマップとセットとして扱われ、`sorted?` と `reversible?` も成り立ちます。
`coll?` などの述語は[数値と述語](numbers.md)にあります。

| Name | Example | Result |
|---|---|---|
| `seq?` | `(seq? '(1 2))` | `true` |
| `sequential?` | `(sequential? [1])` | `true` |
| `map?` | `(map? {:a 1})` | `true` |
| `set?` | `(set? #{1})` | `true` |
| `list?` | `(list? '(1))` | `true` |
| `record?` | `(record? {:a 1})` | `false` |
| `sorted?` | `(sorted? (sorted-set 1 2))` | `true` |
| `seqable?` | `(seqable? "ab")` | `true` |
| `associative?` | `(associative? [1])` | `true` |
| `counted?` | `(counted? [1])` | `true` |
| `indexed?` | `(indexed? [1])` | `true` |
| `reversible?` | `(reversible? [1])` | `true` |
| `ifn?` | `(ifn? :a)` | `true` |
| `chunked-seq?` | `(chunked-seq? (seq [1 2]))` | `false` |
| `number?` | `(number? 1/2)` | `true` |
| `integer?` | `(integer? 1.0)` | `false` |
| `int?` | `(int? 1)` | `true` |
| `double?` | `(double? 1.5)` | `true` |
| `float?` | `(float? 1.5)` | `true` |
| `decimal?` | `(decimal? 1.5)` | `false` |
| `ratio?` | `(ratio? 1/2)` | `true` |
| `rational?` | `(rational? 0.5)` | `false` |
| `nat-int?` | `(nat-int? 0)` | `true` |
| `pos-int?` | `(pos-int? 0)` | `false` |
| `neg-int?` | `(neg-int? -1)` | `true` |
| `infinite?` | `(infinite? ##-Inf)` | `true` |
| `NaN?` | `(NaN? ##NaN)` | `true` |
| `keyword?` | `(keyword? :a)` | `true` |
| `ident?` | `(ident? 'a)` | `true` |
| `simple-ident?` | `(simple-ident? :a)` | `true` |
| `qualified-ident?` | `(qualified-ident? :a/b)` | `true` |
| `simple-keyword?` | `(simple-keyword? :a)` | `true` |
| `qualified-keyword?` | `(qualified-keyword? :a/b)` | `true` |
| `simple-symbol?` | `(simple-symbol? 'a)` | `true` |
| `qualified-symbol?` | `(qualified-symbol? 'a/b)` | `true` |
| `char?` | `(char? \a)` | `true` |
| `bytes?` | `(bytes? [1 2])` | `false` |
| `any?` | `(any? nil)` | `true` |
| `not-any?` | `(not-any? odd? [2 4])` | `true` |
| `not-every?` | `(not-every? odd? [1 2])` | `true` |
| `distinct?` | `(distinct? 1 2 1)` | `false` |
| `identical?` | `(identical? :a :a)` | `true` |
| `inst?` | `(inst? "2020-01-01")` | `false` |
| `uuid?` | `(uuid? "x")` | `false` |
| `uri?` | `(uri? "http://a")` | `false` |
| `var?` | `(var? 'x)` | `false` |
| `volatile?` | `(volatile? (atom 1))` | `false` |
| `delay?` | `(delay? 1)` | `false` |
| `future?` | `(future? 1)` | `false` |
| `realized?` | `(realized? (lazy-seq nil))` | `false` |
| `bound?` | `(bound?)` | `true` |
| `class?` | `(class? 1)` | `false` |
| `extends?` | `(extends? P R)` | `R` が `P` を実装していれば `true` |
| `special-symbol?` | `(special-symbol? 'if)` | `true` |
| `reader-conditional?` | `(reader-conditional? '(1))` | `false` |
| `tagged-literal?` | `(tagged-literal? 1)` | `false` |
