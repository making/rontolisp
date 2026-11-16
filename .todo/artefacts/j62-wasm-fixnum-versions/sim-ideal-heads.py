#!/usr/bin/env python3
# sim-ideal-heads.py in.wat out.wat IDX SET LISTLAYOUT : a hand-written function in front of
# %starlark-index and of %starlark-set-index (function numbers IDX and SET, the list's layout
# address LISTLAYOUT, all read off the module's text) that answers a list subscripted by a
# fixnum within it and tail-calls the compiled function for anything else
import sys,re
src=open(sys.argv[1]).read()
idx,st,lay=sys.argv[3],sys.argv[4],sys.argv[5]
INST='$#type56'; CELL='$#type5'; CONS='$#type3'; ARR='$#type8'
m=re.search(r'\(func \$#func%s \(;\d+;\) \(type (\$#type\d+)\)'%idx,src); tidx=m.group(1)
m=re.search(r'\(func \$#func%s \(;\d+;\) \(type (\$#type\d+)\)'%st,src); tset=m.group(1)
src=src.replace('call $#func%s\n'%idx,'call $fastidx\n').replace('call $#func%s\n'%st,'call $fastset\n')
common=f'''
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
    (local $st (ref null {ARR})) (local $cell (ref null {CELL})) (local $inst (ref null {INST})) (local $n i32) (local $j i32)
    block $slow
      block $fail (result eqref)
      local.get $x
      br_on_cast_fail $fail eqref (ref {INST})
      local.tee $inst
      struct.get {INST} $#field0
      i32.const {lay}
      i32.ne
      br_if $slow
      local.get $inst
      struct.get {INST} $#field1
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
    (local $st (ref null {ARR})) (local $cell (ref null {CELL})) (local $inst (ref null {INST})) (local $slots (ref null {ARR})) (local $n i32) (local $j i32)
    block $slow
      block $fail (result eqref)
      local.get $x
      br_on_cast_fail $fail eqref (ref {INST})
      local.tee $inst
      struct.get {INST} $#field0
      i32.const {lay}
      i32.ne
      br_if $slow
      local.get $inst
      struct.get {INST} $#field1
      local.tee $slots
      i32.const 1
      array.get {ARR}
      br_on_cast_fail $fail eqref (ref {CONS})
      struct.get {CONS} $#field0
      ref.is_null
      i32.eqz
      br_if $slow
      local.get $slots
      i32.const 2
      array.get {ARR}
      br_on_cast_fail $fail eqref (ref i31)
      i31.get_s
      i32.const 0
      i32.gt_s
      br_if $slow
      local.get $slots
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
