# Multimethods and hierarchies

A multimethod is a method table plus a dispatcher; a hierarchy widens the dispatch by the most-specific-first search, `prefer-method` breaking the remaining ties.

| Name | Example | Result |
|---|---|---|
| `defmulti` | `(do (defmulti area :shape) (defmethod area :default [m] 0) (area {:shape :x}))` | `0` |
| `defmethod` | `(do (defmulti a :k) (defmethod a :x [m] 1) (a {:k :x}))` | `1` |
| `remove-method` | `(do (defmulti r :k) (defmethod r :x [m] 1) (remove-method r :x) nil)` | `nil` |
| `get-method` | `(do (defmulti g :k) (defmethod g :x [m] 1) (if (get-method g :x) :yes :no))` | `:yes` |
| `methods` | `(do (defmulti m :k) (defmethod m :x [v] 1) (count (methods m)))` | `1` |
| `prefer-method` | `(do (defmulti p :k) (defmethod p :x [m] 1) (defmethod p :y [m] 2) (derive :x :y) (prefer-method p :x :y) (p {:k :x}))` | `1` |
| `derive` | `(do (derive :circle :shape) nil)` | `nil` |
| `underive` | `(do (underive :circle :shape) nil)` | `nil` |
| `isa?` | `(do (derive :c :p) (isa? :c :p))` | `true` |
| `parents` | `(do (derive :c :p) (parents :c))` | `#{:p}` |
| `ancestors` | `(do (derive :c :p) (derive :p :q) (ancestors :c))` | `#{:p :q}` |
| `descendants` | `(do (derive :c :p) (descendants :p))` | `#{:c}` |
| `make-hierarchy` | `(isa? (make-hierarchy) :c :p)` | `false` |
