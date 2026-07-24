package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispLayout;

/**
 * Bakes every registered {@link LispLayout} into the module's static data segment and
 * returns each instance tag's absolute linear-memory address -- the WASM twin of the JVM
 * backend's {@code String[]} layout constants.
 *
 * <p>
 * An instance carries that address in field 0, so it is self-describing: {@code %obj-tag}
 * reads the tag text out of the record and the printer reads the type name and the slot
 * names, with no per-struct dispatch and no lookup table to keep in sync.
 *
 * <p>
 * Record shape (little-endian {@code i32} words, 4-byte aligned):
 *
 * <pre>
 * +0  kind      0 = struct (prints "#S(" ... ")"), 1 = class (prints "#&lt;" ... "&gt;")
 * +4  tagOff    byte offset of the tag text, e.g. "%struct-POINT"  (%obj-tag reads this)
 * +8  tagLen
 * +12 nameOff   byte offset of the printed type name, e.g. "POINT" (the printer reads this)
 * +16 nameLen
 * +20 slotCount
 * +24 slotCount * { +0 slotNameOff, +4 slotNameLen }
 * </pre>
 *
 * Both the tag and the print name are stored: {@code %obj-tag} must yield the tag while
 * the printer needs the name, and the two prefixes differ in length ({@code "%struct-"}
 * is 8, {@code "%class-"} is 7), so one cannot be derived from the other without a
 * kind-dependent skip.
 *
 * <p>
 * ORDERING: this must run after the string table is created and BEFORE Pass 2a, because
 * {@code %obj-new} bakes a record's address into an ordinary function body as an
 * {@code i32.const} -- unlike the eval registry, the intern table and the case-fold
 * tables, which are consumed only by runtime helper bodies built after their append. It
 * must also, like them, run before the data segment is snapshotted.
 *
 * <p>
 * EVERY registered layout is baked, not just the ones the program references, because the
 * tags a program uses only become known during Pass 2 (a {@code make-instance} or an
 * {@code error} deep inside a function body expands then), which is after the addresses
 * have to exist. The cost is the ~21 seeded condition layouts plus their names; the JVM
 * backend can afford to intern on demand only because its layout constants are minted
 * while bodies are compiled.
 */
final class WasmInstanceLayouts {

	private WasmInstanceLayouts() {
	}

	/** Byte offset of the kind word inside a layout record. */
	static final int OFF_KIND = 0;

	/** Byte offset of the tag text's data offset. */
	static final int OFF_TAG_OFF = 4;

	/** Byte offset of the tag text's length. */
	static final int OFF_TAG_LEN = 8;

	/** Byte offset of the printed type name's data offset. */
	static final int OFF_NAME_OFF = 12;

	/** Byte offset of the printed type name's length. */
	static final int OFF_NAME_LEN = 16;

	/** Byte offset of the slot count. */
	static final int OFF_SLOT_COUNT = 20;

	/** Byte offset of the first slot-name entry. */
	static final int OFF_SLOTS = 24;

	/** Size of one slot-name entry (offset + length). */
	static final int SLOT_ENTRY_BYTES = 8;

	/** The kind word of a struct layout. */
	static final int KIND_STRUCT = 0;

	/** The kind word of a class layout. */
	static final int KIND_CLASS = 1;

	/**
	 * Interns every layout's strings and appends its record to the static data segment.
	 * @param registry the compilation's CLOS/struct registry
	 * @param stringTable the module's string table, still open for appends
	 * @return instance tag to the absolute linear address of its layout record
	 */
	static Map<String, Integer> emit(ClosRegistry registry, WasmLispCompiler.StringTable stringTable) {
		Map<String, Integer> addresses = new LinkedHashMap<>();
		for (LispLayout layout : registry.layouts().values()) {
			// Every string is interned BEFORE the record is serialized: the record holds
			// their offsets.
			WasmLispCompiler.StringTable.StringEntry tag = stringTable.addString(layout.tag());
			WasmLispCompiler.StringTable.StringEntry name = stringTable.addString(layout.printName());
			List<WasmLispCompiler.StringTable.StringEntry> slots = layout.slotNames()
				.stream()
				.map(stringTable::addString)
				.toList();
			ByteArrayOutputStream record = new ByteArrayOutputStream();
			write32(record, layout.kind() == LispLayout.Kind.STRUCT ? KIND_STRUCT : KIND_CLASS);
			write32(record, tag.offset());
			write32(record, tag.length());
			write32(record, name.offset());
			write32(record, name.length());
			write32(record, slots.size());
			for (WasmLispCompiler.StringTable.StringEntry slot : slots) {
				write32(record, slot.offset());
				write32(record, slot.length());
			}
			addresses.put(layout.tag(), stringTable.appendBlob(record.toByteArray()));
		}
		return addresses;
	}

	private static void write32(ByteArrayOutputStream target, int value) {
		target.write(value & 0xFF);
		target.write((value >>> 8) & 0xFF);
		target.write((value >>> 16) & 0xFF);
		target.write((value >>> 24) & 0xFF);
	}

}
