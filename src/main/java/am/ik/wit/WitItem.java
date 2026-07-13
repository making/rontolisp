package am.ik.wit;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One syntactic item of a WIT document: a top-level declaration, a world member or an
 * interface member. Which variants are legal where is enforced by {@link WitParser} (and
 * by {@code wasm-tools}); the model deliberately keeps a single family so containers can
 * hold a uniform {@code List<WitItem>}.
 */
public sealed interface WitItem {

	/**
	 * The doc comment and gate attributes attached to this item.
	 * @return the meta (never {@code null}; possibly {@link WitMeta#none()})
	 */
	WitMeta meta();

	/**
	 * A file-level package declaration, {@code package wasi:cli@0.3.0;}.
	 *
	 * @param meta docs and gates
	 * @param name the package name
	 */
	record PackageHeader(WitMeta meta, WitPackageName name) implements WitItem {
	}

	/**
	 * An explicit (nested) package definition, {@code package wasi:cli@0.3.0 '{' ... '}'}
	 * — the form {@code wasm-tools component wit} prints for every referenced package
	 * after the root world.
	 *
	 * @param meta docs and gates
	 * @param name the package name
	 * @param items the contained worlds and interfaces
	 */
	record PackageBlock(WitMeta meta, WitPackageName name, List<WitItem> items) implements WitItem {
	}

	/**
	 * A {@code world} definition.
	 *
	 * @param meta docs and gates
	 * @param name the world name
	 * @param items the world members ({@link ImportRef}, {@link ImportNamed},
	 * {@link ExportRef}, {@link ExportNamed}, {@link Include}, {@link Use} and type
	 * definitions)
	 */
	record World(WitMeta meta, String name, List<WitItem> items) implements WitItem {
	}

	/**
	 * An {@code interface} definition.
	 *
	 * @param meta docs and gates
	 * @param name the interface name
	 * @param items the interface members ({@link Use}, type definitions and
	 * {@link FuncDef}s)
	 */
	record InterfaceDef(WitMeta meta, String name, List<WitItem> items) implements WitItem {
	}

	/**
	 * A {@code use} clause, {@code use types.{error-code};} or {@code use
	 * wasi:io/error@0.2.0.{error as io-error};}.
	 *
	 * @param meta docs and gates
	 * @param path the source interface
	 * @param names the imported names, each optionally renamed
	 */
	record Use(WitMeta meta, WitRef path, List<UseName> names) implements WitItem {
	}

	/**
	 * One name inside a {@code use} clause's braces.
	 *
	 * @param name the name in the source interface
	 * @param alias the local rename after {@code as}, or {@code null} when not renamed
	 */
	record UseName(String name, @Nullable String alias) {
	}

	/**
	 * A {@code type} alias, {@code type filesize = u64;}.
	 *
	 * @param meta docs and gates
	 * @param name the alias name
	 * @param target the aliased type
	 */
	record TypeAlias(WitMeta meta, String name, WitType target) implements WitItem {
	}

	/**
	 * A {@code record} definition.
	 *
	 * @param meta docs and gates
	 * @param name the record name
	 * @param fields the fields in order
	 */
	record RecordDef(WitMeta meta, String name, List<Field> fields) implements WitItem {
	}

	/**
	 * One {@code record} field.
	 *
	 * @param meta docs and gates
	 * @param name the field name (a {@code %}-escape is kept verbatim)
	 * @param type the field type
	 */
	record Field(WitMeta meta, String name, WitType type) {
	}

	/**
	 * A {@code variant} definition.
	 *
	 * @param meta docs and gates
	 * @param name the variant name
	 * @param cases the cases in order
	 */
	record VariantDef(WitMeta meta, String name, List<Case> cases) implements WitItem {
	}

	/**
	 * An {@code enum} definition.
	 *
	 * @param meta docs and gates
	 * @param name the enum name
	 * @param cases the cases in order (payloads are always {@code null})
	 */
	record EnumDef(WitMeta meta, String name, List<Case> cases) implements WitItem {
	}

