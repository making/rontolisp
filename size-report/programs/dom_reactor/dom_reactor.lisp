;;;; dom_reactor -- a browser-facing reactor: four host DOM imports, four
;;;; exports the page calls, a recursive fib for the compute demo.
;;;;
;;;; This is the shape a Wasm module takes when the host is a web page rather
;;;; than a WASI runtime: no stdout, no _start, nothing to print. The page
;;;; calls an export; the module calls back into the DOM through imports. A
;;;; :string parameter crosses as (ptr, len) in linear memory, so the length
;;;; argument the host expects is part of the boundary, not something the
;;;; program passes by hand.
;;;;
;;;; One source, both backends: the program stays inside the --no-gc subset
;;;; (only defun / wasm-import / wasm-export at top level), so the same file
;;;; measures the wasm-GC and the MVP core lowering of the same program.
;;;;
;;;; It is a port of hike-lang's examples/browser/main.hike
;;;; (https://github.com/kanryu/hike-lang), import for import and export for
;;;; export, and the size report compares the two section by section. The
;;;; literals are therefore deliberately SIZED: the data section is only
;;;; comparable if the text it holds is the same length, so the prose here is
;;;; written to that program's byte lengths rather than to taste.
;;;;
;;;; Run:
;;;;   rontolisp size-report/programs/dom_reactor/dom_reactor.lisp \
;;;;     -o dom.wasm --no-gc --no-wasi --optimize=size
;;;;   node size-report/programs/dom_reactor/host.mjs dom.wasm
;;;;
;;;; wasmtime cannot run it: the four imports come from the host page's `env`
;;;; module, which only a JS host supplies. host.mjs is that host.

;;; Host DOM APIs.
(rontolisp:wasm-import 'js-log
                       :from "env"
                       :as "js_log"
                       :params '(:string)
                       :returns nil)
(rontolisp:wasm-import 'js-set-text
                       :from "env"
                       :as "js_set_text"
                       :params '(:string :string)
                       :returns nil)
(rontolisp:wasm-import 'js-append-text
                       :from "env"
                       :as "js_append_text"
                       :params '(:string :string)
                       :returns nil)
(rontolisp:wasm-import 'js-set-badge-color
                       :from "env"
                       :as "js_set_badge_color"
                       :params '(:string :string)
                       :returns nil)

;;; Internal helpers. The literals sit HERE, one frame above the import call
;;; site, which is where a real program puts them.
(defun emit-log (msg) (js-log msg))
(defun set-text (element-id text) (js-set-text element-id text))
(defun append-text (element-id text) (js-append-text element-id text))
(defun set-badge-color (element-id color) (js-set-badge-color element-id color))

;;; Internal logic: fibonacci, for the compute demo.
(defun fib (n) (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))

;;; --- the API exported to the page ---

(defun init-app ()
  (emit-log "Reactor core initialized and is ready to serve.")
  (set-text "status-panel" "Running (module live)")
  (set-badge-color "status-panel" "#2f7f4f")
  (set-text "main-output"
            "The compiled reactor module is online and idle.
Press a button to trigger native computation."))

(defun add-numbers (a b) (+ a b))

(defun run-computation (n)
  (emit-log "Entering run-computation inside this module...")
  (fib n))

(defun append-log-message (msg-code)
  (cond ((= msg-code 1)
         (append-text "event-logbox"
                      "
[event] Button A pressed: linear memory layout validated ok."))
        ((= msg-code 2)
         (append-text "event-logbox"
                      "
[event] Button B pressed: slice buffer manipulation has completed fine."))
        (t (append-text "event-logbox"
                        "
[event] Heartbeat tick received from the web page."))))

(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
(rontolisp:wasm-export 'add-numbers
                       :as "AddNumbers"
                       :params '(:s32 :s32)
                       :returns :s32)
(rontolisp:wasm-export 'run-computation
                       :as "RunComputation"
                       :params '(:s32)
                       :returns :s32)
(rontolisp:wasm-export 'append-log-message
                       :as "AppendLogMessage"
                       :params '(:s32)
                       :returns nil)
