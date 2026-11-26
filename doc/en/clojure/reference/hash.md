# hash

`(hash x)`

`clojure.core/hash`: the hash consistent with `=`, the oracle's `Util.hasheq` and its
numbers: Murmur3 over a long, a string, a keyword's or symbol's name and namespace, and a
collection's members -- in order for a vector, list or seq, in any order for a map or set --
mixed with their count; a double, ratio, character, boolean, UUID and instant hash as their
Java `hashCode` (both zeros `0`), a record as the oracle's generated `hasheq`. A deftype or
reify answers its `IHashEq` `hasheq`, else its `hashCode` override, else its identity, like a
function or an atom: stable for the object's life, but not the oracle's number. As a value a
function of one argument.

```clojure
(prn (hash 1))                                     ; 1392991556
(prn (hash "a") (hash :a))                         ; 1455541201 -2123407586
(prn (= (hash [1 2]) (hash '(1 2))))               ; true
(prn (= (hash {:a 1}) (hash (sorted-map :a 1))))   ; true
```
