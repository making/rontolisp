# asdf:system-description

`(asdf:system-description system)`

Reads back what a `defsystem` declared. The readers are `asdf:system-description`,
`asdf:system-long-description`, `asdf:system-author`, `asdf:system-maintainer`,
`asdf:system-license` (also `asdf:system-licence`), `asdf:system-homepage`,
`asdf:system-bug-tracker`, `asdf:system-mailto`, `asdf:system-long-name`,
`asdf:system-source-control`, `asdf:system-depends-on`,
`asdf:system-weakly-depends-on`, `asdf:system-defsystem-depends-on` and
`(asdf:component-property system property)` for `:properties`. Each answers the option
as written. **A `#.` in one of these options is not evaluated, so the reader answers
nil**; real ASDF's reader evaluates it. The slots carry real ASDF's names
(`(slot-value system 'asdf::description)`).

`asdf:system-source-file` answers the namestring of the `.asd` file that defined the
system, nil for an inline `defsystem`, and takes a designator.

```lisp
(asdf:defsystem :described :description "A demo" :license "MIT" :components ())
(asdf:system-description (asdf:find-system :described)) ; => "A demo"
(asdf:system-license (asdf:find-system :described)) ; => "MIT"
(asdf:system-author (asdf:find-system :described)) ; => NIL
```

## Backend support

Works on all four backends. A compiled program carries these values only when it names
a reader or a slot.
