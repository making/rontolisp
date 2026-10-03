# c42. Clojure: `conj` onto a map accepts a `(k v)` list and a set of vectors, the oracle refuses

Difficulty: Low

`(conj {} '(1 2))`, `(into {} ['(1 2)])`, `(merge {} '(1 2))` and `(conj {} #{[1 2]})` answer `{1 2}` here; the
oracle (`clj` 1.12.6) signals `ClassCastException` for each (a list is no `Map.Entry`, and a set's members are
cast to `Map.Entry` too, so a vector member fails). Only a map, a `[k v]` vector and nil are entries of a
direct item; a set's members must be map entries (which are plain 2-vectors here, so a vector member of a set
has to stay accepted unless entries get their own representation: decide, and record the choice in
`.kb/clojure-frontend.md`).

The accepting arms are the `(k v)` cons arms of `ClojureCollectionLowering.entryPlist` / `memberEntryPlist`
and `rontolisp::%clojure-sorted-entry-plist`. Check first whether a program or spec relies on the list entry,
then pin the refusal in `clojure-spec.yaml`.
