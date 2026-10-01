# マルチメソッドと階層

multimethod はメソッドテーブルとディスパッチャです。階層は最も具体的なもの順の探索でディスパッチを広げ、残った同順は prefer-method が決めます。

| Name | Example | Result |
|---|---|---|
| `defmulti` | `(do (defmulti area :shape) (defmethod area :default [m] 0) (area {:shape :x}))` | `0` |
| `defmethod` | `(do (defmulti a :k) (defmethod a :x [m] 1) (a {:k :x}))` | `1` |
| `remove-method` | `(do (defmulti r :k) (defmethod r :x [m] 1) (remove-method r :x) nil)` | `nil` |
| `get-method` | `(do (defmulti g :k) (defmethod g :x [m] 1) (if (get-method g :x) :yes :no))` | `:yes` |
| `prefer-method` | `(do (defmulti p :k) (defmethod p :x [m] 1) (defmethod p :y [m] 2) (derive :x :y) (prefer-method p :x :y) (p {:k :x}))` | `1` |
| `derive` | `(do (derive :circle :shape) nil)` | `nil` |
| `underive` | `(do (underive :circle :shape) nil)` | `nil` |
| `isa?` | `(do (derive :c :p) (isa? :c :p))` | `true` |
| `parents` | `(do (derive :c :p) (parents :c))` | `#{:p}` |
| `ancestors` | `(do (derive :c :p) (derive :p :q) (ancestors :c))` | `#{:p :q}` |
| `descendants` | `(do (derive :c :p) (descendants :p))` | `#{:c}` |
| `make-hierarchy` | `(isa? (make-hierarchy) :c :p)` | `false` |