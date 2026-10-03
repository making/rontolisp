# Type and collection predicates

The predicates over a value's kind. Each answers `true` or `false`, and every one but
`extends?` is also a function value of the same arguments. The answers follow the representation
([Deviations](../deviations.md)): `nil` is the empty list, a seq a verb answers over a strict
input is a list, and decimal and `N` literals read as plain rationals. A predicate over a
kind no value here has (`sorted?`, `delay?`, `future?`, `decimal?`, ...) answers `false`.
`coll?` and the other predicates are on [Numbers and predicates](numbers.md).

| Name | Example | Result |
|---|---|---|
| `seq?` | `(seq? '(1 2))` | `true` |
| `sequential?` | `(sequential? [1])` | `true` |
| `map?` | `(map? {:a 1})` | `true` |
| `set?` | `(set? #{1})` | `true` |
| `list?` | `(list? '(1))` | `true` |
| `record?` | `(record? {:a 1})` | `false` |
| `sorted?` | `(sorted? [1 2])` | `false` |
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
| `extends?` | `(extends? P R)` | `true` when `R` implements `P` |
| `special-symbol?` | `(special-symbol? 'if)` | `true` |
| `reader-conditional?` | `(reader-conditional? '(1))` | `false` |
| `tagged-literal?` | `(tagged-literal? 1)` | `false` |
