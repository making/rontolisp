# make-load-form-saving-slots

`(make-load-form-saving-slots object &key slot-names environment)`

Lite stub: rontolisp has no fasl dumper, so calling this standard function signals an error. It exists so a library's `make-load-form` methods (which only run when an implementation dumps compiled files) still compile; such call sites are dead at run time.

```console
CL-USER> (make-load-form-saving-slots (make-instance 'point))
Error: make-load-form-saving-slots is not supported (no fasl dumper)
```
