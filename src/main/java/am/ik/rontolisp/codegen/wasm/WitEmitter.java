package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.ComponentWriter;
import am.ik.wit.WitDocument;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitMeta;
import am.ik.wit.WitPrinter;
import am.ik.wit.WitType;

/**
 * Renders the WIT text ({@code package root:component; world root { ... }}) describing a
 * {@code --component} output, for the CLI's {@code --wit} option, so hosts and binding
 * generators (e.g. {@code jco}) can consume the component's typed surface without
 * introspecting it via {@code wasm-tools component wit}.
 *
 * <p>
 * The fixed part (the world's WASI imports, the fixed {@code wasi:cli/run} /
 * {@code wasi:http/incoming-handler} export, and the referenced package definitions) is
 * the per-variant {@link WasiWitDefinitions} document model; the
 * {@code rontolisp:wasm-export} directives are appended to the world as typed export
 * items, and the whole document is printed by {@link WitPrinter} in the canonical
 * {@code wasm-tools component wit} style — byte-identical to that tool's output on the
 * same component bytes (the http-server variants deliberately restore incoming-handler's
 * {@code use} clause that tool drops; see {@code src/wasm-component/README.md}).
 */
final class WitEmitter {

	/** The GC component's base variant (no fetch, no sockets). */
	static final String VARIANT_BASE = "base";

	/** The GC component's {@code rontolisp:fetch} (outgoing HTTP) variant. */
	static final String VARIANT_HTTP_CLIENT = "http-client";

	/** The GC component's {@code rontolisp:tcp-*} variant. */
	static final String VARIANT_SOCKETS = "sockets";

	/** The GC {@code rontolisp:http-handler} (incoming HTTP) variant. */
	static final String VARIANT_HTTP_SERVER = "http-server";

	/** The http-server variant of a handler program that also uses fetch. */
	static final String VARIANT_HTTP_SERVER_CLIENT = "http-server-client";

	/** The adapter-free {@code --no-gc} reactor variant. */
	static final String VARIANT_NOGC = "nogc";

	/** The {@code --no-gc} print-micro-adapter variant. */
	static final String VARIANT_NOGC_PRINT = "nogc-print";

	private WitEmitter() {
	}

	/**
	 * Renders the WIT text for a component of the given variant with the given export
	 * directives.
	 * @param variant one of the {@code VARIANT_*} names
	 * @param exportDecls the {@code rontolisp:wasm-export} directives lifted as
	 * component-model exports, in export order (empty on the http-server variants, whose
	 * only export is the fixed {@code wasi:http/incoming-handler})
	 * @return the WIT text (ends with a newline)
	 */
	static String emit(String variant, List<WasmExportCompiler.Decl> exportDecls) {
		WitDocument document = WasiWitDefinitions.document(variant);
		if (!exportDecls.isEmpty()) {
			WitItem.World world = document.world();
			List<WitItem> items = new ArrayList<>(world.items());
			for (WasmExportCompiler.Decl decl : exportDecls) {
				items.add(exportItem(decl));
			}
			document = document.withWorld(new WitItem.World(world.meta(), world.name(), List.copyOf(items)));
		}
		return WitPrinter.print(document);
	}

	// Builds one typed world export the way wasm-tools prints it, e.g.
	// " export noisy-mul: async func(p0: s32, p1: s32) -> s32;". Parameter names are
	// p0, p1, ... -- the names WasmComponentBuilder/NoGcWasmComponentBuilder encode into
	// the component's function types.
	private static WitItem exportItem(WasmExportCompiler.Decl decl) {
		List<WitFunc.Param> params = new ArrayList<>();
		List<String> paramTypes = decl.paramTypes();
		for (int i = 0; i < paramTypes.size(); i++) {
			params.add(new WitFunc.Param("p" + i, witType(paramTypes.get(i))));
		}
		Integer result = WasmExportCompiler.componentValType(decl.returnType());
		WitFunc func = new WitFunc(decl.async(), List.copyOf(params), result == null ? null : witTypeOf(result));
		return new WitItem.ExportNamed(WitMeta.none(), decl.exportName(), new WitItem.Extern.ExternFunc(func));
	}

	private static WitType witType(String designator) {
		Integer valType = WasmExportCompiler.componentValType(designator);
		if (valType == null) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-export type " + designator + " is not a WIT parameter type");
		}
		return witTypeOf(valType);
	}

	private static WitType witTypeOf(int valType) {
		return new WitType.Prim(switch (valType) {
			case ComponentWriter.VT_S32 -> "s32";
			case ComponentWriter.VT_S64 -> "s64";
			case ComponentWriter.VT_F64 -> "f64";
			case ComponentWriter.VT_BOOL -> "bool";
			case ComponentWriter.VT_STRING -> "string";
			default -> throw new UnsupportedOperationException(
					"component value type 0x" + Integer.toHexString(valType) + " has no WIT rendering");
		});
	}

}
