package am.ik.rontolisp.compiler;

import am.ik.wit.WitItem;
import am.ik.wit.WitType;

/**
 * The settled WIT type &lt;-&gt; rontolisp value mapping — the single vocabulary every
 * backend binder ({@code wit-import} / {@code wit-export}, todos 126-128) consults, in
 * the same way {@link WasmImportDirective} is the shared import directive. This class
 * contains <strong>no codegen</strong>; it names the house representation of each WIT
 * type. Full rationale (and the {@code result} decision record): {@code .kb/wit.md}.
 *
 * <p>
 * The two decisions pinned here (reversing either later would break user programs):
 *
 * <ul>
 * <li><strong>{@code result<T, E>}</strong> — option (c) of todo 124: the ok arm is the
 * function's return value ({@code nil} when the ok arm is {@code _} or absent); the error
 * arm <strong>signals a condition on every backend</strong>, catchable with
 * {@code handler-case}. On the WASM backends, where {@code handler-case} is still a
 * compile-time error (traps are uncatchable; {@code .kb/error-handling.md}), signaling
 * means trapping with the message <em>as a temporary limitation, not a contract</em> — a
 * WASM catch mechanism is a prerequisite of todo 128's result-returning imports, and user
 * code written against this mapping starts compiling there unchanged the day it
 * lands.</li>
 * <li><strong>{@code list<u8>}</strong> — a rontolisp string (byte-per-char, the existing
 * fetch/socket byte-marshalling convention), NOT a list of integers; distinct from WIT
 * {@code string}, which crosses as canonical-ABI UTF-8.</li>
 * </ul>
 */
public final class WitTypeMapper {

	private WitTypeMapper() {
	}

	/**
	 * The rontolisp-side representation of a WIT type.
	 */
	public enum Rep {

		/** {@code s8}/{@code s16}/{@code s32}/{@code u8}/{@code u16}/{@code u32}: int. */
		INT,

		/**
		 * {@code s64}/{@code u64}: int, bignum-safe (beyond {@code i31} the interpreter
		 * and JVM stay exact; the wasm-GC backend follows its existing wide-int
		 * behavior).
		 */
		BIGNUM_INT,

		/** {@code f32}/{@code f64}: float. */
		FLOAT,

		/** {@code bool}: {@code t} / {@code nil}. */
		BOOLEAN,

		/** {@code string}: string (canonical-ABI UTF-8 at a component boundary). */
		STRING,

		/** {@code char}: character. */
		CHARACTER,

		/**
		 * {@code list<u8>}: a string carrying raw bytes one-per-char (the fetch/socket
		 * marshalling convention), NOT a list of ints.
		 */
		BYTE_STRING,

		/** {@code list<T>} (T not {@code u8}): proper list. */
		LIST,

		/** {@code tuple<A, B, ...>}: proper list, positional. */
		TUPLE_LIST,

		/** {@code option<T>}: the value, or {@code nil} when absent. */
		NIL_OR_VALUE,

		/**
		 * {@code result} / {@code result<T>} / {@code result<_, E>} /
		 * {@code result<T, E>}: the ok payload is the return value ({@code nil} for a
		 * payload-less ok arm); the error arm signals a condition carrying the mapped
		 * {@code E} payload on EVERY backend (todo 124 option (c) — see the class doc for
		 * the WASM interim behavior).
		 */
		RESULT,

		/**
		 * {@code borrow<R>} / {@code own<R>} (and a bare {@code resource} reference): an
		 * opaque integer handle, in the same one-handle-space convention as file/socket
		 * streams ({@code .kb/read-load-streams.md}).
		 */
		HANDLE,

		/**
		 * A {@code record} definition: keyword plist ({@code rontolisp:http-handler}
		 * precedent).
		 */
		PLIST,

		/** An {@code enum} definition: keyword. */
		KEYWORD,

		/** A {@code variant} definition: {@code (tag . payload)} tagged list. */
		TAGGED_LIST,

		/** A {@code flags} definition: list of keywords. */
		KEYWORD_LIST,

		/**
		 * {@code stream}/{@code future}: no rontolisp value yet — a binder must reject
		 * them until language-level async lands (the WASI 0.3 async plan); todo 124.
		 */
		UNSUPPORTED

	}

	/**
	 * Maps a structural WIT type use to its rontolisp representation. A
	 * {@link WitType.Named} reference cannot be classified structurally — resolve it to
	 * its definition and use {@link #repOfDefinition(WitItem)}.
	 * @param type the WIT type use
	 * @return the representation
	 * @throws IllegalArgumentException for a {@link WitType.Named} reference (the caller
	 * must resolve it first)
	 */
	public static Rep rep(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> switch (prim.name()) {
				case "s8", "s16", "s32", "u8", "u16", "u32" -> Rep.INT;
				case "s64", "u64" -> Rep.BIGNUM_INT;
				case "f32", "f64" -> Rep.FLOAT;
				case "bool" -> Rep.BOOLEAN;
				case "string" -> Rep.STRING;
				case "char" -> Rep.CHARACTER;
				default -> throw new IllegalArgumentException("Unknown WIT primitive: " + prim.name());
			};
			case WitType.ListOf list ->
				(list.element() instanceof WitType.Prim prim && "u8".equals(prim.name())) ? Rep.BYTE_STRING : Rep.LIST;
			case WitType.TupleOf ignored -> Rep.TUPLE_LIST;
			case WitType.OptionOf ignored -> Rep.NIL_OR_VALUE;
			case WitType.ResultOf ignored -> Rep.RESULT;
			case WitType.BorrowOf ignored -> Rep.HANDLE;
			case WitType.OwnOf ignored -> Rep.HANDLE;
			case WitType.StreamOf ignored -> Rep.UNSUPPORTED;
			case WitType.FutureOf ignored -> Rep.UNSUPPORTED;
			case WitType.Named named -> throw new IllegalArgumentException(
					"Named type '" + named.name() + "' must be resolved to its definition first");
		};
	}

	/**
	 * Maps a named WIT type definition to the representation of its instances.
	 * @param definition the type definition ({@code record} / {@code variant} /
	 * {@code enum} / {@code flags} / {@code resource} / {@code type} alias)
	 * @return the representation ({@code type} aliases classify by their target)
	 * @throws IllegalArgumentException when the item is not a type definition
	 */
	public static Rep repOfDefinition(WitItem definition) {
		return switch (definition) {
			case WitItem.RecordDef ignored -> Rep.PLIST;
			case WitItem.EnumDef ignored -> Rep.KEYWORD;
			case WitItem.VariantDef ignored -> Rep.TAGGED_LIST;
			case WitItem.FlagsDef ignored -> Rep.KEYWORD_LIST;
			case WitItem.ResourceDef ignored -> Rep.HANDLE;
			case WitItem.TypeAlias alias -> rep(alias.target());
			default -> throw new IllegalArgumentException("Not a WIT type definition: " + definition);
		};
	}

}
