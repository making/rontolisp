package am.ik.wit;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Static factories for building a {@link WitDocument} model programmatically, in the
 * shape generated code and binding generators want: every factory attaches
 * {@link WitMeta#none()} (construct the records directly to attach docs or gates).
 *
 * <p>
 * Intended for use with {@code import static am.ik.wit.Wit.*;}.
 */
public final class Wit {

	private Wit() {
	}

	// ---------------------------------------------------------------- documents

	/**
	 * Builds a document from top-level items.
	 * @param items the top-level items in order
	 * @return the document
	 */
	public static WitDocument document(WitItem... items) {
		return new WitDocument(List.of(items));
	}

	/**
	 * A file-level package declaration, e.g. {@code package root:component;}.
	 * @param namespace the namespace
	 * @param name the package name
	 * @param version the version, or {@code null} when unversioned
	 * @return the item
	 */
	public static WitItem.PackageHeader packageHeader(String namespace, String name, @Nullable String version) {
		return new WitItem.PackageHeader(WitMeta.none(), new WitPackageName(namespace, name, version));
	}

	/**
	 * An explicit package block, e.g. {@code package wasi:cli@0.3.0 '{' ... '}'}.
	 * @param namespace the namespace
	 * @param name the package name
	 * @param version the version, or {@code null} when unversioned
	 * @param items the contained worlds and interfaces
	 * @return the item
	 */
	public static WitItem.PackageBlock packageBlock(String namespace, String name, @Nullable String version,
			WitItem... items) {
		return new WitItem.PackageBlock(WitMeta.none(), new WitPackageName(namespace, name, version), List.of(items));
	}

	/**
	 * A {@code world} definition.
	 * @param name the world name
	 * @param items the world members
	 * @return the item
	 */
	public static WitItem.World world(String name, WitItem... items) {
		return new WitItem.World(WitMeta.none(), name, List.of(items));
	}

	/**
	 * An {@code interface} definition.
	 * @param name the interface name
	 * @param items the interface members
	 * @return the item
	 */
	public static WitItem.InterfaceDef iface(String name, WitItem... items) {
		return new WitItem.InterfaceDef(WitMeta.none(), name, List.of(items));
	}

	// ---------------------------------------------------------------- references

	/**
	 * A fully qualified interface reference, e.g. {@code wasi:io/streams@0.2.0}.
	 * @param namespace the package namespace
	 * @param pkg the package name
	 * @param iface the interface name
	 * @param version the package version, or {@code null} when unversioned
	 * @return the reference
	 */
	public static WitRef ref(String namespace, String pkg, String iface, @Nullable String version) {
		return new WitRef(new WitPackageName(namespace, pkg, version), iface);
	}

	/**
	 * A local (unqualified) reference, e.g. {@code types}.
	 * @param name the interface or world name
	 * @return the reference
	 */
	public static WitRef localRef(String name) {
		return WitRef.local(name);
	}

	// ---------------------------------------------------------------- world members

	/**
	 * {@code import <target>;}.
	 * @param target the imported interface
	 * @return the item
	 */
	public static WitItem.ImportRef importRef(WitRef target) {
		return new WitItem.ImportRef(WitMeta.none(), target);
	}

	/**
	 * {@code export <target>;}.
	 * @param target the exported interface
	 * @return the item
	 */
	public static WitItem.ExportRef exportRef(WitRef target) {
		return new WitItem.ExportRef(WitMeta.none(), target);
	}

	/**
	 * {@code import <name>: func(...);}.
	 * @param name the import name
	 * @param type the function type
	 * @return the item
	 */
	public static WitItem.ImportNamed importFunc(String name, WitFunc type) {
		return new WitItem.ImportNamed(WitMeta.none(), name, new WitItem.Extern.ExternFunc(type));
	}

	/**
	 * {@code export <name>: func(...);}.
	 * @param name the export name
	 * @param type the function type
	 * @return the item
	 */
	public static WitItem.ExportNamed exportFunc(String name, WitFunc type) {
		return new WitItem.ExportNamed(WitMeta.none(), name, new WitItem.Extern.ExternFunc(type));
	}

	/**
	 * {@code include <target>;}.
	 * @param target the included world
	 * @return the item
	 */
	public static WitItem.Include include(WitRef target) {
		return new WitItem.Include(WitMeta.none(), target);
	}

	// ---------------------------------------------------------------- interface members

	/**
	 * {@code use <path>.{<names>};}.
	 * @param path the source interface
	 * @param names the imported names
	 * @return the item
	 */
	public static WitItem.Use use(WitRef path, WitItem.UseName... names) {
		return new WitItem.Use(WitMeta.none(), path, List.of(names));
	}

	/**
	 * One plain name inside a {@code use} clause.
	 * @param name the name
	 * @return the use-name
	 */
	public static WitItem.UseName useName(String name) {
		return new WitItem.UseName(name, null);
	}

	/**
	 * One renamed name inside a {@code use} clause, {@code <name> as <alias>}.
	 * @param name the name in the source interface
	 * @param alias the local name
	 * @return the use-name
	 */
	public static WitItem.UseName useNameAs(String name, String alias) {
		return new WitItem.UseName(name, alias);
	}

	/**
	 * {@code type <name> = <target>;}.
	 * @param name the alias name
	 * @param target the aliased type
	 * @return the item
	 */
	public static WitItem.TypeAlias typeAlias(String name, WitType target) {
		return new WitItem.TypeAlias(WitMeta.none(), name, target);
	}

	/**
	 * A {@code record} definition.
	 * @param name the record name
	 * @param fields the fields
	 * @return the item
	 */
	public static WitItem.RecordDef record(String name, WitItem.Field... fields) {
		return new WitItem.RecordDef(WitMeta.none(), name, List.of(fields));
	}

	/**
	 * One {@code record} field.
	 * @param name the field name
	 * @param type the field type
	 * @return the field
	 */
	public static WitItem.Field field(String name, WitType type) {
		return new WitItem.Field(WitMeta.none(), name, type);
	}

	/**
	 * A {@code variant} definition.
	 * @param name the variant name
	 * @param cases the cases
	 * @return the item
	 */
	public static WitItem.VariantDef variant(String name, WitItem.Case... cases) {
		return new WitItem.VariantDef(WitMeta.none(), name, List.of(cases));
	}

	/**
	 * A payload-less {@code variant} case.
	 * @param name the case name
	 * @return the case
	 */
	public static WitItem.Case vcase(String name) {
		return new WitItem.Case(WitMeta.none(), name, null);
	}

	/**
	 * A {@code variant} case with a payload.
	 * @param name the case name
	 * @param payload the payload type
	 * @return the case
	 */
	public static WitItem.Case vcase(String name, WitType payload) {
		return new WitItem.Case(WitMeta.none(), name, payload);
	}

	/**
	 * An {@code enum} definition.
	 * @param name the enum name
	 * @param cases the case names
	 * @return the item
	 */
	public static WitItem.EnumDef enumDef(String name, String... cases) {
		return new WitItem.EnumDef(WitMeta.none(), name, cases(cases));
	}

	/**
	 * A {@code flags} definition.
	 * @param name the flags name
	 * @param names the flag names
	 * @return the item
	 */
	public static WitItem.FlagsDef flags(String name, String... names) {
		return new WitItem.FlagsDef(WitMeta.none(), name, cases(names));
	}

	private static List<WitItem.Case> cases(String[] names) {
		return List.of(names).stream().map(Wit::vcase).toList();
	}

	/**
	 * A bodiless {@code resource} declaration, {@code resource <name>;}.
	 * @param name the resource name
	 * @return the item
	 */
	public static WitItem.ResourceDef resource(String name) {
		return new WitItem.ResourceDef(WitMeta.none(), name, null);
	}

	/**
	 * A {@code resource} definition with a body.
	 * @param name the resource name
	 * @param first the first member
	 * @param rest the remaining members
	 * @return the item
	 */
	public static WitItem.ResourceDef resource(String name, WitItem first, WitItem... rest) {
		List<WitItem> body = new java.util.ArrayList<>();
		body.add(first);
		body.addAll(List.of(rest));
		return new WitItem.ResourceDef(WitMeta.none(), name, List.copyOf(body));
	}

	// ---------------------------------------------------------------- functions

	/**
	 * A named plain function member, {@code <name>: func(...);}.
	 * @param name the function name
	 * @param type the function type
	 * @return the item
	 */
	public static WitItem.FuncDef func(String name, WitFunc type) {
		return new WitItem.FuncDef(WitMeta.none(), name, WitItem.FuncKind.PLAIN, type);
	}

	/**
	 * A resource static function, {@code <name>: static func(...);}.
	 * @param name the function name
	 * @param type the function type
	 * @return the item
	 */
	public static WitItem.FuncDef staticFunc(String name, WitFunc type) {
		return new WitItem.FuncDef(WitMeta.none(), name, WitItem.FuncKind.STATIC, type);
	}

	/**
	 * A resource constructor, {@code constructor(...);}.
	 * @param params the constructor parameters
	 * @return the item
	 */
	public static WitItem.FuncDef constructor(WitFunc.Param... params) {
		return new WitItem.FuncDef(WitMeta.none(), "constructor", WitItem.FuncKind.CONSTRUCTOR,
				new WitFunc(false, List.of(params), null));
	}

	/**
	 * A synchronous function type with no result.
	 * @param params the parameters
	 * @return the function type
	 */
	public static WitFunc funcType(WitFunc.Param... params) {
		return new WitFunc(false, List.of(params), null);
	}

	/**
	 * A synchronous function type with a result.
	 * @param result the result type
	 * @param params the parameters
	 * @return the function type
	 */
	public static WitFunc funcType(WitType result, WitFunc.Param... params) {
		return new WitFunc(false, List.of(params), result);
	}

	/**
	 * An {@code async func} type with no result.
	 * @param params the parameters
	 * @return the function type
	 */
	public static WitFunc asyncFuncType(WitFunc.Param... params) {
		return new WitFunc(true, List.of(params), null);
	}

	/**
	 * An {@code async func} type with a result.
	 * @param result the result type
	 * @param params the parameters
	 * @return the function type
	 */
	public static WitFunc asyncFuncType(WitType result, WitFunc.Param... params) {
		return new WitFunc(true, List.of(params), result);
	}

	/**
	 * One function parameter.
	 * @param name the parameter name
	 * @param type the parameter type
	 * @return the parameter
	 */
	public static WitFunc.Param param(String name, WitType type) {
		return new WitFunc.Param(name, type);
	}

	// ---------------------------------------------------------------- types

	/**
	 * The {@code bool} primitive.
	 * @return the type
	 */
	public static WitType bool() {
		return new WitType.Prim("bool");
	}

	/**
	 * The {@code u8} primitive.
	 * @return the type
	 */
	public static WitType u8() {
		return new WitType.Prim("u8");
	}

	/**
	 * The {@code u16} primitive.
	 * @return the type
	 */
	public static WitType u16() {
		return new WitType.Prim("u16");
	}

	/**
	 * The {@code u32} primitive.
	 * @return the type
	 */
	public static WitType u32() {
		return new WitType.Prim("u32");
	}

	/**
	 * The {@code u64} primitive.
	 * @return the type
	 */
	public static WitType u64() {
		return new WitType.Prim("u64");
	}

	/**
	 * The {@code s8} primitive.
	 * @return the type
	 */
	public static WitType s8() {
		return new WitType.Prim("s8");
	}

	/**
	 * The {@code s16} primitive.
	 * @return the type
	 */
	public static WitType s16() {
		return new WitType.Prim("s16");
	}

	/**
	 * The {@code s32} primitive.
	 * @return the type
	 */
	public static WitType s32() {
		return new WitType.Prim("s32");
	}

	/**
	 * The {@code s64} primitive.
	 * @return the type
	 */
	public static WitType s64() {
		return new WitType.Prim("s64");
	}

	/**
	 * The {@code f32} primitive.
	 * @return the type
	 */
	public static WitType f32() {
		return new WitType.Prim("f32");
	}

	/**
	 * The {@code f64} primitive.
	 * @return the type
	 */
	public static WitType f64() {
		return new WitType.Prim("f64");
	}

	/**
	 * The {@code char} primitive.
	 * @return the type
	 */
	public static WitType charType() {
		return new WitType.Prim("char");
	}

	/**
	 * The {@code string} primitive.
	 * @return the type
	 */
	public static WitType string() {
		return new WitType.Prim("string");
	}

	/**
	 * A reference to a named type.
	 * @param name the type name
	 * @return the type
	 */
	public static WitType named(String name) {
		return new WitType.Named(name);
	}

	/**
	 * {@code list<T>}.
	 * @param element the element type
	 * @return the type
	 */
	public static WitType list(WitType element) {
		return new WitType.ListOf(element);
	}

	/**
	 * {@code option<T>}.
	 * @param element the payload type
	 * @return the type
	 */
	public static WitType option(WitType element) {
		return new WitType.OptionOf(element);
	}

	/**
	 * The bare {@code result} type (no payloads).
	 * @return the type
	 */
	public static WitType result() {
		return new WitType.ResultOf(null, null);
	}

	/**
	 * {@code result<T>}.
	 * @param ok the ok-arm payload
	 * @return the type
	 */
	public static WitType result(WitType ok) {
		return new WitType.ResultOf(ok, null);
	}

	/**
	 * {@code result<T, E>} (pass a {@code null} ok for {@code result<_, E>}).
	 * @param ok the ok-arm payload, or {@code null} for {@code _}
	 * @param err the error-arm payload
	 * @return the type
	 */
	public static WitType result(@Nullable WitType ok, WitType err) {
		return new WitType.ResultOf(ok, err);
	}

	/**
	 * {@code tuple<A, B, ...>}.
	 * @param elements the element types
	 * @return the type
	 */
	public static WitType tuple(WitType... elements) {
		return new WitType.TupleOf(List.of(elements));
	}

	/**
	 * The unparameterized {@code stream} type.
	 * @return the type
	 */
	public static WitType stream() {
		return new WitType.StreamOf(null);
	}

	/**
	 * {@code stream<T>}.
	 * @param element the element type
	 * @return the type
	 */
	public static WitType stream(WitType element) {
		return new WitType.StreamOf(element);
	}

	/**
	 * The unparameterized {@code future} type.
	 * @return the type
	 */
	public static WitType future() {
		return new WitType.FutureOf(null);
	}

	/**
	 * {@code future<T>}.
	 * @param element the payload type
	 * @return the type
	 */
	public static WitType future(WitType element) {
		return new WitType.FutureOf(element);
	}

	/**
	 * {@code borrow<R>}.
	 * @param resource the resource type name
	 * @return the type
	 */
	public static WitType borrow(String resource) {
		return new WitType.BorrowOf(resource);
	}

	/**
	 * {@code own<R>}.
	 * @param resource the resource type name
	 * @return the type
	 */
	public static WitType own(String resource) {
		return new WitType.OwnOf(resource);
	}

}
