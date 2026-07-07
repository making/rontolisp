///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.dylibso.chicory:runtime:1.2.1

// The rontolisp equivalent of Chicory's "Using Memory to share data" tutorial.
// Instead of a Rust count_vowels.wasm, the module here is compiled from
// count-vowels.lisp with --no-gc (a plain MVP module: no wasm-GC, no WASI
// imports), so the pure-Java Chicory runtime instantiates it with no import
// object at all.
//
// Wasm only understands integers and floats, so we pass the string across the
// boundary as a pointer/length pair of raw UTF-8 bytes in the module's linear
// memory. The module exports its `memory` and a bump allocator
// `__ronto_alloc(size)` for exactly this: reserve space, write the bytes, then
// call `count_vowels(ptr, len)`. There is no general `dealloc` (__ronto_alloc is
// a bump allocator), but `count_vowels` returns a scalar, so its wrapper
// auto-frees the per-call internal string copy on return -- repeated calls on one
// instance don't leak it. For a long-lived host, reserve one input buffer with
// __ronto_alloc and reuse it across calls (here we call once and exit).
//
// Build the module, then run this host:
//   java -jar ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar count-vowels.lisp \
//     -o count_vowels.wasm --no-gc --optimize
//   jbang CountVowels.java "Hello, World!"

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class CountVowels {

	public static void main(String[] args) {
		String message = args.length > 0 ? args[0] : "Hello, World!";

		// Instantiate the module. --no-gc emits no imports, so no import object is
		// needed -- the same "instantiate, then call the exports" shape as the
		// Chicory tutorial.
		Instance instance = Instance.builder(Parser.parse(new File("count_vowels.wasm"))).build();
		ExportFunction countVowels = instance.export("count_vowels");
		ExportFunction alloc = instance.export("__ronto_alloc");
		Memory memory = instance.memory();

		// Reserve {len} bytes of module memory and write the string into it. alloc
		// returns a pointer to that memory; the :string ABI expects the raw UTF-8
		// bytes at that pointer (no length header on the host side).
		byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
		int len = bytes.length;
		int ptr = (int) alloc.apply(len)[0];
		memory.write(ptr, bytes);

		// Call count_vowels with the (pointer, length) pair. It reads the bytes back
		// out of linear memory and returns the count.
		long result = countVowels.apply(ptr, len)[0];

		System.out.println("\"" + message + "\" has " + result + " vowels");
		if (message.equals("Hello, World!") && result != 3L) {
			throw new AssertionError("expected 3 vowels in \"Hello, World!\", got " + result);
		}
	}

}
