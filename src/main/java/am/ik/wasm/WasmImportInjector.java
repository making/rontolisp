package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Language-independent post-pass that injects host function imports into a finished core
 * WebAssembly module.
 *
 * <p>
 * The rontolisp WASM backend holds every function index fixed at compile time (see the
 * index-stability invariant in {@code CLAUDE.md}), but the WASM spec places all imported
 * functions before all defined functions in the function index space, so adding an import
 * necessarily shifts every defined-function index. Instead of threading a dynamic offset
 * through every emitter, the backend emits calls to imported functions against
 * placeholder indices ({@code placeholderBase + ordinal}, far beyond any real index) and
 * this pass rewrites the finished module in one sweep, exactly like
 * {@link WasmTreeShaker} renumbers surviving functions:
 *
 * <ul>
 * <li>the new function imports are prepended to the import section (created right before
 * the function section when absent, e.g. under {@code --no-wasi}), so import {@code j}
 * gets global function index {@code j};</li>
 * <li>every {@code call}/{@code ref.func} immediate in every body is remapped: a
 * placeholder {@code placeholderBase + j} becomes {@code j}, any other reference shifts
 * up by the number of injected imports;</li>
 * <li>function indices in the export and start sections shift the same way.</li>
 * </ul>
 *
 * <p>
 * The type indices of the injected imports must already exist in the module's type
 * section (the backend appends them after its fixed types, which this pass copies
 * verbatim). The pass composes with {@link WasmTreeShaker}: run the injector first (the
 * module is not valid until the placeholders are resolved), then shake.
 */
public final class WasmImportInjector {

	// Section ids.
	private static final int SEC_IMPORT = 2;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_EXPORT = 7;

	private static final int SEC_START = 8;

	private static final int SEC_CODE = 10;

	// Import / export descriptor kinds.
	private static final int KIND_FUNC = 0x00;

	private WasmImportInjector() {
	}

	/**
	 * A host function import to inject.
	 *
	 * @param module the import module name (e.g. {@code "env"})
	 * @param name the import field name
	 * @param typeIndex the function type index (must already exist in the type section)
	 */
	public record HostImport(String module, String name, int typeIndex) {
	}

	/**
	 * Injects the given function imports and resolves the placeholder call indices.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @param hostImports the imports to inject, in ordinal order (placeholder
	 * {@code placeholderBase + j} resolves to the {@code j}-th entry)
	 * @param placeholderBase the placeholder base the backend emitted calls against; must
	 * exceed the module's total function count
	 * @return the module with the imports injected and every function reference
	 * renumbered; the input is returned unchanged when {@code hostImports} is empty
	 */
	public static byte[] inject(byte[] module, List<HostImport> hostImports, int placeholderBase) {
		if (hostImports.isEmpty()) {
			return module;
		}
		int shift = hostImports.size();
		List<WasmSections.Section> sections = WasmSections.parseSections(module);
		List<WasmSections.Section> rebuilt = new ArrayList<>(sections.size() + 1);
		boolean importSectionSeen = false;
		for (WasmSections.Section s : sections) {
			switch (s.id()) {
				case SEC_IMPORT -> {
					importSectionSeen = true;
					rebuilt.add(new WasmSections.Section(SEC_IMPORT, prependImports(s.payload(), hostImports)));
				}
				case SEC_FUNCTION -> {
					// A module with no import section (e.g. --no-wasi) gets a fresh one
					// right before the function section, preserving section order.
					if (!importSectionSeen) {
						importSectionSeen = true;
						rebuilt.add(new WasmSections.Section(SEC_IMPORT, prependImports(null, hostImports)));
					}
					rebuilt.add(s);
				}
				case SEC_CODE ->
					rebuilt.add(new WasmSections.Section(SEC_CODE, rewriteCode(s.payload(), shift, placeholderBase)));
				case SEC_EXPORT -> rebuilt.add(new WasmSections.Section(SEC_EXPORT, shiftExports(s.payload(), shift)));
				case SEC_START -> rebuilt.add(new WasmSections.Section(SEC_START, shiftStart(s.payload(), shift)));
				default -> rebuilt.add(s);
			}
		}
		return WasmSections.assemble(rebuilt);
	}

	// Prepends the injected entries to the import section payload (or builds a fresh
	// payload when the module had none), so the new imports occupy function indices
	// 0..shift-1 ahead of any existing function imports.
	private static byte[] prependImports(byte @Nullable [] existingPayload, List<HostImport> hostImports) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		int existingCount = 0;
		byte[] existingEntries = new byte[0];
		if (existingPayload != null) {
			int[] p = { 0 };
			existingCount = WasmSections.readU(existingPayload, p);
			existingEntries = WasmSections.slice(existingPayload, p[0], existingPayload.length);
		}
		WasmSections.writeU(body, hostImports.size() + existingCount);
		for (HostImport imp : hostImports) {
			writeName(body, imp.module());
			writeName(body, imp.name());
			body.write(KIND_FUNC);
			WasmSections.writeU(body, imp.typeIndex());
		}
		WasmSections.writeRaw(body, existingEntries);
		return body.toByteArray();
	}

	// Rewrites every call/ref.func immediate: a placeholder resolves to its import
	// index, everything else shifts past the injected imports.
	private static byte[] rewriteCode(byte[] payload, int shift, int placeholderBase) {
		List<byte[]> entries = WasmSections.parseCodeEntries(payload);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, entries.size());
		for (byte[] entry : entries) {
			byte[] rewritten = rewriteBody(entry, shift, placeholderBase);
			WasmSections.writeU(body, rewritten.length);
			WasmSections.writeRaw(body, rewritten);
		}
		return body.toByteArray();
	}

	private static byte[] rewriteBody(byte[] entry, int shift, int placeholderBase) {
		List<WasmSections.CallSite> sites = WasmSections.scanCallSites(entry);
		if (sites.isEmpty()) {
			return entry;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int cursor = 0;
		for (WasmSections.CallSite cs : sites) {
			WasmSections.writeRaw(out, WasmSections.slice(entry, cursor, cs.operandStart()));
			int target = cs.target();
			WasmSections.writeU(out, target >= placeholderBase ? target - placeholderBase : target + shift);
			cursor = cs.operandEnd();
		}
		WasmSections.writeRaw(out, WasmSections.slice(entry, cursor, entry.length));
		return out.toByteArray();
	}

	private static byte[] shiftExports(byte[] payload, int shift) {
		int[] p = { 0 };
		int count = WasmSections.readU(payload, p);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, count);
		for (int i = 0; i < count; i++) {
			int nameStart = p[0];
			WasmSections.skipName(payload, p);
			WasmSections.writeRaw(body, WasmSections.slice(payload, nameStart, p[0]));
			int kind = payload[p[0]++] & 0xff;
			int index = WasmSections.readU(payload, p);
			body.write(kind);
			WasmSections.writeU(body, kind == KIND_FUNC ? index + shift : index);
		}
		return body.toByteArray();
	}

	private static byte[] shiftStart(byte[] payload, int shift) {
		int[] p = { 0 };
		int index = WasmSections.readU(payload, p);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, index + shift);
		return body.toByteArray();
	}

	private static void writeName(ByteArrayOutputStream out, String name) {
		byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
		WasmSections.writeU(out, bytes.length);
		WasmSections.writeRaw(out, bytes);
	}

}
