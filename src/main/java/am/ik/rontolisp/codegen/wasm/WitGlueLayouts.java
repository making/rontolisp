package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.compiler.CanonicalLayout;
import am.ik.rontolisp.compiler.HostGlueEmitter;
import am.ik.wasm.Type;
import am.ik.wit.WitItem;
import am.ik.wit.WitPrinter;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * A {@code --no-wasi} module's canonical-ABI imports and exports as the JavaScript glue
 * lifts and lowers them: each function's parameters and result as
 * {@link CanonicalLayout}s, computed by the layout calculator the module's own
 * marshalling wrappers use ({@link WitCanonicalAbi}), so the glue's offsets, sizes and
 * discriminants are the wrappers'.
 */
final class WitGlueLayouts {

	private WitGlueLayouts() {
	}

	/**
	 * The glue's view of a bound WIT function.
	 * @param decl the binding
	 * @return its import as the glue sees it
	 */
	static HostGlueEmitter.WitImport of(WasmComponentImportCompiler.Decl decl) {
		WitCanonicalAbi abi = decl.abi();
		WitResolver.Func func = decl.func();
		List<String> names = new ArrayList<>();
		List<CanonicalLayout> params = new ArrayList<>();
		if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
			names.add("self");
			params.add(new CanonicalLayout.Handle(resource(abi, func.resource()), false));
		}
		List<String> declared = new ArrayList<>();
		for (var param : func.def().func().params()) {
			names.add(param.name());
			params.add(layout(abi, param.type()));
			declared.add(param.name() + ": " + WitPrinter.type(param.type()));
		}
		WitType result = abi.resultType(func);
		WitType written = func.def().func().result();
		String signature = "func(" + String.join(", ", declared) + ")"
				+ (written == null ? "" : " -> " + WitPrinter.type(written));
		boolean raises = result != null && abi.resolveAliases(result).type() instanceof WitType.ResultOf;
		return new HostGlueEmitter.WitImport(decl.module(), decl.field(), signature, List.copyOf(names),
				List.copyOf(params), result == null ? null : layout(abi, result), decl.sig().retptr(), raises);
	}

	/**
	 * The glue's view of a canonical export: what the glue lowers a JavaScript caller's
	 * arguments by and lifts the module's answer by.
	 * @param signature the export's WIT function
	 * @return its export as the glue sees it
	 */
	static HostGlueEmitter.WitExport of(WitExportSignature signature) {
		WitCanonicalAbi abi = signature.abi();
		List<CanonicalLayout> params = new ArrayList<>();
		List<WitType> types = new ArrayList<>();
		List<String> declared = new ArrayList<>();
		for (var param : signature.func().params()) {
			params.add(layout(abi, param.type()));
			types.add(param.type());
			declared.add(param.name() + ": " + WitPrinter.type(param.type()));
		}
		WitType result = signature.func().result();
		String written = "func(" + String.join(", ", declared) + ")"
				+ (result == null ? "" : " -> " + WitPrinter.type(result));
		boolean raises = result != null && abi.resolveAliases(result).type() instanceof WitType.ResultOf;
		// Past 16 flats the parameters cross as one tuple in memory, laid out as the
		// tuple of their types is.
		CanonicalLayout spilled = signature.spilledParams() ? fields("tuple", abi, new WitType.TupleOf(types)) : null;
		return new HostGlueEmitter.WitExport(written, signature.paramNames(), List.copyOf(params), spilled,
				result == null ? null : layout(abi, result), signature.indirectResult(), raises);
	}

	/**
	 * The glue's view of a resource's release.
	 * @param drop the binding
	 * @return its import as the glue sees it
	 */
	static HostGlueEmitter.WitImport of(WasmComponentImportCompiler.Drop drop) {
		return new HostGlueEmitter.WitImport(drop.module(), drop.field(), "resource-drop", List.of("self"),
				List.of(new CanonicalLayout.Handle(drop.module() + "#" + drop.resource(), true)), null, false, false);
	}

	private static CanonicalLayout layout(WitCanonicalAbi abi, WitType type) {
		WitCanonicalAbi.Scoped scoped = abi.resolveAliases(type);
		WitCanonicalAbi in = scoped.abi();
		WitType t = scoped.type();
		return switch (t) {
			case WitType.Prim prim ->
				"string".equals(prim.name()) ? new CanonicalLayout.Text() : new CanonicalLayout.Scalar(prim.name());
			case WitType.ListOf list -> in.isU8(list.element()) ? new CanonicalLayout.Octets()
					: new CanonicalLayout.ListOf(layout(in, list.element()));
			case WitType.OptionOf ignored -> cases("option", in, t);
			case WitType.ResultOf ignored -> cases("result", in, t);
			case WitType.TupleOf ignored -> fields("tuple", in, t);
			case WitType.BorrowOf borrow -> new CanonicalLayout.Handle(resource(in, borrow.resource()), false);
			case WitType.OwnOf own -> new CanonicalLayout.Handle(resource(in, own.resource()), true);
			case WitType.Named named -> switch (in.resolveNamed(named)) {
				case WitItem.RecordDef ignored -> fields("record", in, t);
				case WitItem.VariantDef ignored -> cases("variant", in, t);
				case WitItem.EnumDef ignored -> cases("enum", in, t);
				case WitItem.ResourceDef ignored -> new CanonicalLayout.Handle(resource(in, named.name()), true);
				case WitItem.FlagsDef flags -> new CanonicalLayout.Flags(
						flags.cases().stream().map(WitItem.Case::name).toList(), WitCanonicalAbi.flagsSize(flags));
				default -> throw unsupported(named.name());
			};
			default -> throw unsupported(WitPrinter.type(t));
		};
	}

	private static CanonicalLayout cases(String kind, WitCanonicalAbi in, WitType type) {
		WitCanonicalAbi.VariantInfo info = in.variantInfo(type);
		List<@Nullable CanonicalLayout> payloads = new ArrayList<>();
		for (WitType payload : info.payloads()) {
			payloads.add(payload == null ? null : layout(info.abi(), payload));
		}
		return new CanonicalLayout.Cases(kind, List.copyOf(info.names()),
				java.util.Collections.unmodifiableList(payloads), info.discSize(), info.payloadOffset(), in.size(type),
				in.alignment(type), flat(in.flatTypes(type)));
	}

	private static CanonicalLayout fields(String kind, WitCanonicalAbi in, WitType type) {
		WitCanonicalAbi.RecordInfo info = in.recordInfo(type);
		List<CanonicalLayout> types = new ArrayList<>();
		for (WitType field : info.types()) {
			types.add(layout(info.abi(), field));
		}
		return new CanonicalLayout.Fields(kind, List.copyOf(info.names()), List.copyOf(types),
				List.copyOf(info.offsets()), in.size(type), in.alignment(type), flat(in.flatTypes(type)));
	}

	// A resource as its defining interface's canonical id and its name: the key of the
	// glue's table of its values, the same whichever interface a handle crosses in.
	private static String resource(WitCanonicalAbi in, String name) {
		WitResolver.Owned owned = in.resolveOwned(new WitType.Named(name));
		String id = in.resolver().canonicalId(owned.owner());
		return Objects.requireNonNullElse(id, owned.owner().name()) + "#" + name;
	}

	private static List<String> flat(List<Type> types) {
		List<String> flat = new ArrayList<>();
		for (Type type : types) {
			flat.add(type == Type.I64 ? "i64" : type == Type.F32 ? "f32" : type == Type.F64 ? "f64" : "i32");
		}
		return List.copyOf(flat);
	}

	private static UnsupportedOperationException unsupported(String what) {
		return new UnsupportedOperationException(
				"the WIT type '" + what + "' does not cross a --no-wasi module's canonical-ABI boundary");
	}

}
