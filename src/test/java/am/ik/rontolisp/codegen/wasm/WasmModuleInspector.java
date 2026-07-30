package am.ik.rontolisp.codegen.wasm;

/**
 * Minimal reader for the code section of an emitted WebAssembly binary -- a core module
 * or a component -- used by tests that need to assert on the SIZE of the emitted function
 * bodies rather than on their contents.
 * <p>
 * The size of the single largest function body is a load-bearing property of the WASM
 * backend: a wasmtime cold compile (Cranelift) needs memory that grows superlinearly in
 * the size of one function, so a monolithic body is what decides whether a large program
 * can be run at all. See {@link WasmToplevelChunkingTest} for the pinned bound and the
 * measurements behind it.
 * <p>
 * A {@code --component} binary must be measured through the same entry point: it wraps a
 * core module whose bodies differ from the Preview 1 module's (an async top level
 * compiles as an entry+resume pair), so inspecting only the core build hides exactly the
 * case where the component is the larger one.
 */
public final class WasmModuleInspector {

	private WasmModuleInspector() {
	}

	private static final int SECTION_CODE = 10;

	/** Component section carrying a nested core module (its payload is a core binary). */
	private static final int COMPONENT_SECTION_CORE_MODULE = 1;

	/**
	 * Component section carrying a nested component (its payload is a component binary).
	 */
	private static final int COMPONENT_SECTION_COMPONENT = 4;

	/**
	 * Returns the size in bytes of the largest function body in the binary, or 0 when it
	 * has no code section. For a component this is the largest body over every core
	 * module it embeds, at any nesting depth.
	 */
	public static int largestFunctionBodySize(byte[] binary) {
		return isComponent(binary) ? largestInComponent(binary) : largestInCoreModule(binary);
	}

	/**
	 * True when the binary's preamble declares layer 1 (a component) rather than layer 0
	 * (a core module). The layer is the 16-bit little-endian field after the version.
	 */
	private static boolean isComponent(byte[] binary) {
		if (binary.length < 8) {
			return false;
		}
		return ((binary[6] & 0xFF) | ((binary[7] & 0xFF) << 8)) == 1;
	}

	private static int largestInComponent(byte[] component) {
		int[] cursor = { 8 };
		int largest = 0;
		while (cursor[0] < component.length) {
			int sectionId = component[cursor[0]++] & 0xFF;
			int sectionSize = readUnsignedLeb128(component, cursor);
			int sectionStart = cursor[0];
			int sectionEnd = sectionStart + sectionSize;
			if (sectionId == COMPONENT_SECTION_CORE_MODULE || sectionId == COMPONENT_SECTION_COMPONENT) {
				byte[] nested = java.util.Arrays.copyOfRange(component, sectionStart, sectionEnd);
				largest = Math.max(largest, largestFunctionBodySize(nested));
			}
			cursor[0] = sectionEnd;
		}
		return largest;
	}

	private static int largestInCoreModule(byte[] module) {
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
