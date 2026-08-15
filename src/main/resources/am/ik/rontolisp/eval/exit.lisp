;;;; exit.lisp -- %host-exit on the two WASM backends.
;;;;
;;;; The host call behind uiop:quit (spliced by eval/ExitLibrary when a WASM program
;;;; reaches it). The public uiop:quit is Lisp on every backend (uiop-image.lisp): it
;;;; finishes the standard output streams and then calls this, so all four agree on
;;;; what quitting means. The interpreter and the JVM keep their own primitive (a
;;;; LispExitSignal the CLI turns into the process code; System.exit).
;;;;
;;;; Neither arm needs a blob or an adapter entry point, and that is the point of doing
;;;; it here: a program that never quits imports nothing new.
;;;;
;;;; * Preview 1 binds wasi_snapshot_preview1's proc_exit through an ordinary
;;;;   rontolisp:wasm-import, under the primitive's OWN name -- the import IS
;;;;   %host-exit, so there is no second spelling to register or resolve.
;;;; * --component binds wasi:cli/exit@0.3.0's exit-with-code as an APPENDED USER
;;;;   IMPORT: the fixed import block does not declare that interface (unlike
;;;;   wasi:cli/environment, which environment.lisp binds FROM the block), so a
;;;;   quitting component is the base variant plus this one import.
;;;;
;;;; A --no-wasi / --no-gc reactor owns no WASI world and gets no arm at all:
;;;; ExitLibrary refuses the compile by name instead of splicing something that
;;;; cannot work.

#+rontolisp-component
(rontolisp:wit-import "exit.wit"
                      :interface "wasi:cli/exit@0.3.0"
                      :package %exit)

#+rontolisp-component
(defun %host-exit (%he-code) (%exit:exit-with-code %he-code))

#-rontolisp-component
(rontolisp:wasm-import '%host-exit
                       :from "wasi_snapshot_preview1"
                       :as "proc_exit"
                       :params '(:int))
