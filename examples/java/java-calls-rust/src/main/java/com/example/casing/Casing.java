// The WIT interface example:textkit/casing as a Java interface: `shout` is its one
// function, which rontolisp checks this declaration against when it compiles the
// program. The WIT is found as a resource of this interface's package, so the Rust
// component and this program agree on what they share.
//
// What answers `shout` is a service provider. Under a JDK, on the interpreter and on
// the JVM it is LocalCasing, named in META-INF/services/com.example.casing.Casing. In
// the WebAssembly component nothing of this program implements it: the module imports
// the interface, and the Rust component that wac plugs in answers.

package com.example.casing;

import am.ik.rontolisp.wit.WitImport;

@WitImport(value = "example:textkit/casing", wit = "wit/textkit.wit")
public interface Casing {

    String shout(String text);

}
