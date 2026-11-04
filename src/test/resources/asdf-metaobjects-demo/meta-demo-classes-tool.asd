;; The classes meta-demo-classes.asd names, defined in this system's SOURCE, so only
;; the load its :defsystem-depends-on asks for can answer for them.
(asdf:defsystem "meta-demo-classes-tool"
  :components ((:file "classes-tool")))
