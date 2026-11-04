# i00. `defclass` of a forward-referenced superclass

Difficulty: Medium

Measured 2026-11-04. ANSI allows a superclass that does not exist yet; the class is only unusable
until it does.

```lisp
(defclass a (b) ())
(defclass b () ((x :initform 7 :reader x)))
(x (make-instance 'a))   ; SBCL 7
                         ; rontolisp: DEFCLASS: unknown superclass B (a superclass must be defined by defclass first)
```

Consumer: cxml. `xml/xmlns-normalizer.lisp:28` does `(defclass namespace-normalizer (sax-proxy) ...)`
and `cxml.asd` loads `sax-proxy.lisp` after it (both depend only on `xml-parse`, so the plan order is
legal). Moving the component in the walked copy gets past it.

Needed: register the class as forward-referenced, finalize it when the last superclass appears
(`.kb/clos.md`, the registry and precedence computation), and make `make-instance` of an
unfinalized class signal. The compile paths' static CLOS can collect all `defclass` forms first,
which may make the JVM and WASM side simpler than the interpreter's. All four backends.
