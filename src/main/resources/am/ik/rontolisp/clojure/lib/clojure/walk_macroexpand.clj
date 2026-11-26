;; clojure.walk's macroexpand-all: loaded into the namespace where a program
;; first names it, so a program walking data expands nothing at run time and
;; carries no macro expander.

(defn macroexpand-all
  "form with every seq in it macroexpanded, outermost first."
  [form]
  (prewalk (fn [x] (if (seq? x) (macroexpand x) x)) form))
