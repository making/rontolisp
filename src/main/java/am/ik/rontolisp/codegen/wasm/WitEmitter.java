package am.ik.rontolisp.codegen.wasm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.ComponentWriter;

/**
 * Renders the WIT text ({@code package root:component; world root { ... }}) describing a
 * {@code --component} output, for the CLI's {@code --wit} option, so hosts and binding
 * generators (e.g. {@code jco}) can consume the component's typed surface without
 * introspecting it via {@code wasm-tools component wit}.
 *
 * <p>
 * The emitted text is semantically identical to what {@code wasm-tools component wit}
 * prints for the same component: the fixed part (the world's WASI imports, the fixed
 * {@code wasi:cli/run} / {@code wasi:http/incoming-handler} export, and the referenced
 * package definitions) is a per-variant classpath template under {@code component/wit/}
 * captured from that tool's output, and only the {@code rontolisp:wasm-export} export
 * lines are rendered dynamically. The templates are regenerated together with the
 * component blobs; see {@code src/wasm-component/README.md}.
 */
final class WitEmitter {

	/** The GC component's base variant (no fetch, no sockets): {@code wit/base.wit}. */
	static final String VARIANT_BASE = "base";

	/** The GC component's {@code rontolisp:fetch} variant: {@code wit/http.wit}. */
	static final String VARIANT_HTTP = "http";

	/** The GC component's {@code rontolisp:tcp-*} variant: {@code wit/sock.wit}. */
	static final String VARIANT_SOCK = "sock";

	/** The GC {@code rontolisp:http-handler} variant: {@code wit/serve.wit}. */
	static final String VARIANT_SERVE = "serve";

	/** The serve+fetch variant: {@code wit/serve-http.wit}. */
	static final String VARIANT_SERVE_HTTP = "serve-http";

	/** The adapter-free {@code --no-gc} reactor variant: {@code wit/nogc.wit}. */
	static final String VARIANT_NOGC = "nogc";

	/** The {@code --no-gc} print-micro-adapter variant: {@code wit/nogc-print.wit}. */
	static final String VARIANT_NOGC_PRINT = "nogc-print";

	private WitEmitter() {
	}

	/**
	 * Renders the WIT text for a component of the given variant with the given export
	 * directives.
	 * @param variant one of the {@code VARIANT_*} template names
	 * @param exportDecls the {@code rontolisp:wasm-export} directives lifted as
	 * component-model exports, in export order (empty on the serve variants, whose only
	 * export is the fixed {@code wasi:http/incoming-handler})
	 * @return the WIT text (ends with a newline)
	 */
	static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls) {
		List<String> lines = new ArrayList<>(List.of(template(variant).split("\n", -1)));
		int insertAt = worldClosingBrace(lines);
		// wasm-tools separates the world's import block from its export block with one
		// blank line; re-add it when the template world ends on an import (the no-gc
		// print variant -- the GC templates already end on their fixed export).
		if (lines.get(insertAt - 1).startsWith("  import ")) {
			lines.add(insertAt++, "");
		}
		for (WasmExportCompiler.Decl decl : exportDecls) {
			lines.add(insertAt++, exportLine(decl));
		}
		return String.join("\n", lines);
	}

	// The world is the template's first block, so its closing brace is the first
	// column-zero "}" (the package definitions after it close with their own).
	private static int worldClosingBrace(List<String> lines) {
		for (int i = 0; i < lines.size(); i++) {
			if ("}".equals(lines.get(i))) {
				return i;
			}
		}
		throw new IllegalStateException("WIT template has no world closing brace");
	}

	// Renders one export line the way wasm-tools prints it, e.g.
	// " export noisy-mul: async func(p0: s32, p1: s32) -> s32;". Parameter names are
	// p0, p1, ... -- the names WasmComponentBuilder/NoGcWasmComponentBuilder encode into
	// the component's function types.
	private static String exportLine(WasmExportCompiler.Decl decl) {
		StringBuilder sb = new StringBuilder("  export ").append(decl.exportName())
			.append(": ")
			.append(decl.async() ? "async " : "")
			.append("func(");
		List<String> paramTypes = decl.paramTypes();
		for (int i = 0; i < paramTypes.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append("p").append(i).append(": ").append(witType(paramTypes.get(i)));
		}
		sb.append(")");
		Integer result = WasmExportCompiler.componentValType(decl.returnType());
		if (result != null) {
			sb.append(" -> ").append(witTypeName(result));
		}
		return sb.append(";").toString();
	}

	private static String witType(String designator) {
		Integer valType = WasmExportCompiler.componentValType(designator);
		if (valType == null) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + designator + " is not a WIT parameter type");
		}
		return witTypeName(valType);
	}

	private static String witTypeName(int valType) {
		return switch (valType) {
			case ComponentWriter.VT_S32 -> "s32";
			case ComponentWriter.VT_S64 -> "s64";
			case ComponentWriter.VT_F64 -> "f64";
			case ComponentWriter.VT_BOOL -> "bool";
			case ComponentWriter.VT_STRING -> "string";
			default -> throw new UnsupportedOperationException(
					"component value type 0x" + Integer.toHexString(valType) + " has no WIT rendering");
		};
	}

	private static String template(String variant) {
		String path = "component/wit/" + variant + ".wit";
		try (InputStream in = WitEmitter.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing WIT template resource: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
