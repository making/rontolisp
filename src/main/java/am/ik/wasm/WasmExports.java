package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrows the export surface of a core WebAssembly module.
 * <p>
 * A hand-written helper module (a WASI adapter, a bridge) is written once with every
 * entry point any consumer might bind, but a given consumer binds only some of them --
 * and after {@link WasmTreeShaker} has run on that consumer, exactly which ones is KNOWN.
 * Retaining only those exports turns the rest into unreachable code, so a following
 * {@link WasmTreeShaker#shake(byte[])} deletes them and everything only they reached,
 * including the module's own imports.
 * <p>
 * Retaining also RENAMES, which is what lets one helper module offer a narrow and a wide
 * implementation of the same entry point (a {@code fd_write} that can only reach
 * stdout/stderr beside one that can also reach a file) and lets the wrapper pick per
 * program: the chosen implementation is retained under the name the consumer imports.
 * Doing it here rather than with a component-level alias keeps the choice free of bytes
 * and of index churn -- the consumer still binds one name out of one instance.
 */
public final class WasmExports {

	private static final int SEC_EXPORT = 7;

	private WasmExports() {
	}

	/**
	 * The names of every export the module declares, in declaration order.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the export names
	 */
	public static LinkedHashSet<String> names(byte[] module) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		byte[] payload = exportSection(module);
		if (payload == null) {
			return names;
		}
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			names.add(readName(payload, p));
			p[0]++; // kind
			readU(payload, p); // index
		}
		return names;
	}

	/**
	 * Rewrites the module's export section to keep only the named exports, each under the
	 * name its key gives it. Everything else stops being exported, hence stops being a
	 * {@link WasmTreeShaker} root.
	 * <p>
	 * A key naming an export the module does not declare is an
	 * {@link IllegalStateException}: the consumer would fail to instantiate against the
	 * result anyway, and failing here says which name is missing.
	 * @param module a core WASM module
	 * @param keepAs the exports to keep, mapping the module's own export name to the name
	 * to export it under (use the same string for both to keep a name unchanged);
	 * iteration order fixes the resulting section's order
	 * @return the module with a narrowed export section, or the input unchanged when it
	 * already exports exactly these names in this order
	 */
	public static byte[] retain(byte[] module, Map<String, String> keepAs) {
		byte[] payload = exportSection(module);
		if (payload == null) {
			throw new IllegalStateException("WasmExports: the module has no export section");
		}
		record Export(String name, int kind, int index) {
		}
		List<Export> declared = new ArrayList<>();
		int[] p = { 0 };
		int count = readU(payload, p);
		for (int i = 0; i < count; i++) {
			String name = readName(payload, p);
			int kind = payload[p[0]++] & 0xff;
			declared.add(new Export(name, kind, readU(payload, p)));
		}
		Set<String> declaredNames = new LinkedHashSet<>();
		for (Export e : declared) {
			declaredNames.add(e.name());
		}
		for (String from : keepAs.keySet()) {
			if (!declaredNames.contains(from)) {
				throw new IllegalStateException(
						"WasmExports: the module does not export '" + from + "' (it exports " + declaredNames + ")");
			}
		}
		ByteArrayOutputStream entries = new ByteArrayOutputStream();
		for (Map.Entry<String, String> entry : keepAs.entrySet()) {
			Export e = declared.stream()
				.filter(d -> d.name().equals(entry.getKey()))
				.findFirst()
				.orElseThrow(IllegalStateException::new);
			writeName(entries, entry.getValue());
			entries.write(e.kind());
			writeU(entries, e.index());
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeU(body, keepAs.size());
		body.writeBytes(entries.toByteArray());
		byte[] newPayload = body.toByteArray();
		return java.util.Arrays.equals(newPayload, payload) ? module : replaceExportSection(module, newPayload);
	}

	private static byte @org.jspecify.annotations.Nullable [] exportSection(byte[] module) {
		int[] p = { 8 };
		while (p[0] < module.length) {
			int id = module[p[0]++] & 0xff;
			int size = readU(module, p);
			int bodyEnd = p[0] + size;
			if (id == SEC_EXPORT) {
				byte[] payload = new byte[size];
				System.arraycopy(module, p[0], payload, 0, size);
				return payload;
			}
			p[0] = bodyEnd;
		}
		return null;
	}

	private static byte[] replaceExportSection(byte[] module, byte[] newPayload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(module, 0, 8);
		int[] p = { 8 };
		while (p[0] < module.length) {
			int start = p[0];
			int id = module[p[0]++] & 0xff;
			int size = readU(module, p);
			int bodyEnd = p[0] + size;
			if (id == SEC_EXPORT) {
				out.write(SEC_EXPORT);
				writeU(out, newPayload.length);
				out.writeBytes(newPayload);
			}
			else {
				out.write(module, start, bodyEnd - start);
			}
			p[0] = bodyEnd;
		}
		return out.toByteArray();
	}

	private static String readName(byte[] buf, int[] p) {
		int len = readU(buf, p);
		String name = new String(buf, p[0], len, java.nio.charset.StandardCharsets.UTF_8);
		p[0] += len;
		return name;
	}

	private static void writeName(ByteArrayOutputStream out, String name) {
		byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		writeU(out, bytes.length);
		out.writeBytes(bytes);
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

	private static void writeU(ByteArrayOutputStream out, int value) {
		int v = value;
		while (true) {
			int b = v & 0x7f;
			v >>>= 7;
			if (v == 0) {
				out.write(b);
				return;
			}
			out.write(b | 0x80);
		}
	}

}
