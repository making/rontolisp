#!/usr/bin/env python3
# sim-ideal-heads-with-redundancy.py in.wat out.wat IDX SET LISTLAYOUT : sim-ideal-heads.py with what the
# compiled helpers repeat put back, by the letters of the environment variable V:
#   I  the instance cast and the channel clear at every slot read
#   N  integerp's three tests and the subscript's repeated fixnum tests
#   C  a second cast of the vector's cell
import sys,re,os
V=os.environ.get('V','')
src=open(sys.argv[1]).read()
idx,st,lay=sys.argv[3],sys.argv[4],sys.argv[5]
INST='$#type56'; CELL='$#type5'; CONS='$#type3'; ARR='$#type8'
m=re.search(r'\(func \$#func%s \(;\d+;\) \(type (\$#type\d+)\)'%idx,src); tidx=m.group(1)
m=re.search(r'\(func \$#func%s \(;\d+;\) \(type (\$#type\d+)\)'%st,src); tset=m.group(1)
src=src.replace('call $#func%s\n'%idx,'call $fastidx\n').replace('call $#func%s\n'%st,'call $fastset\n')

recast=f"local.get $inst\n      struct.get {INST} $#field1"
recast2="local.get $slots"
if 'I' in V:
    recast=f"ref.null eq\n      global.set $#global1\n      local.get $x\n      br_on_cast_fail $fail eqref (ref {INST})\n      struct.get {INST} $#field1"
    recast2=recast
intp=""
if 'N' in V:
    intp=f"""
      local.get $i
      ref.test (ref i31)
      local.get $i
      ref.test (ref $#type31)
      i32.or
      local.get $i
      ref.test (ref $#type36)
      i32.or
      i32.eqz
      br_if $slow
      local.get $i
      ref.test (ref i31)
      i32.eqz
      br_if $slow
      local.get $i
      ref.cast (ref i31)
      i31.get_s
      i32.const 0
      i32.lt_s
      br_if $slow
      local.get $i
      ref.test (ref i31)
      i32.eqz
      br_if $slow
"""
cellx=""
if 'C' in V:
    cellx=f"""
      local.tee $tmp
      br_on_cast_fail $fail eqref (ref {CELL})
      drop
      local.get $tmp"""

common=f'''{cellx}
      br_on_cast_fail $fail eqref (ref {CELL})
      local.tee $cell
      struct.get {CELL} $#field1
      local.tee $st
      ref.is_null
      br_if $slow
      local.get $cell
      struct.get {CELL} $#field2
      struct.get {CONS} $#field0
      br_on_cast_fail $fail eqref (ref i31)
      i31.get_s
      local.set $n
{intp}
      local.get $i
      br_on_cast_fail $fail eqref (ref i31)
      i31.get_s
      local.tee $j
      local.get $n
      i32.ge_u
      br_if $slow
      local.get $j
      local.get $st
      array.len
      i32.ge_u
      br_if $slow
'''
fast=f'''
  (func $fastidx (type {tidx}) (param $env eqref) (param $x eqref) (param $i eqref) (result eqref)
    (local $st (ref null {ARR})) (local $cell (ref null {CELL})) (local $inst (ref null {INST})) (local $n i32) (local $j i32) (local $tmp eqref)
    block $slow
      block $fail (result eqref)
      local.get $x
      br_on_cast_fail $fail eqref (ref {INST})
      local.tee $inst
      struct.get {INST} $#field0
      i32.const {lay}
      i32.ne
      br_if $slow
      {recast}
      i32.const 0
      array.get {ARR}
{common}
      local.get $st
      local.get $j
      array.get {ARR}
      return
      end
      drop
    end
    local.get $env
    local.get $x
    local.get $i
    return_call $#func{idx})
  (func $fastset (type {tset}) (param $env eqref) (param $x eqref) (param $i eqref) (param $v eqref) (result eqref)
    (local $st (ref null {ARR})) (local $cell (ref null {CELL})) (local $inst (ref null {INST})) (local $slots (ref null {ARR})) (local $n i32) (local $j i32) (local $tmp eqref)
    block $slow
      block $fail (result eqref)
      local.get $x
      br_on_cast_fail $fail eqref (ref {INST})
      local.tee $inst
      struct.get {INST} $#field0
      i32.const {lay}
      i32.ne
      br_if $slow
      {recast}
      local.tee $slots
      i32.const 1
      array.get {ARR}
      br_on_cast_fail $fail eqref (ref {CONS})
      struct.get {CONS} $#field0
      ref.is_null
      i32.eqz
      br_if $slow
      {recast2}
      i32.const 2
      array.get {ARR}
      br_on_cast_fail $fail eqref (ref i31)
      i31.get_s
      i32.const 0
      i32.gt_s
      br_if $slow
      {recast2}
      i32.const 0
      array.get {ARR}
{common}
      local.get $st
      local.get $j
      local.get $v
      array.set {ARR}
      local.get $v
      return
      end
      drop
    end
    local.get $env
    local.get $x
    local.get $i
    local.get $v
    return_call $#func{st})
'''
# insert before the first func after imports: append after last func is simplest -> before the final ')'
k=src.rindex(')')
open(sys.argv[2],'w').write(src[:k]+fast+')\n')
