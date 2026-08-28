package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the minimum page count the emitted module declares for its linear memory.
 *
 * <p>
 * What lives above the static data there is the bump heap -- one identity per
 * runtime-created string -- so its need follows what a program builds at load time, not a
 * constant. The rule was three fixed growth pages (~192 KB), which a library that reads
 * tens of thousands of strings in exhausts mid-load, trapping out of bounds with nothing
 * but an address to go on (cl-unicode, {@code .todo/545}). It is now "at least as much
 * heap as static data", which leaves a small program on exactly the old three pages --
 * the reason a memory-capped host is not handed a large constant instead.
 */
class WasmLinearMemoryHeadroomTest {

	/** The min-pages field of the module's memory section (id 5). */
	private static int declaredMinPages(byte[] module) {
		int offset = 8; // magic + version
		while (offset < module.length) {
			int id = module[offset++] & 0xFF;
			int[] size = readLeb128(module, offset);
			offset = size[1];
			if (id == 5) {
				// count, flags, min -- the compiler emits exactly one memory, min only.
				assertThat(module[offset] & 0xFF).isEqualTo(1);
				assertThat(module[offset + 1] & 0xFF).isEqualTo(0);
				return readLeb128(module, offset + 2)[0];
			}
			offset += size[0];
		}
		throw new AssertionError("the module declares no memory section");
	}

	/**
	 * {@return the value and the offset just past it}
	 */
	private static int[] readLeb128(byte[] bytes, int offset) {
		int value = 0;
		int shift = 0;
		while (true) {
			int b = bytes[offset++] & 0xFF;
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return new int[] { value, offset };
			}
			shift += 7;
		}
	}

	private static byte[] compile(String source) {
		List<LispVal> program = LispReader.readAllFromString(source);
		return new WasmLispCompiler(false, false, false, OptimizeLevel.NONE).compile(program);
	}

	@Test
	void aProgramWithNoStaticDataOfItsOwnKeepsTheOldFloor() {
		assertThat(declaredMinPages(compile("(print 1)"))).isEqualTo(4);
	}

	@Test
	void headroomFollowsTheStaticDataRatherThanAFixedThreePages() {
		// One 400 KB string constant, kept out of reach of the pure-builtin fold: six
		// pages of static data on top of the runtime's own, and the heap above it has to
		// grow with them.
		String big = "(defvar *s* \"" + "x".repeat(400_000) + "\") (print (length *s*))";
		int pages = declaredMinPages(compile(big));
		assertThat(pages).isGreaterThan(2 * 400_000 / 65536);
	}

	@Test
	void theRuleIsTheStaticDataPlusAHeapAtLeastAsLarge() {
		assertThat(WasmLispCompiler.memoryMinPages(0)).isEqualTo(4);
		// Below the floor the four pages win; above it the data is doubled.
		assertThat(WasmLispCompiler.memoryMinPages(65536)).isEqualTo(4);
		assertThat(WasmLispCompiler.memoryMinPages(4 * 65536)).isEqualTo(8);
		assertThat(WasmLispCompiler.memoryMinPages(76 * 65536)).isEqualTo(152);
		// A program whose data does not reach the minimum headroom keeps that instead.
		assertThat(WasmLispCompiler.memoryMinPages(2 * 65536)).isEqualTo(2 + WasmLispCompiler.HEAP_HEADROOM_MIN_PAGES);
	}

}
