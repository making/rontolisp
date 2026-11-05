#!/usr/bin/env python3
# dropdead.py SDK.lisp NAME... : delete top-level defuns, as a dispatch table that ignored dead code would.
import re,sys
p=sys.argv[1]; s=open(p).read()
for name in sys.argv[2:]:
    m=re.search(r'\n\(defun '+re.escape(name)+r'(?=[\s(])',s)
    if not m: print('not found',name); continue
    i=m.start()+1; d=0; j=i; ins=False
    while True:
        c=s[j]
        if ins:
            if c=='\\': j+=1
            elif c=='"': ins=False
        elif c=='"': ins=True
        elif c==';':
            while s[j]!='\n': j+=1
        elif c=='#' and s[j+1]=='\\': j+=2
        elif c=='(': d+=1
        elif c==')':
            d-=1
            if d==0: break
        j+=1
    s=s[:i]+s[j+1:]
open(p,'w').write(s)
