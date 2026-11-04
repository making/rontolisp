;; Classes that exist only once :defsystem-depends-on has loaded: a :class named by a
;; string (hu.dwim.def's), component types by keyword (:test-file, :lass-file,
;; :coalton-file), and test-op's :after spelling with a #. in it (prove's consumers).
(defsystem "meta-demo-classes"
  :defsystem-depends-on ("meta-demo-classes-tool")
  :class "classes-tool:tool-system"
  :serial t
  :components ((:file "classes-plain")
               (:typed-file "classes-typed")
               (:data-file "classes-data" :output "classes-data.out")
               (:later-file "classes-later"))
  :perform (test-op :after (o c)
                    (funcall (intern #.(string :run-later) :classes-tool) c)))

;; A class this file defines: only the image has it, and methods in a .asd do not
;; reach a program, so an instance is of the class's nearest superclass the program
;; has.
(defclass own-system (system) ())

(defclass own-file (classes-tool:later-file) ())

(defsystem "meta-demo-classes/own"
  :class own-system
  :components ((:own-file "classes-own")))
