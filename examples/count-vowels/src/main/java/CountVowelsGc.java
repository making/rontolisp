// The wasm-GC counterpart of CountVowels: the same count-vowels.lisp compiled with
// --no-wasi (and no --no-gc), so Lisp values live on the GC heap and the engine
// collects them. Endive runs it as of 1.0.1 (GC support); it imports nothing, so
// again no import object is needed -- but it is a reactor module, so the host calls
// the exported `_initialize` once after instantiating.
//
// What the GC does NOT collect is the buffer the HOST writes the input into: that
// lives in linear memory, which is an opaque byte array the engine never traces.
// __ronto_alloc is still a bump allocator and the GC backend has no
// __ronto_alloc_mark/_reset (see .todo/124), so allocate ONE buffer up front and
// reuse it across calls -- the instance then stays flat.
//
//   mvn -q compile exec:java -Dexec.mainClass=CountVowelsGc
//   mvn -q exec:java -Dexec.mainClass=CountVowelsGc -Dexec.args=Programming

import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.Parser;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class CountVowelsGc {

	// The one reused input buffer. Inputs longer than this would need a bigger one.
	private static final int BUFFER_SIZE = 256;

	// The module exports the host needs, plus the mailbox at the boundary: one input
	// buffer, allocated once and reused by every call.
	record Module(ExportFunction countVowels, Memory memory, int buffer) {

		static Module of(Instance instance) {
			instance.export("_initialize").apply(); // reactor module: initialize once
			int buffer = (int) instance.export("__ronto_alloc").apply(BUFFER_SIZE)[0];
			return new Module(instance.export("count-vowels"), instance.memory(), buffer);
		}

		// One call across the :string boundary, through the reused buffer.
		long countVowels(String message) {
			byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > BUFFER_SIZE) {
				throw new IllegalArgumentException("input longer than the reused buffer: " + bytes.length + " bytes");
			}
			this.memory.write(this.buffer, bytes);
			return this.countVowels.apply(this.buffer, bytes.length)[0];
		}
	}

	public static void main(String[] args) {
		String message = args.length > 0 ? args[0] : "Hello, World!";

		Instance instance = Instance.builder(Parser.parse(new File("count_vowels_gc.wasm"))).build();
		Module module = Module.of(instance);

		long result = module.countVowels(message);
		System.out.println("\"" + message + "\" has " + result + " vowels");
		if (message.equals("Hello, World!") && result != 3L) {
			throw new AssertionError("expected 3 vowels in \"Hello, World!\", got " + result);
		}

		// A resident instance: the module copies each input into a GC string the engine
		// reclaims, and the host writes into the same buffer every time, so linear
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
