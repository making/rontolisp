#!/usr/bin/env python3
# variants.py SRC-DIR NAME [LEVER,LEVER...]: a copy of examples/proxy-wasm with the SDK levers applied.
# Levers: logmacro, nocond, trap (trap needs nocond).
import re,sys,shutil,os
base=sys.argv[1]; name=sys.argv[2]; levers=sys.argv[3].split(',') if len(sys.argv)>3 and sys.argv[3] else []
d=f'{name}'; os.makedirs(d+'/sdk-examples',exist_ok=True)
for f in os.listdir(base+'/sdk-examples'): shutil.copy(f'{base}/sdk-examples/{f}',f'{d}/sdk-examples/{f}')
s=open(base+'/proxy-wasm.lisp').read()
def sub(old,new,count=1,rx=False):
    global s
    if rx:
        assert re.search(old,s,re.S),old
        s=re.sub(old,new,s,count=count,flags=re.S)
    else:
        assert old in s,old
        s=s.replace(old,new)
if 'logmacro' in levers:
    sub(r'\(defun %log \(level control args\).*?\(defun log-critical \(control &rest args\) \(%log 5 control args\)\)',
    """(defmacro %define-log (name level)
  `(defmacro ,name (control &rest args)
     `(progn (%proxy-log ,,level (format nil ,control ,@args)) nil)))
(%define-log log-trace 0)
(%define-log log-debug 1)
(%define-log log-info 2)
(%define-log log-warn 3)
(%define-log log-error 4)
(%define-log log-critical 5)""",rx=True)
if 'nocond' in levers:
    sub('(%proxy-log 4 (format nil "proxy-wasm: ~a" condition))','(%proxy-log 4 "proxy-wasm: callback failed")')
    sub('(error (condition)','(error ()')
    sub('(error "~a answered status ~a" operation status)','(error operation)')
if 'trap' in levers:
    # No handler at all: a failed host call traps.
    sub('(error operation)','(panic)')
    sub(r'\(handler-case \(progn ,@body\)\s*\(error \(\)\s*\(%proxy-log 4 "proxy-wasm: callback failed"\)\s*\(when \*trap-on-error\* \(panic\)\)\s*,fallback\)\)','(progn ,@body)',rx=True)
    sub('(defmacro %with-callback ((fallback) &body body)','(defmacro %with-callback ((fallback) &body body)\n  (declare (ignorable fallback))')
open(d+'/proxy-wasm.lisp','w').write(s)
if 'trap' in levers:
    # A trap cannot be caught, so the one call a filter handles answers by status.
    p=d+'/sdk-examples/test_plugin.lisp'; t=open(p).read()
    a=t.index('(proxy-wasm:add-header :response "x-outgoing"'); b=t.index('("/replace"',a)
    t=t[:a]+'''(proxy-wasm:add-header :response "x-outgoing"
      (if (= 0 (proxy-wasm::%proxy-http-call "upstream" (proxy-wasm::%serialize-pairs '()) "" (proxy-wasm::%serialize-pairs '()) 1000 (proxy-wasm::%alloc 8))) "sent" "refused")))
    '''+t[b:]
    open(p,'w').write(t)
