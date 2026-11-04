;; What the macros of a component see: coalton's parser asks for its feature while
;; a coalton-toplevel form expands. A top-level form, because the interpreter expands
;; a macro inside a defun when the defun first runs, past the hook.
(defmacro hook-features-at-expansion ()
  `',(remove-if-not (lambda (feature)
                      (member feature '(:meta-demo-hooked :meta-demo-named-hook :meta-demo-tool-hooked)))
                    *features*))

(defparameter *hooked*
  (list #+meta-demo-hooked :read-with #-meta-demo-hooked :read-without
        (hook-features-at-expansion)))
