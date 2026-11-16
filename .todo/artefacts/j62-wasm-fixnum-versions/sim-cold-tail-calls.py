#!/usr/bin/env python3
# sim3.py in.wat out.wat : in func257 (the split index head) every cold non-tail call becomes a tail call of the general function
import sys,re
s=open(sys.argv[1]).read()
a=s.index('  (func $#func257 '); b=s.index('  (func $#func258 ')
f=s[a:b]
tc='ref.null eq\n local.get $#local1\n local.get $#local2\n return_call $#func259\n'
def rep(f,call,nargs):
    assert ('call %s\n'%call) in f, call
    return f.replace('call %s\n'%call, 'drop\n'*nargs+tc)
for call,n in (('$#func168',2),('$#func105',1),('$#func24',2),('$#func126',2),('$#func116',2),('$#func130',3),('$#func103',2)):
    f=rep(f,call,n)
open(sys.argv[2],'w').write(s[:a]+f+s[b:])
