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

	private static final int SECTION_DATA = 11;

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

	/**
	 * Returns the linear-memory address at which an active data segment of the core
	 * module places {@code needle}, or -1 when no segment holds it -- after a shake, -1
	 * means the bytes were cut. Only the flag-0 segments the backends emit are read.
	 */
	public static int dataAddressOf(byte[] module, byte[] needle) {
		int[] cursor = { 8 };
		while (cursor[0] < module.length) {
			int sectionId = module[cursor[0]++] & 0xFF;
			int sectionSize = readUnsignedLeb128(module, cursor);
			int sectionEnd = cursor[0] + sectionSize;
			if (sectionId == SECTION_DATA) {
				int count = readUnsignedLeb128(module, cursor);
				for (int i = 0; i < count; i++) {
					int flags = readUnsignedLeb128(module, cursor);
					if (flags != 0 || module[cursor[0]++] != 0x41) {
						throw new IllegalArgumentException("not an active i32.const segment");
					}
					int offset = readSignedLeb128(module, cursor);
					cursor[0]++; // end
					int length = readUnsignedLeb128(module, cursor);
					int at = indexOf(module, cursor[0], cursor[0] + length, needle);
					if (at >= 0) {
						return offset + at - cursor[0];
					}
					cursor[0] += length;
				}
				return -1;
			}
			cursor[0] = sectionEnd;
		}
		return -1;
	}

	private static int indexOf(byte[] bytes, int from, int to, byte[] needle) {
		outer: for (int i = from; i + needle.length <= to; i++) {
			for (int k = 0; k < needle.length; k++) {
				if (bytes[i + k] != needle[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static int readSignedLeb128(byte[] bytes, int[] cursor) {
		int result = 0;
		int shift = 0;
		int b;
		do {
			b = bytes[cursor[0]++] & 0xFF;
			result |= (b & 0x7F) << shift;
			shift += 7;
		}
		while ((b & 0x80) != 0);
		if (shift < 32 && (b & 0x40) != 0) {
			result |= -1 << shift;
		}
		return result;
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
