package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.ComponentWriter;
import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;
import org.jspecify.annotations.Nullable;

/**
 * Encodes an imported WIT interface as a component-model <strong>instance type</strong>
 * (the {@code ty} of the component import entry), restricted to the functions the program
 * binds. The declaration grammar and index rules were byte-validated against
 * {@code wasm-tools component new} output (see {@code ComponentWriterTest}): a resource
 * is an {@code export "name" (type (sub resource))} declaration, a named type
 * (variant/record/enum/flags) is a type declaration followed by an {@code eq}-bound
 * export of the same name, structural types (list/option/result/tuple) are unexported
 * type declarations deduplicated by shape, and both type declarations and type-bound
 * exports append to the instance type's LOCAL type index space (function exports do not).
 * References always point at already-declared indices, so everything is created on first
 * use, in function order.
 */
final class WitComponentTypeEncoder {

	private final WitCanonicalAbi abi;

	private final List<byte[]> decls = new ArrayList<>();

	// Structural memo: a rendered shape key -> the local type index that defines it.
	private final Map<String, Integer> memo = new LinkedHashMap<>();

	private int nextLocal;

	private WitComponentTypeEncoder(WitCanonicalAbi abi) {
		this.abi = abi;
	}

	/**
	 * Encodes the instance type of the given import (its bound functions only).
	 * @param imported the parsed component import
	 * @return the encoded instance type bytes
	 */
	static byte[] encode(WasmComponentImportCompiler.Import imported) {
		WitComponentTypeEncoder encoder = new WitComponentTypeEncoder(
				new WitCanonicalAbi(imported.resolver(), imported.iface()));
		for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
			encoder.declareFunction(decl);
		}
		return ComponentWriter.instanceTypeOf(encoder.decls);
	}

	private void declareFunction(WasmComponentImportCompiler.Decl decl) {
		WitResolver.Func func = decl.func();
		List<String> paramNames = new ArrayList<>();
		List<byte[]> paramTypes = new ArrayList<>();
		if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
			paramNames.add("self");
			paramTypes.add(ComponentWriter.valTypeIndex(borrowIndex(func.resource())));
		}
		for (var param : func.def().func().params()) {
			paramNames.add(param.name());
			paramTypes.add(valType(param.type()));
		}
		WitType result = this.abi.resultType(func);
		byte[] resultType = result == null ? null : valType(result);
		int funcType = addDecl(ComponentWriter.funcTypeOf(paramNames, paramTypes, resultType));
		this.decls.add(ComponentWriter.instanceDeclExportFunc(decl.field(), funcType));
	}

	// The encoded valtype operand of a WIT type use: a primitive code, or the local
	// index of its (memoized, on-demand) type declaration.
	private byte[] valType(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> ComponentWriter.valTypePrim(primCode(prim.name()));
			case WitType.ListOf list -> ComponentWriter.valTypeIndex(memoized("list<" + key(list.element()) + ">",
					() -> ComponentWriter.definedListOf(valType(list.element()))));
			case WitType.OptionOf opt -> ComponentWriter.valTypeIndex(memoized("option<" + key(opt.element()) + ">",
					() -> ComponentWriter.definedOptionOf(valType(opt.element()))));
			case WitType.ResultOf res -> ComponentWriter.valTypeIndex(memoized(
					"result<" + (res.ok() == null ? "_" : key(res.ok())) + ","
							+ (res.err() == null ? "_" : key(res.err())) + ">",
					() -> ComponentWriter.definedResultOf(res.ok() == null ? null : valType(res.ok()),
							res.err() == null ? null : valType(res.err()))));
			case WitType.TupleOf tuple -> ComponentWriter.valTypeIndex(memoized(key(tuple), () -> {
				List<byte[]> elements = new ArrayList<>();
				for (WitType element : tuple.elements()) {
					elements.add(valType(element));
				}
				return ComponentWriter.definedTupleOf(elements);
			}));
			case WitType.BorrowOf borrow -> ComponentWriter.valTypeIndex(borrowIndex(borrow.resource()));
			case WitType.OwnOf own -> ComponentWriter.valTypeIndex(ownIndex(own.resource()));
			case WitType.Named named -> switch (this.abi.resolveNamed(named)) {
				case WitItem.TypeAlias alias -> valType(alias.target());
				// A bare resource reference is an own handle (WIT's rule; borrow must be
				// explicit).
				case WitItem.ResourceDef resource -> ComponentWriter.valTypeIndex(ownIndex(resource.name()));
				case WitItem.RecordDef record -> ComponentWriter.valTypeIndex(namedIndex(record.name(), () -> {
					List<String> names = new ArrayList<>();
					List<byte[]> types = new ArrayList<>();
					for (WitItem.Field field : record.fields()) {
						names.add(field.name());
						types.add(valType(field.type()));
					}
					return ComponentWriter.definedRecordOf(names, types);
				}));
				case WitItem.VariantDef variant -> ComponentWriter.valTypeIndex(namedIndex(variant.name(), () -> {
					List<String> names = new ArrayList<>();
					List<byte @Nullable []> payloads = new ArrayList<>();
					for (WitItem.Case c : variant.cases()) {
						names.add(c.name());
						payloads.add(c.payload() == null ? null : valType(c.payload()));
					}
					return ComponentWriter.definedVariantOf(names, payloads);
				}));
				case WitItem.EnumDef en -> ComponentWriter.valTypeIndex(namedIndex(en.name(),
						() -> ComponentWriter.definedEnumOf(en.cases().stream().map(WitItem.Case::name).toList())));
				case WitItem.FlagsDef flags -> ComponentWriter.valTypeIndex(namedIndex(flags.name(),
						() -> ComponentWriter.definedFlagsOf(flags.cases().stream().map(WitItem.Case::name).toList())));
				default -> throw new UnsupportedOperationException(
						"the WIT type '" + named.name() + "' cannot be encoded as a component import type");
			};
			default -> throw new UnsupportedOperationException("the WIT type '" + type.getClass().getSimpleName()
					+ "' cannot be encoded as a component import type");
		};
	}

	// A structurally-memoized unexported type declaration.
	private int memoized(String shapeKey, java.util.function.Supplier<byte[]> encode) {
		Integer existing = this.memo.get(shapeKey);
		if (existing != null) {
			return existing;
		}
		// Encode FIRST (creating any nested declarations), then claim this type's index.
		byte[] encoded = encode.get();
		int index = addDecl(encoded);
		this.memo.put(shapeKey, index);
		return index;
	}

	// A named (variant/record/enum/flags) definition: one type declaration + an eq-bound
	// export of the WIT name; references use the exported index (the wasm-tools shape).
	// get/put rather than computeIfAbsent: encoding the definition can recursively
	// declare (and memoize) the types its fields use.
	private int namedIndex(String name, java.util.function.Supplier<byte[]> encode) {
		Integer existing = this.memo.get("named:" + name);
		if (existing != null) {
			return existing;
		}
		byte[] encoded = encode.get();
		int defined = addDecl(encoded);
		this.decls.add(ComponentWriter.instanceDeclExportTypeEq(name, defined));
		int exported = this.nextLocal++;
		this.memo.put("named:" + name, exported);
		return exported;
	}

	private int resourceIndex(String name) {
		Integer existing = this.memo.get("resource:" + name);
		if (existing != null) {
			return existing;
		}
		this.decls.add(ComponentWriter.instanceDeclExportResource(name));
		int index = this.nextLocal++;
		this.memo.put("resource:" + name, index);
		return index;
	}

	private int borrowIndex(String resource) {
		int resourceIdx = resourceIndex(resource);
		return memoized("borrow:" + resource, () -> ComponentWriter.definedBorrow(resourceIdx));
	}

	private int ownIndex(String resource) {
		int resourceIdx = resourceIndex(resource);
		return memoized("own:" + resource, () -> ComponentWriter.definedOwn(resourceIdx));
	}

	private int addDecl(byte[] definedType) {
		this.decls.add(ComponentWriter.instanceDeclType(definedType));
		return this.nextLocal++;
	}

	// A stable structural key for memoization (named types key by name, which is unique
	// within the interface scope).
	private String key(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> prim.name();
			case WitType.ListOf list -> "list<" + key(list.element()) + ">";
			case WitType.OptionOf opt -> "option<" + key(opt.element()) + ">";
			case WitType.ResultOf res -> "result<" + (res.ok() == null ? "_" : key(res.ok())) + ","
					+ (res.err() == null ? "_" : key(res.err())) + ">";
			case WitType.TupleOf tuple -> {
				StringBuilder sb = new StringBuilder("tuple<");
				for (WitType element : tuple.elements()) {
					sb.append(key(element)).append(',');
				}
				yield sb.append('>').toString();
			}
			case WitType.BorrowOf borrow -> "borrow<" + borrow.resource() + ">";
			case WitType.OwnOf own -> "own<" + own.resource() + ">";
			case WitType.Named named -> "named:" + named.name();
			default -> type.toString();
		};
	}

	private static int primCode(String name) {
		return switch (name) {
			case "bool" -> ComponentWriter.VT_BOOL;
			case "s8" -> ComponentWriter.VT_S8;
			case "u8" -> ComponentWriter.VT_U8;
			case "s16" -> ComponentWriter.VT_S16;
			case "u16" -> ComponentWriter.VT_U16;
			case "s32" -> ComponentWriter.VT_S32;
			case "u32" -> ComponentWriter.VT_U32;
			case "s64" -> ComponentWriter.VT_S64;
			case "u64" -> ComponentWriter.VT_U64;
			case "f32" -> ComponentWriter.VT_F32;
			case "f64" -> ComponentWriter.VT_F64;
			case "char" -> ComponentWriter.VT_CHAR;
			case "string" -> ComponentWriter.VT_STRING;
			default -> throw new UnsupportedOperationException("Unknown WIT primitive: " + name);
		};
	}

}