	/**
	 * A {@code flags} definition.
	 *
	 * @param meta docs and gates
	 * @param name the flags name
	 * @param cases the flag names in order (payloads are always {@code null})
	 */
	record FlagsDef(WitMeta meta, String name, List<Case> cases) implements WitItem {
	}

	/**
	 * One case of a {@code variant} (payload optional), {@code enum} or {@code flags}
	 * (payload always {@code null}).
	 *
	 * @param meta docs and gates
	 * @param name the case name
	 * @param payload the variant case payload type, or {@code null} when the case carries
	 * none
	 */
	record Case(WitMeta meta, String name, @Nullable WitType payload) {
	}

	/**
	 * A {@code resource} definition, with or without a body.
	 *
	 * @param meta docs and gates
	 * @param name the resource name
	 * @param body the constructor / methods / static functions, or {@code null} for the
	 * bodiless {@code resource error;} form
	 */
	record ResourceDef(WitMeta meta, String name, @Nullable List<WitItem> body) implements WitItem {
	}

	/**
	 * A named function: an interface member, a resource member or the payload of an
	 * inline {@code import}/{@code export}.
	 *
	 * @param meta docs and gates
	 * @param name the function name ({@code constructor} for
	 * {@link FuncKind#CONSTRUCTOR})
	 * @param kind plain, {@code static} or {@code constructor}
	 * @param func the function type
	 */
	record FuncDef(WitMeta meta, String name, FuncKind kind, WitFunc func) implements WitItem {
	}

	/**
	 * How a {@link FuncDef} is declared.
	 */
	enum FuncKind {

		/** A plain (freestanding or method) function, {@code name: func(...)}. */
		PLAIN,

		/** A resource static function, {@code name: static func(...)}. */
		STATIC,

		/** A resource constructor, {@code constructor(...)}. */
		CONSTRUCTOR

	}

	/**
	 * A world import of an interface by reference, {@code import wasi:cli/types@0.3.0;}
	 * or {@code import monotonic-clock;}.
	 *
	 * @param meta docs and gates
	 * @param target the imported interface
	 */
	record ImportRef(WitMeta meta, WitRef target) implements WitItem {
	}

	/**
	 * A world export of an interface by reference, {@code export wasi:cli/run@0.3.0;}.
	 *
	 * @param meta docs and gates
	 * @param target the exported interface
	 */
	record ExportRef(WitMeta meta, WitRef target) implements WitItem {
	}

	/**
	 * A world import of a named extern, {@code import name: func(...);} or {@code import
	 * name: interface '{' ... '}'}.
	 *
	 * @param meta docs and gates
	 * @param name the import name
	 * @param extern the imported shape
	 */
	record ImportNamed(WitMeta meta, String name, Extern extern) implements WitItem {
	}

	/**
	 * A world export of a named extern, {@code export run: async func();}.
	 *
	 * @param meta docs and gates
	 * @param name the export name
	 * @param extern the exported shape
	 */
	record ExportNamed(WitMeta meta, String name, Extern extern) implements WitItem {
	}

	/**
	 * The shape of an inline world {@code import}/{@code export}.
	 */
	sealed interface Extern {

		/**
		 * A function extern.
		 *
		 * @param func the function type
		 */
		record ExternFunc(WitFunc func) implements Extern {
		}

		/**
		 * An inline interface extern.
		 *
		 * @param items the interface members
		 */
		record ExternInterface(List<WitItem> items) implements Extern {
		}

	}

	/**
	 * A world {@code include}, {@code include wasi:clocks/imports@0.3.0;} or
	 * {@code include imports;}.
	 *
	 * @param meta docs and gates
	 * @param target the included world
	 */
	record Include(WitMeta meta, WitRef target) implements WitItem {
	}

}
