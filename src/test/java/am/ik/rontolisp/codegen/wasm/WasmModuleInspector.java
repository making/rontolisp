package am.ik.rontolisp.codegen.wasm;

/**
 * Minimal reader for the code section of an emitted core WebAssembly module, used by
 * tests that need to assert on the SIZE of the emitted function bodies rather than on
 * their contents.
 * <p>
 * The size of the single largest function body is a load-bearing property of the WASM
 * backend: a wasmtime cold compile (Cranelift) needs memory that grows superlinearly in
 * the size of one function, so a monolithic body is what decides whether a large program
 * can be run at all. See {@link WasmToplevelChunkingTest} for the pinned bound and the
 * measurements behind it.
 */
public final class WasmModuleInspector {

	private WasmModuleInspector() {
	}

	private static final int SECTION_CODE = 10;

	/**
	 * Returns the size in bytes of the largest function body in the module's code
	 * section, or 0 when the module has no code section.
	 */
	public static int largestFunctionBodySize(byte[] module) {
		int[] cursor = { 8 }; // skip the 8-byte magic + version header
		while (cursor[0] < module.length) {
			int sectionId = module[cursor[0]++] & 0xFF;
			int sectionSize = readUnsignedLeb128(module, cursor);
			int sectionEnd = cursor[0] + sectionSize;
			if (sectionId == SECTION_CODE) {
				int count = readUnsignedLeb128(module, cursor);
				int largest = 0;
				for (int i = 0; i < count; i++) {
					int bodySize = readUnsignedLeb128(module, cursor);
					largest = Math.max(largest, bodySize);
					cursor[0] += bodySize;
				}
				return largest;
			}
			cursor[0] = sectionEnd;
		}
		return 0;
	}

	private static int readUnsignedLeb128(byte[] bytes, int[] cursor) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = bytes[cursor[0]++] & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
		}
	}

}
