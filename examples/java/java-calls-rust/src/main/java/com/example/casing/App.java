// A Java command that calls the `casing` interface.
//
// It imports example:textkit/casing (src/main/resources/com/example/casing/wit/
// textkit.wit) and calls `shout` on a few phrases. WHAT answers it is decided per
// backend, and nothing here changes:
//
//   mvn exec:exec                    a JDK -- LocalCasing answers
//   rontolisp App.java               the interpreter -- LocalCasing answers
//   rontolisp App.java -o App.class  the JVM -- LocalCasing answers
//   mvn package                      a WASM component -- the Rust component that
//     + build.sh                     wac plugs in answers
//
// So under a JDK and on the JVM this is Java calling Java; composed with the Rust
// component it is Java calling Rust. The output is identical either way.

package com.example.casing;

import java.util.List;
import java.util.ServiceLoader;

public class App {

    static final List<String> PHRASES = List.of("hello world", "component model", "rust and lisp");

    public static void main(String[] args) {
        // The implementation the interface has here: LocalCasing under a JDK, on the
        // interpreter and on the JVM; in the component, the Rust component's export.
        Casing casing = ServiceLoader.load(Casing.class).findFirst().orElseThrow();
        for (String phrase : PHRASES) {
            System.out.println(phrase + "  ->  " + casing.shout(phrase));
        }
    }

}
