// The provider that answers on a JDK, on the interpreter and on the JVM: the same
// two lines as the Lisp fallback in ../../wit/lisp-calls-rust/app.lisp. It is not
// compiled into the WebAssembly component -- a provider the program names is not
// consulted there, the host's is -- so in a component the Rust component answers.

package com.example.casing;

public class LocalCasing implements Casing {

    @Override
    public String shout(String text) {
        return text.toUpperCase() + "!";
    }

}
