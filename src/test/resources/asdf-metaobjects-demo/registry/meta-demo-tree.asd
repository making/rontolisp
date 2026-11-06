;; The component tree a program walks: every shape of child real ASDF makes an
;; instance of, definition order against plan order, a :pathname at each level. A
;; class this file declares is an instance of its superclass here, which the exercise
;; reads by typep.
(defclass tree-txt (doc-file) ((type :initform "txt")))

(defclass tree-src (cl-source-file.cl) ())

;; cl-unification's shape: a type spelled as the class symbol, its sources named .asd by
;; a method, so the file loads as source and its defsystem registers.
(defclass tree-asd (cl-source-file) ())

(defmethod source-file-type ((c tree-asd) (s module)) "asd")

(defsystem "meta-demo-tree"
  :pathname "tree"
  :components ((:file "one")
               (:static-file "README.md")
               (:static-file "LICENSE" :pathname "other/LICENSE")
               (:doc-file "guide.txt")
               (:html-file "page")
               (:tree-txt "notes")
               (:tree-src "cee")
               (:cl-source-file.cl "see")
               (tree-asd "sub")
               (:file "later" :depends-on ("m"))
               (:module "m"
                :components ((:file "two")
                             (:static-file "data.txt")
                             (:module "deep"
                              :pathname "d/e"
                              :components ((:file "three")))))
               (:module "flat" :pathname "" :components ((:file "four")))
               (:file "renamed" :pathname "named/file")
               (:file "gated" :if-feature :meta-demo-nowhere)
               (:module "ser"
                :serial t
                :components ((:file "s1")
                             (:file "s2")
                             (:file "s3" :depends-on ("s1"))))))
