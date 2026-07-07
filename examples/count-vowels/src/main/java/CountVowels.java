// The rontolisp equivalent of Endive's "sharing data through memory" tutorial
// (Endive is the successor to Chicory; see https://endive.run). Instead of a Rust
// count_vowels.wasm, the module here is compiled from count-vowels.lisp with
// --no-gc (a plain MVP module: no wasm-GC, no WASI imports), so the pure-Java
// Endive runtime instantiates it with no import object at all.
//
// Wasm only understands integers and floats, so we pass the string across the
// boundary as a pointer/length pair of raw UTF-8 bytes in the module's linear
// memory. The module exports its `memory` and a bump allocator
// `__ronto_alloc(size)` for exactly this: reserve space, write the bytes, then
// call `count_vowels(ptr, len)`. There is no general `dealloc` (__ronto_alloc is
// a bump allocator), but `count_vowels` returns a scalar, so its wrapper
// auto-frees the per-call internal string copy on return -- repeated calls on one
// instance don't leak it.
//
// For a long-lived (resident) host that allocates a fresh input buffer per call,
// the module also exports the arena API `__ronto_alloc_mark()` / `__ronto_alloc_reset(mark)`:
// snapshot the heap top before allocating the input, restore it after reading the
// result, and the instance stays flat forever (the second phase of main() below
// runs 100000 calls and asserts memory().pages() never grows).
//
// Build the compiler jar once (from the repo root), then run this host with Maven:
//   ./mvnw -q clean package -DskipTests
//   mvn -q compile exec:java -Dexec.args="Hello, World!"
// `mvn compile` also builds count_vowels.wasm from count-vowels.lisp (see pom.xml).

import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.Parser;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class CountVowels {

	public static void main(String[] args) {
		String message = args.length > 0 ? args[0] : "Hello, World!";

		// Instantiate the module. --no-gc emits no imports, so no import object is
		// needed -- the same "instantiate, then call the exports" shape as the
		// Endive tutorial.
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

		// A resident instance: call 100000 times, each with a fresh input buffer, and
		// keep memory flat with the arena API. Snapshot the heap top before allocating
		// the input (__ronto_alloc_mark), then pop back to it after reading the scalar
		// result (__ronto_alloc_reset) -- this reclaims the input buffer, which sits
		// below the wrapper's own auto-reset mark and would otherwise leak per call.
		ExportFunction mark = instance.export("__ronto_alloc_mark");
		ExportFunction reset = instance.export("__ronto_alloc_reset");
		int pagesBefore = memory.pages();
		for (int i = 0; i < 100_000; i++) {
			byte[] in = ("Hello, World! " + i).getBytes(StandardCharsets.UTF_8);
			long snapshot = mark.apply()[0];
			int p = (int) alloc.apply(in.length)[0];
			memory.write(p, in);
			countVowels.apply(p, in.length); // scalar result, fully consumed here
			reset.apply(snapshot);
		}
		int pagesAfter = memory.pages();
		System.out.println("resident 100000 calls: memory " + pagesBefore + " -> " + pagesAfter + " pages");
		if (pagesAfter != pagesBefore) {
			throw new AssertionError("resident loop grew memory from " + pagesBefore + " to " + pagesAfter + " pages");
		}
	}

}
