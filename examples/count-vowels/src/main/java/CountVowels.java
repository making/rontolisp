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
// call `count-vowels(ptr, len)`.
//
// A bump allocator never frees, so every call is bracketed with the module's
// arena API -- `__ronto_alloc_mark()` snapshots the heap top BEFORE the input
// buffer is allocated, `__ronto_alloc_reset(mark)` pops back to it AFTER the
// result has been read. That reclaims the input buffer and everything the call
// allocated internally, so a resident instance stays flat forever: the loop below
// runs 100000 calls and asserts memory().pages() never grows.
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

	// The module exports the host needs: the entry point, the bump allocator and the
	// arena API over it, plus the linear memory to write the input into.
	record Module(ExportFunction countVowels, ExportFunction alloc, ExportFunction mark, ExportFunction reset,
			Memory memory) {

		static Module of(Instance instance) {
			return new Module(instance.export("count-vowels"), instance.export("__ronto_alloc"),
					instance.export("__ronto_alloc_mark"), instance.export("__ronto_alloc_reset"), instance.memory());
		}

		// One call across the :string boundary, bracketed by the arena API.
		long countVowels(String message) {
			byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

			// Snapshot the heap top BEFORE allocating the input buffer.
			long snapshot = this.mark.apply()[0];

			// Reserve {len} bytes of module memory and write the string into it. alloc
			// returns a pointer to that memory; the :string ABI expects the raw UTF-8
			// bytes at that pointer (no length header on the host side).
			int ptr = (int) this.alloc.apply(bytes.length)[0];
			this.memory.write(ptr, bytes);

			// Call count-vowels with the (pointer, length) pair. It reads the bytes back
			// out of linear memory and returns the count.
			long result = this.countVowels.apply(ptr, bytes.length)[0];

			// The result is a scalar and is already read out, so pop the input buffer
			// (and the call's internal string copy) off the bump heap.
			this.reset.apply(snapshot);
			return result;
		}
	}

	public static void main(String[] args) {
		String message = args.length > 0 ? args[0] : "Hello, World!";

		// Instantiate the module. --no-gc emits no imports, so no import object is
		// needed -- the same "instantiate, then call the exports" shape as the
		// Endive tutorial.
		Instance instance = Instance.builder(Parser.parse(new File("count_vowels.wasm"))).build();
		Module module = Module.of(instance);

		long result = module.countVowels(message);
		System.out.println("\"" + message + "\" has " + result + " vowels");
		if (message.equals("Hello, World!") && result != 3L) {
			throw new AssertionError("expected 3 vowels in \"Hello, World!\", got " + result);
		}

		// A resident instance: call 100000 times, each with a fresh input string. The
		// arena bracket inside countVowels() pops every call's allocation, so linear
		// memory never grows.
		int pagesBefore = module.memory().pages();
		for (int i = 0; i < 100_000; i++) {
			module.countVowels("Hello, World! " + i);
		}
		int pagesAfter = module.memory().pages();
		System.out.println("resident 100000 calls: memory " + pagesBefore + " -> " + pagesAfter + " pages");
		if (pagesAfter != pagesBefore) {
			throw new AssertionError("resident loop grew memory from " + pagesBefore + " to " + pagesAfter + " pages");
		}
	}

}
