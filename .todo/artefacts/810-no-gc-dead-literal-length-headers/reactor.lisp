;;;; A reactor benchmark: four host DOM calls behind four forwarders, a
;;;; recursive fib, and four exports. Every string literal sits INSIDE a
;;;; forwarder, one frame above the import call site.

;;; Host DOM APIs. A :string parameter crosses as (ptr, len) in linear memory,
;;; so the length argument the host expects is part of the boundary, not a
;;; parameter the program passes by hand.
(rontolisp:wasm-import 'js-log :from "env" :as "js_log"
                       :params '(:string) :returns nil)
(rontolisp:wasm-import 'js-set-text :from "env" :as "js_set_text"
                       :params '(:string :string) :returns nil)
(rontolisp:wasm-import 'js-append-text :from "env" :as "js_append_text"
                       :params '(:string :string) :returns nil)
(rontolisp:wasm-import 'js-set-badge-color :from "env" :as "js_set_badge_color"
                       :params '(:string :string) :returns nil)

;;; Internal helpers.
(defun emit-log (msg) (js-log msg))
(defun set-text (element-id text) (js-set-text element-id text))
(defun append-text (element-id text) (js-append-text element-id text))
(defun set-badge-color (element-id color) (js-set-badge-color element-id color))

;;; Internal logic: fibonacci, for the compute demo.
(defun fib (n)
  (if (<= n 1)
      n
      (+ (fib (- n 1)) (fib (- n 2)))))

;;; --- the API exported to the browser ---

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
        (t
         (append-text "event-logbox"
                      "
[event] Heartbeat tick received from the web page."))))

(rontolisp:wasm-export 'init-app :as "InitApp" :params '() :returns nil)
(rontolisp:wasm-export 'add-numbers :as "AddNumbers" :params '(:s32 :s32) :returns :s32)
(rontolisp:wasm-export 'run-computation :as "RunComputation" :params '(:s32) :returns :s32)
(rontolisp:wasm-export 'append-log-message :as "AppendLogMessage" :params '(:s32) :returns nil)
