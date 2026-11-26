;;;; logging.lisp -- %host-log: a log record handed to the host's log under --wasi-logging.
;;;;
;;;; A Java program's root logger hands its records here where the build says
;;;; --wasi-logging (java.util.logging.HostHandler, .kb/java-frontend.md,
;;;; "java.util.logging"): the level by its place among wasi:logging's levels, the
;;;; logger's name as the context, the record's text as the message. Spliced by
;;;; eval/LoggingLibrary where a program reaches %host-log, which only such a build's
;;;; does.
;;;;
;;;; * --component binds wasi:logging/logging@0.1.0-draft's log as an APPENDED USER
;;;;   IMPORT, as exit.lisp binds wasi:cli/exit: a host without the interface (wasmtime
;;;;   51) refuses to instantiate a component importing it, so only the flag adds it.
;;;; * A --no-wasi module imports the same log through the canonical ABI, the enum
;;;;   being no member of the flat set: its host is the glue --emit-js-glue writes, which
;;;;   answers it on the console method of the level (.kb/wasm-import.md, "A WIT
;;;;   interface beyond the flat set").

#+(or rontolisp-component rontolisp-reactor)
(rontolisp:wit-import "logging.wit"
                      :interface "wasi:logging/logging@0.1.0-draft"
                      :package %logging)

#+(or rontolisp-component rontolisp-reactor)
(defun %host-log (%hl-level %hl-context %hl-message)
  (%logging:log (case %hl-level
                  (0 :trace)
                  (1 :debug)
                  (2 :info)
                  (3 :warn)
                  (4 :error)
                  (t :critical)) %hl-context %hl-message))
