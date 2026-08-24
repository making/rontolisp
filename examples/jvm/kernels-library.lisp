;; A rontolisp library the JVM ecosystem can consume (doc/en/guides/jvm-library.md).
;;
;; Compile it as a library class -- the -o path is the Java package, --no-main
;; drops the command entry point:
;;
;;   rontolisp kernels-library.lisp -o com/example/Kernels.class --no-main
;;
;; The class then carries the typed methods the directives below declare:
;;
;;   package com.example;
;;   public class Kernels {
;;     public static double scaledSum(double a, double b);
;;     public static long   fact(long n);
;;     public static String greet(String name);
;;   }
;;
;; JvmExportExampleTest compiles this file and calls each method from Java.

(defvar *scale* 2.0)

(defun scaled-sum (a b) (* *scale* (+ a b)))

(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))

(defun greet (name) (concatenate 'string "hello, " name))

(rontolisp:jvm-export 'scaled-sum :params '(:float :float) :returns :float)

(rontolisp:jvm-export 'fact :params '(:s64) :returns :s64)

(rontolisp:jvm-export 'greet :params '(:string) :returns :string)
