# extenders

`(extenders protocol)`

The classes the protocol was extended to, as a seq, like the oracle's: the
extended tags in walk order (`:object` last for the `Object` default), which
are the keywords `class` answers. A value holding no table (a non-protocol)
extends to nothing (`nil`), like the oracle's. As a value one argument; a
computed protocol reads the method table itself, which holds no `Object`
default, so the default shows only through a literal protocol name.

```clojure
(defprotocol Sip (sip [x]))
(defrecord Mug [size])
(extend-protocol Sip Mug (sip [x] :drunk))
(println (= (count (extenders Sip)) 1)) ; true
```
