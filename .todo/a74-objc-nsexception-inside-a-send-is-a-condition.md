# `objc`: an NSException inside a send is a condition, and `invoke-with-error`

Difficulty: High

Built on the new base (.todo/a71); nothing here calls the old verbs or the old bridge.

## Today

An Objective-C exception raised inside a send ends the process; `handler-case` never sees it.
Measured 2026-09-28 (macOS aarch64, `java -jar`, old base):

```lisp
(print (handler-case (objc:send (objc:send "NSArray" "array") "objectAtIndex:" 5)
         (error (e) (list :caught (princ-to-string e)))))
(print :after)
```

prints `*** Terminating app due to uncaught exception 'NSRangeException' ...` and neither line.
Only a wrong selector, arity or operand type is caught, because the selector is resolved and the
operands checked before sending.

## Target

- `objc:objc-exception` (readers `objc-exception-name`, `-reason`, `-object`) signalled in the
  innermost `invoke` on that thread, on the interpreter, JVM class output and `--native`.
- `objc:ns-error` (readers `ns-error-domain`, `-code`, `-description`, `-object`) and
  `objc:invoke-with-error`, which supplies the method's `NSError **` and signals when the RESULT
  says the call failed (nil, `NO` or zero -- Foundation's rule, not "the slot is non-NULL"). The
  old base's `:error` slot (`ObjcRuntime.Out`, `checkError`) shows the mechanics.

## The hard part

The exception must not unwind through JVM or wasm frames. Candidates:

- the runtime's uncaught-exception handler (`NSSetUncaughtExceptionHandler`) records the exception
  and escapes to a point below the send -- on the JVM that point must be native, since an upcall
  may not throw back into FFM;
- a catching trampoline around `objc_msgSend` (`objc_begin_catch`/`objc_end_catch` and the
  personality routine) -- in the `--native` runner beside `rl_objc_call` in `call.rs`; on the JVM
  rontolisp has no native code of its own to put it in, which is itself a decision.

Record in `.kb/objc.md` what is abandoned between throw and catch (frames skipped without their
cleanups). An exception outside any send, or one Cocoa catches itself, is out of scope.

## Done when

The snippet above, written with `objc:invoke`, prints `(:CAUGHT ...)` then `:AFTER` on the three
targets, and `invoke-with-error` over a failing `NSFileManager` call signals `ns-error` with the
domain and code.
