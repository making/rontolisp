# second

`(second coll)`

Answers the member past the head of `coll`'s seq view, or `nil`: a lazy input realizes
only its first two members, and a map or a set answers its second entry or member (where
`nth` refuses them). As a value a one-argument lambda.

```clojure
(println (second [1 2 3])) ; 2
```
