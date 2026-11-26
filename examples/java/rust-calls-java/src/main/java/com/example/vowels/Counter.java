// The Java component a Rust program calls.
//
// It implements the `counter` world of wit/vowels.wit (src/main/resources/com/
// example/vowels/wit/vowels.wit, the same WIT as ../../wit/rust-calls-lisp): the
// world exports one plain function, `vowel-count`, which is the static method
// vowelCount below. rontolisp checks the method against the world when it compiles
// the program: its name, its parameter and its result.
//
// The program has no main. Its entry point is this class's export, so rontolisp
// compiles it to a reactor component -- no wasi:cli/run, only vowel-count -- that
// `wac` plugs into the Rust describer:
//
//   mvn package        target/rust-calls-java.wasm
//   mvn test           CounterTest: vowelCount called as a method under a JDK
//
// Under a JDK, and on rontolisp's interpreter and JVM, @WitExport does nothing and
// vowelCount is an ordinary static method.

package com.example.vowels;

import am.ik.rontolisp.wit.WitExport;

@WitExport(value = "counter", wit = "wit/vowels.wit")
public class Counter {

    // WIT: vowel-count: func(text: string) -> s32
    public static int vowelCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isVowel(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    // A vowel is one of a e i o u, in either case.
    static boolean isVowel(char c) {
        return "aeiouAEIOU".indexOf(c) >= 0;
    }

}
