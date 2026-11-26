;; clojure.java.io's resource: loaded into the namespace where a program first
;; names it, so a program looking no name up when it runs -- one naming
;; resources only with string literals, which the lowering finds itself --
;; carries no lookup, and none of a jar's reader.

(defn resource
  "The URL of the file n names below the source path, or nil. A name written
  as a string literal is read when the program lowers and travels with it; any
  other is looked up when it runs, below the source path's directories and
  jars. A class loader given is not consulted."
  ([n] (k/resource n))
  ([n _loader] (k/resource n)))
