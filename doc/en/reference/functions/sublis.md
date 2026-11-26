# sublis

`(sublis alist tree &key key test test-not)`

Returns a fresh copy of `tree` in which every subtree matching a key of `alist` is replaced by that entry's value. Each subtree -- the cons cells as well as the leaves -- is looked up before being descended into, so a whole branch can be substituted. The default test is `eql`; `:key` is applied to the subtree before the lookup.

The destructive `nsublis` is not provided.

```lisp
(sublis '((a . 1) (b . 2)) '(a (b c) . a)) ; => (1 (2 C) . 1)
```
