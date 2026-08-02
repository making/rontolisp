# CLOS multiple inheritance + setf methods + setf function-name places

Difficulty: 高 (substrate milestone, multi-session — CPL/slot-merge/dispatch
on all four backends; do not fold into a library todo)

Split out of `.todo/231` (2026-08-02 survey). The ONLY heavyweight between
circular-streams / lack-request / lack-middleware-session / -csrf and
loading, and a broad unblocker for the wider CL ecosystem beyond lack.

Three related gaps, in dependency order:

1. **Multiple inheritance in defclass** — currently "at most one
   superclass". Needs class precedence list computation (CLHS
   topological-sort rules), slot inheritance/merging across supers, and
   method dispatch over the CPL. Probe evidence:
   - fast-io: `(defclass fast-output-stream (fast-io-stream
     fundamental-output-stream) ...)` (and fast-input-stream likewise)
   - circular-streams: `(defclass circular-input-stream
     (trivial-gray-stream-mixin fundamental-binary-input-stream) ...)`
   Check `.kb/clos.md` for the current single-inheritance representation
   and its pinning tests before touching.

2. **`(defmethod (setf name) ...)`** — DEFMETHOD currently rejects a
   `(setf name)` function name. fast-io and circular-streams both define
   `(setf stream-file-position)` methods. Interacts with the setf expansion
   machinery (`LispMacroExpander` setf) and the Lisp-2 function namespace.

3. **setf places `symbol-function` / `fdefinition`** — fast-io aliases its
   `write8-le`-family via `(setf (symbol-function 'write8-le) #'write8)`;
   circular-streams does `(setf (fdefinition 'make-circular-stream)
   #'make-circular-input-stream)`. On the compile paths this needs the
   runtime symbol->function registry (todo-229, `.kb/symbol-runtime-api.md`)
   to accept runtime (re)binding, not just lookup.

Related finding worth folding in or filing separately: a runtime `(export
(intern "X" "PKG") "PKG")` after `(defun pkg::x ...)` does not make `pkg:x`
callable — function lookup keys do not follow a later export (found while
trying to patch uiop from Lisp during the 231 survey).

All four backends; the interpreter re-expands (`.kb/*` gensym/macroexpand
rules) and the WASM GC struct layout for instances may constrain the slot
merge — survey `.kb/clos.md` + `.kb/instance-syntax.md` first.
