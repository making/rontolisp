# c37. Clojure: `conj` onto a hash map refuses `nil` and takes a keyword for an entry

Difficulty: Low

`(conj {:a 1} nil)` signals `conj needs a map entry: a map, a [k v] vector or a (k v) list`; the oracle
(`clj` 1.12.6) answers `{:a 1}` (a nil item adds nothing). `(conj {} :a)` answers the garbage map
`{:C%KEYWORD "a"}`: `ClojureCollectionLowering.entryPlist` reads any two-member cons as a `(k v)` list,
and a keyword is the wrapper `(:C%KEYWORD "a")`; the oracle signals (a keyword is no map entry). The same
`entryPlist`/`memberEntryPlist` shapes serve `into` and `merge`'s conj.

A sorted map's conj (`rontolisp::%clojure-sorted-entry-plist`) already takes nil and refuses a wrapper
(`(not (keywordp (car item)))`); the hash-map arm should agree. Pin both in `clojure-spec.yaml`.
