# objc: an exit inside a method or block is swallowed on the interpreter

Difficulty: Low

`(uiop:quit 7)` inside a method `objc:define-objc-method` defined (or a block's function) ends
the process with code 7 as a JVM class and as a `--native` executable, but on the interpreter
(`java -jar`, the native binary) it prints `objc: error in a callback:
am.ik.rontolisp.eval.LispExitSignal`, answers zero, and the program goes on:

```lisp
(objc:define-objc-class exit-target () () (:objc-class-name "ExitTarget"))
(objc:define-objc-method ("bye:" :void) ((self exit-target) (x objc:objc-object-pointer))
  (declare (ignore x))
  (format t "bye~%") (finish-output) (uiop:quit 7))
(objc:invoke (make-instance 'exit-target) "performSelector:withObject:" "bye:" nil)
(format t "not reached~%")
```

The upcall guard (`am.ik.objc.ObjcMethods` / `ObjcBlocks` dispatch, and the Lisp
`objc::%run-method`) contains every escape, as it must for a `throw` or an error -- unwinding into
the native frame above an upcall ends the process. An EXIT is the one escape whose meaning is to
end the process, and the interpreter's `LispExitSignal` is contained like the rest. A menu item
or button whose handler quits the program (a script's "Quit") is the case that meets it.

## Done when
- A test (interpreter, macOS) runs the program above in a child process and sees `bye`, no
  `not reached`, exit code 7 -- the same as `NativeObjcE2eTest#anExitInsideACallbackEndsTheProcessWithItsCode`.
- Output is flushed as `uiop:quit` flushes it elsewhere; `.kb/objc.md` ("standard-objc-object,
  ownership, and objc-object-destroyed") drops the sentence that records the gap.
