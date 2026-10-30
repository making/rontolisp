#!/bin/bash
# Drives Lem (tmux session "lem", started by start.sh) through the recorded session.
# Each step marks wire.log ("## STEP name") and appends a pane capture to screens.txt.
# Usage: drive.sh [step ...]   (no argument: all steps in order)
set -u
O=@WORK@/lem-oracle
W=$O/work

key() { tmux send-keys -t lem "$@"; sleep 0.4; }
txt() { tmux send-keys -t lem -l "$1"; sleep 0.4; }
mx() { key Escape x; sleep 0.5; txt "$1"; key Enter; sleep 0.5; }
mark() { echo "## STEP $1" >> "$O/wire.log"; echo "######## STEP $1" >> "$O/screens.txt"; }
cap() { sleep "${1:-1.5}"; tmux capture-pane -p -t lem | grep -v '^\s*$' >> "$O/screens.txt"; echo "--------" >> "$O/screens.txt"; }
repl() { txt "$1"; key Enter; cap "${2:-1.5}"; }
open() { printf '%b' "$2" > "$W/$1"; key C-x C-f; sleep 1; txt "$1"; key Enter; sleep 1.5; }
torepl() { key C-c C-z; sleep 0.5; }

connect() {
  mark connect
  mx slime-connect
  key Enter
  key BSpace BSpace BSpace BSpace; txt 4406; key Enter
  cap 4
}
repl_values() {
  mark repl-values
  repl '(+ 1 2)'
  repl '(progn (format t "hello~%") (values 1 "two"))'
  repl '(values)'
  repl '(princ "no newline")'
  repl '(defvar *drv* 10)'
  repl '(list *drv* * ** ***)'
}
repl_package() {
  mark repl-package
  repl '(defpackage :drv-pkg (:use :cl) (:export #:hello))'
  repl '(in-package :drv-pkg)'
  repl "(defun hello () 'hi)"
  repl '(list (package-name *package*) (hello))'
  repl '(in-package :cl-user)'
  repl '(drv-pkg:hello)'
}
repl_input() {
  mark repl-input
  repl '(list (read-line) 7)' 1.5
  repl 'typed'
}
interactive_eval() {
  mark interactive-eval
  open drv-eval.lisp '(in-package :drv-pkg)\n\n(* 6 7)\n\n(defun drv-twice (x)\n  (* 2 x))\n'
  key M-'<'; key C-n C-n C-e
  key C-c C-e; cap
  key C-n C-n C-n; key C-M-x; cap
  mx lisp-eval-string 2>/dev/null; txt '(values 1 2)'; key Enter; cap
}
compile_defun() {
  mark compile-defun
  open drv-compile.lisp '(defun drv-add (a b)\n  (+ a b))\n\n(defun drv-bad ()\n  (drv-undefined-fn 1))\n'
  key M-'<'; key C-f C-f C-f
  key C-c C-c; cap 2
  key M-'>'; key C-p C-p; key C-c C-c; cap 2
}
compile_file() {
  mark compile-file
  open drv-file.lisp '(defun drv-file-fn (x)\n  (* x 2))\n\n(defun drv-file-warn ()\n  (let ((unused 1))\n    (drv-other-undefined)))\n'
  key C-c C-k; cap 3
  key C-c C-l; sleep 0.5; key Enter; cap 2
}
completion() {
  mark completion
  open drv-complete.lisp '\n'
  txt '(mapc'; key Tab; cap 2
  key Escape; key C-a C-k
  txt '(drv-ad'; key Tab; cap 2
  key Escape; key C-a C-k
}
autodoc() {
  mark autodoc
  open drv-autodoc.lisp '\n'
  txt '(mapcar '; cap 2
  key M-a; cap 2
  key C-a C-k
  txt '(drv-add 1 '; key M-a; cap 2
  key C-a C-k
}
describe() {
  mark describe
  open drv-describe.lisp 'mapcar drv-add\n'
  key M-'<'; key C-f
  mx lisp-describe-symbol; cap 1; key Enter; cap 2
  key Escape; key C-g
}
macroexpand() {
  mark macroexpand
  open drv-mx.lisp '(when (zerop 0) (print 1))\n'
  key M-'<'
  key C-c Enter; cap 2
  key n; cap 1
  key q
  key C-c M-m; cap 2
  key Space; sleep 0.5
}
error_step() {
  mark error
  torepl
  repl '(error "boom ~a" 1)' 3
  key q; cap 2
  torepl
  repl '(+ 40 2)'
}
interrupt() {
  mark interrupt
  torepl
  repl '(loop)' 1
  key C-c C-c; cap 3
  key q; cap 2
  torepl
  repl '(+ 40 3)'
}

STEPS=("$@")
if [ ${#STEPS[@]} -eq 0 ]; then
  STEPS=(connect repl_values repl_package repl_input interactive_eval compile_defun compile_file completion autodoc describe macroexpand error_step interrupt)
fi
for s in "${STEPS[@]}"; do "$s"; done
