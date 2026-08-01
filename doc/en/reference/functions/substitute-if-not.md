# substitute-if-not

`(substitute-if-not new predicate sequence &key key)`

The complement of [`substitute-if`](substitute-if.md): returns a new sequence in which every element the predicate *rejects* is replaced by `new`. Takes the same optional `:key` selector, keeps the sequence kind, and does not modify the original; the destructive version is [`nsubstitute-if-not`](nsubstitute-if-not.md).

```lisp
(substitute-if-not 0 #'oddp '(1 2 3 4 5)) ; => (1 0 3 0 5)
```

```lisp
(substitute-if-not 'keep #'stringp '("a" 1 "b")) ; => ("a" KEEP "b")
```
