# d64. `defmethod` accepts a lambda list not congruent with its generic

Difficulty: Medium

SBCL refuses a method whose lambda list is not congruent with the generic's (CLHS 7.6.4)
at `defmethod` time; rontolisp accepts it on all four backends and the method fails only
when called, with a count that includes the hidden next-method argument:

```lisp
(defgeneric g (a b &optional c))
(defmethod g ((a integer) b) (list a b))
;; SBCL: program-error at the defmethod ("the method has fewer optional arguments
;;       than the generic function")
;; interpreter: (g 1 2 3) -> "Function expects 3 arguments, got 4"
;; JVM / P1 / component: (g 1 2 3) -> "No applicable method: G on INTEGER"
```

Since `stream-write-string` is always called with integer `start` / `end`
(`.kb/gray-streams.md`), a Gray method spelled `((s c) str)` is this shape: it now fails
on its first write. Plan: check required / optional counts and `&rest` / `&key` presence
against the generic (explicit `defgeneric` or the first method) where a method is added,
on every backend, and signal SBCL's program-error there. Measure first what the bundled
and test-resource libraries define -- a non-congruent method that works today because it
is never called with the extra arguments would start failing at load time.
