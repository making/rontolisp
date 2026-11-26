package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.wasm.Type;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * The WIT function behind a canonical export
 * ({@code rontolisp:wasm-export ... :wit "text"}): an export whose types are the WIT's
 * own, lifted and lowered through the canonical ABI instead of the flat boundary's
 * designators. It is the export-side twin of a bound import function
 * ({@code WasmComponentImportCompiler.Decl}), in the opposite direction: a parameter is
 * LIFTED as an import's result is, the result LOWERED as an import's argument is.
 *
 * <p>
 * The core signature is the canonical ABI's for a lifted function: the parameters
 * flattened, or one pointer to their tuple in memory past
 * {@link WitCanonicalAbi#MAX_FLAT_PARAMS}; the result's one flat value, or one pointer to
 * its memory representation when it flattens to more.
 *
 * @param abi the layout calculator scoped where the function's type names resolve
 * @param func the function
 * @param ofWorld whether the scope is the world's own type definitions (a freestanding
 * function export), not an exported interface's
 * @param scopeName the scope's name: the exported interface's id, or the world's name
 * @param at the WIT file and line of the export ({@code path:line}), for a refusal
 * @param octets whether a {@code list<u8>} parameter is lifted as the octets it holds (an
 * {@code (unsigned-byte 8)} vector) instead of as text
 */
record WitExportSignature(WitCanonicalAbi abi, WitFunc func, boolean ofWorld, String scopeName, String at,
		boolean octets) {

	/**
	 * Reads the signature a canonical export form names.
	 * @param witText the WIT document
	 * @param world the world's name
	 * @param ifaceId the exported interface's id, or {@code null} for a freestanding
	 * function
	 * @param label the export's label
	 * @param at the WIT file and line of the export
	 * @param octets whether a {@code list<u8>} parameter is lifted as octets
	 * @return the signature
	 */
	static WitExportSignature of(String witText, String world, @Nullable String ifaceId, String label, String at,
			boolean octets) {
		WitExportDirective.Signature signature = WitExportDirective.signature(witText, world, ifaceId, label);
		return new WitExportSignature(new WitCanonicalAbi(signature.resolver(), signature.scope()), signature.func(),
				signature.ofWorld(), ifaceId == null ? world : ifaceId, at, octets);
	}

	/**
	 * {@return the function's parameter count}
	 */
	int arity() {
		return this.func.params().size();
	}

	/**
	 * {@return the parameter names, in order}
	 */
	List<String> paramNames() {
		return this.func.params().stream().map(WitFunc.Param::name).toList();
	}

	/**
	 * {@return the flat core types of every parameter, in order}
	 */
	List<Type> flatParams() {
		List<Type> flats = new ArrayList<>();
		for (WitFunc.Param param : this.func.params()) {
			flats.addAll(this.abi.flatTypes(param.type()));
		}
		return flats;
	}

	/**
	 * {@return whether the parameters arrive as one pointer to their tuple in memory}
	 * (they flatten past {@link WitCanonicalAbi#MAX_FLAT_PARAMS})
	 */
	boolean spilledParams() {
		return flatParams().size() > WitCanonicalAbi.MAX_FLAT_PARAMS;
	}

	/**
	 * {@return the core parameter types of the lifted function}
	 */
	Type[] coreParams() {
		return spilledParams() ? new Type[] { Type.I32 } : flatParams().toArray(new Type[0]);
	}

	/**
	 * {@return whether the result is answered as one pointer to its memory
	 * representation} (it flattens past {@link WitCanonicalAbi#MAX_FLAT_RESULTS})
	 */
	boolean indirectResult() {
		WitType result = this.func.result();
		return result != null && this.abi.flatTypes(result).size() > WitCanonicalAbi.MAX_FLAT_RESULTS;
	}

	/**
	 * {@return the core result types of the lifted function}
	 */
	Type[] coreResults() {
		WitType result = this.func.result();
		if (result == null) {
			return new Type[0];
		}
		return indirectResult() ? new Type[] { Type.I32 } : this.abi.flatTypes(result).toArray(new Type[0]);
	}

	/**
	 * {@return whether the export touches linear memory}: a parameter the host lowers
	 * into it (a string or a list anywhere inside, or the spilled tuple), or a result
	 * answered there. Such an export is lifted with the canonical memory options and a
	 * post-return.
	 */
	boolean usesMemory() {
		if (spilledParams() || indirectResult()) {
			return true;
		}
		for (WitFunc.Param param : this.func.params()) {
			if (holdsBytes(param.type(), this.abi)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@return whether a parameter holds a {@code list<u8>} this export lifts as octets}
	 */
	boolean liftsOctets() {
		if (!this.octets) {
			return false;
		}
		for (WitFunc.Param param : this.func.params()) {
			if (WasmComponentImportCompiler.liftsListU8(param.type(), this.abi)) {
				return true;
			}
		}
		return false;
	}

	// Whether a value of this type holds a string or a list anywhere inside.
	private static boolean holdsBytes(@Nullable WitType type, WitCanonicalAbi abi) {
		return switch (type) {
			case null -> false;
			case WitType.Prim prim -> "string".equals(prim.name());
			case WitType.ListOf ignored -> true;
			case WitType.OptionOf opt -> holdsBytes(opt.element(), abi);
			case WitType.ResultOf res -> holdsBytes(res.ok(), abi) || holdsBytes(res.err(), abi);
			case WitType.TupleOf tuple -> tuple.elements().stream().anyMatch(element -> holdsBytes(element, abi));
			case WitType.Named named -> {
				WitCanonicalAbi in = abi.scopeOf(named);
				yield switch (abi.resolveNamed(named)) {
					case WitItem.TypeAlias alias -> holdsBytes(alias.target(), in);
					case WitItem.RecordDef record ->
						record.fields().stream().anyMatch(field -> holdsBytes(field.type(), in));
					case WitItem.VariantDef variant ->
						variant.cases().stream().anyMatch(c -> holdsBytes(c.payload(), in));
					default -> false;
				};
			}
			default -> false;
		};
	}

}
