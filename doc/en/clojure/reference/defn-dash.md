# defn-

`(defn- name doc? params body...)`

A private `defn`: identical in every way except convention (there is no
namespace to hide the name from). Metadata anywhere -- `^:private` on the name,
an attr map, a docstring -- parses and drops.

```clojure
(defn- double [x] (* x 2))
(println (double 21)) ; 42
```
