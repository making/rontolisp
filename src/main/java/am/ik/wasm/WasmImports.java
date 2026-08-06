package am.ik.wasm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;

/**
 * Reads the import section of a core WebAssembly module.
 * <p>
 * Language-independent, like the rest of {@code am.ik.wasm}. It exists because more than
 * one caller needs to know what a FINISHED module still imports -- a question that only
 * has an answer after {@link WasmTreeShaker} has dropped the imports nothing reaches, and
 * whose answer then drives what has to be wired around the module (which adapter entry
 * points, which host interfaces, how large a shared memory).
 */
public final class WasmImports {

	private static final int SEC_IMPORT = 2;

	/** A function import, in the core binary format's import-kind numbering. */
	public static final int KIND_FUNC = 0x00;

	/** A table import. */
	public static final int KIND_TABLE = 0x01;

	/** A memory import; the only kind that carries a {@link Entry#memoryMinPages()}. */
	public static final int KIND_MEMORY = 0x02;

	/** A global import. */
	public static final int KIND_GLOBAL = 0x03;

	/**
	 * One import declaration.
	 *
	 * @param module the two-level name's module part
	 * @param field the two-level name's field part
	 * @param kind one of the {@code KIND_*} constants
	 * @param memoryMinPages the declared minimum page count of a {@link #KIND_MEMORY}
	 * import, or {@code -1} for every other kind
	 */
	public record Entry(String module, String field, int kind, int memoryMinPages) {
	}

	private WasmImports() {
	}

	/**
	 * Every import the module declares, in declaration order.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the import entries; empty when the module has no import section
	 */
	public static List<Entry> of(byte[] module) {
		int[] p = { 8 }; // "\0asm" + version
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = readU(module, p);
			int bodyEnd = p[0] + size;
			if (id == SEC_IMPORT) {
				return parse(module, p, bodyEnd);
			}
			p[0] = bodyEnd;
		}
		return List.of();
	}

	/**
	 * The field names of the FUNCTION imports the module takes from one import module, in
	 * declaration order.
	 * @param module a core WASM module
	 * @param importModule the two-level name's module part
	 * ({@code "wasi_snapshot_preview1"})
	 * @return the field names
	 */
	public static LinkedHashSet<String> functionFields(byte[] module, String importModule) {
		LinkedHashSet<String> fields = new LinkedHashSet<>();
		for (Entry e : of(module)) {
			if (e.kind() == KIND_FUNC && e.module().equals(importModule)) {
				fields.add(e.field());
			}
		}
		return fields;
	}

	/**
	 * The declared minimum page count of an imported memory.
	 * @param module a core WASM module
	 * @param importModule the two-level name's module part
	 * @param field the two-level name's field part
	 * @return the minimum page count, or empty when the module imports no such memory
	 */
	public static OptionalInt memoryMinPages(byte[] module, String importModule, String field) {
		for (Entry e : of(module)) {
			if (e.kind() == KIND_MEMORY && e.module().equals(importModule) && e.field().equals(field)) {
				return OptionalInt.of(e.memoryMinPages());
			}
		}
		return OptionalInt.empty();
	}

	private static List<Entry> parse(byte[] module, int[] p, int bodyEnd) {
		int count = readU(module, p);
		List<Entry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String mod = readName(module, p);
			String field = readName(module, p);
			int kind = module[p[0]++] & 0xff;
			int minPages = -1;
			switch (kind) {
				case KIND_FUNC -> readU(module, p); // typeidx
				case KIND_TABLE -> {
					p[0]++; // element reftype
					skipLimits(module, p);
				}
				case KIND_MEMORY -> minPages = skipLimits(module, p);
				case KIND_GLOBAL -> {
					p[0]++; // valtype
					p[0]++; // mutability
				}
				default -> throw new IllegalStateException("WasmImports: unknown import kind " + kind);
			}
			entries.add(new Entry(mod, field, kind, minPages));
		}
		if (p[0] != bodyEnd) {
			throw new IllegalStateException("WasmImports: import section did not end where its size said");
		}
		return entries;
	}

	// Reads a limits record and returns its minimum.
	private static int skipLimits(byte[] module, int[] p) {
		int flags = module[p[0]++] & 0xff;
		int min = readU(module, p);
		if ((flags & 0x01) != 0) {
			readU(module, p); // max
		}
		return min;
	}

	private static String readName(byte[] buf, int[] p) {
		int len = readU(buf, p);
		String name = new String(buf, p[0], len, java.nio.charset.StandardCharsets.UTF_8);
		p[0] += len;
		return name;
	}

	private static int readU(byte[] buf, int[] p) {
		int value = 0;
		int shift = 0;
		while (true) {
			int b = buf[p[0]++] & 0xff;
			value |= (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
		}
	}

}
