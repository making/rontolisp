;;;; environment.lisp -- %host-getenv and %host-argv over wit-imported
;;;; wasi:cli/environment@0.3.0.
;;;;
;;;; This is the --component implementation of the HOST read behind uiop:getenv
;;;; (spliced by eval/EnvironmentLibrary when a --component program reaches it). The
;;;; public uiop:getenv is Lisp on every backend (uiop-os.lisp): it consults the
;;;; override map a (setf (uiop:getenv name) value) wrote before asking the host.
;;;; The interpreter and the JVM keep System.getenv; Preview 1 keeps the
;;;; environ_sizes_get / environ_get buffer scan (_getenv, WasmGetenvRuntimeBuilder)
;;;; -- the host fills that buffer there, and a component has no preview1 host to
;;;; fill it.
;;;;
;;;; ONE binding serves every component variant, which is the point of doing it here
;;;; rather than in an adapter: the base / sockets blocks already declare
;;;; wasi:cli/environment, so WasmComponentBuilder lowers get-environment FROM the
;;;; block (a second import of the same interface would be invalid), while a SERVE
;;;; component -- whose wasi:http service world carries no environment interface, so
;;;; its preview1 bridge answers environ_* with a zero environment -- gets the very
;;;; same binding as an appended user import. That is what makes `wasmtime serve
;;;; --env FOO=bar` (and any other host's environment) readable from a served
;;;; handler.
;;;;
;;;; get-environment hands back the WHOLE environment as list<tuple<string,string>>
;;;; (a Lisp list of two-element lists) and the lookup walks it, so an unset variable
;;;; answers nil and an empty value answers "" -- the contract of every other
;;;; backend.

;; The WIT interface is lowered by EnvironmentLibrary (which calls
;; WitImportDirective.lower itself), so this directive never reaches WitImportInliner
;; -- but it is written here, in environment.lisp's own source, so the file reads as
;; the program it is.
(rontolisp:wit-import "environment.wit"
                      :interface "wasi:cli/environment@0.3.0"
                      :package %environ)

(defun %host-getenv (%getenv-name)
  (do ((%getenv-rest (%environ:get-environment) (cdr %getenv-rest)))
      ((null %getenv-rest) nil)
    (if (string= (car (car %getenv-rest)) %getenv-name)
        (return (car (cdr (car %getenv-rest)))))))

;; %host-argv: the vector behind the whole uiop/image command-line family (the five
;; public names are Lisp over it, uiop-image.lisp). get-arguments hands back the
;; POSIX-style arguments INCLUDING argv[0], which is the shape every backend answers,
;; so there is nothing to reshape here. Preview 1 scans the args_sizes_get / args_get
;; buffer instead (_argv, WasmArgvRuntimeBuilder); the interpreter and the JVM have
;; their own primitive. A program that reads no arguments is not spliced this defun,
;; and binds no get-arguments.
(defun %host-argv () (%environ:get-arguments))
