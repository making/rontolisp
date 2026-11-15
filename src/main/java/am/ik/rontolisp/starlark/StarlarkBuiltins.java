package am.ik.rontolisp.starlark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The built-in functions and methods the Starlark front end knows, each with the
 * parameters its run-time helper in {@code starlark.lisp} takes. One table serves the two
 * call tiers of {@link StarlarkLowering}: a call whose arguments fit a signature is bound
 * here, while lowering, and becomes a direct call of the helper with every parameter
 * filled; any other call, and a built-in used as a value, hands the same signature to the
 * run-time binder as a function value.
 *
 * <p>
 * A built-in is declared as Python writes one: {@code "sorted(iterable, /, *, key=None,
 * reverse=False)"}, a method behind the types that have it:
 * {@code "list|dict.pop(key=unset, default=unset, /)"}. Parameters before {@code /} are
 * filled by position only, those after {@code *} (or {@code *args}) by name only, and
 * {@code **kwargs} takes the surplus named arguments. A default is {@code None},
 * {@code True}, {@code False}, an integer, a quoted string, or {@code unset} for an
 * optional parameter with no value of its own: the helper is handed the marker and
 * decides. {@code -> int} or {@code -> bool} after the parameters says what every call
 * that returns answers, which the lowering's typed fast paths read.
 */
final class StarlarkBuiltins {

	/** None. */
	static final LispSymbol NONE = new LispSymbol(":NONE");

	/** What a helper is handed for an optional parameter the call did not fill. */
	static final LispSymbol UNSET = new LispSymbol(":%UNSET");

	/** What a function value's defaults hold for a parameter that needs an argument. */
	static final LispSymbol REQUIRED = new LispSymbol(":%REQUIRED");

	private static final Map<String, Signature> FUNCTIONS = table("abs(x, /)", "all(x, /) -> bool", "any(x, /) -> bool",
			"bool(x=False, /) -> bool", "dict(pairs=unset, /, **kwargs)", "dir(x, /)",
			"enumerate(iterable, /, start=0)", "fail(*args, sep=' ')", "float(x=0, /)",
			"getattr(x, name, default=unset, /)", "hasattr(x, name, /) -> bool", "hash(x, /) -> int",
			"int(x, /, base=unset) -> int", "len(x, /) -> int", "list(x=unset, /)", "max(*args, key=None)",
			"min(*args, key=None)", "print(*args, sep=' ')", "range(start_or_stop, stop=unset, step=1, /)",
			"repr(x, /)", "reversed(sequence, /)", "sorted(iterable, /, *, key=None, reverse=False)", "str(x, /)",
			"tuple(x=unset, /)", "type(x, /)", "zip(*args)");

	// A name two types share is one helper that tells the receivers apart: its
	// parameters are what either takes.
	private static final Map<String, Signature> METHODS = table("list.append(x, /)", "list|dict.clear()",
			"list.extend(x, /)", "list|string.index(x, start=None, end=None, /) -> int", "list.insert(index, x, /)",
			"list|dict.pop(key=unset, default=unset, /)", "list.remove(x, /)", "dict.get(key, default=None, /)",
			"dict.items()", "dict.keys()", "dict.popitem()", "dict.setdefault(key, default=None, /)",
			"dict.update(pairs=unset, /, **kwargs)", "dict.values()", "string.capitalize()", "string.codepoint_ords()",
			"string.codepoints()", "string.count(sub, start=None, end=None, /) -> int", "string.elem_ords()",
			"string.elems()", "string.endswith(suffix, start=None, end=None, /) -> bool",
			"string.find(sub, start=None, end=None, /) -> int", "string.format(*args, **kwargs)",
			"string.isalnum() -> bool", "string.isalpha() -> bool", "string.isdigit() -> bool",
			"string.islower() -> bool", "string.isspace() -> bool", "string.istitle() -> bool",
			"string.isupper() -> bool", "string.join(iterable, /)", "string.lower()", "string.lstrip(cutset=None, /)",
			"string.partition(sep, /)", "string.removeprefix(prefix, /)", "string.removesuffix(suffix, /)",
			"string.replace(old, new, count=unset, /)", "string.rfind(sub, start=None, end=None, /) -> int",
			"string.rindex(sub, start=None, end=None, /) -> int", "string.rpartition(sep, /)",
			"string.rsplit(sep=None, maxsplit=None, /)", "string.rstrip(cutset=None, /)",
			"string.split(sep=None, maxsplit=None, /)", "string.splitlines(keepends=False, /)",
			"string.startswith(prefix, start=None, end=None, /) -> bool", "string.strip(cutset=None, /)",
			"string.title()", "string.upper()");

	private StarlarkBuiltins() {
	}

	/**
	 * How a built-in is called.
	 *
	 * @param name the name a program spells
	 * @param helper the run-time helper's symbol name
	 * @param kinds the types whose values have the method; empty for a function
	 * @param params the parameters the helper takes, in order (after the receiver, for a
	 * method), before the tuple of surplus positional arguments and the dict of surplus
	 * named ones
	 * @param defaults parallel to {@code params}: the constant an unfilled parameter
	 * takes, or {@code null} when the call must fill it
	 * @param positional how many of the leading parameters a positional argument fills
	 * @param namedFrom the index of the first parameter a named argument fills
	 * @param varargs whether surplus positional arguments are taken, as a tuple
	 * @param kwargs whether surplus named arguments are taken, as a dict
	 * @param result {@code "int"} or {@code "bool"} when every call that returns answers
	 * one, else empty
	 */
	record Signature(String name, String helper, List<String> kinds, List<String> params,
			List<@Nullable LispVal> defaults, int positional, int namedFrom, boolean varargs, boolean kwargs,
			String result) {

		/**
		 * Reads a declaration: {@code "name(parameters)"} for a function,
		 * {@code "type|type.name(parameters)"} for a method, {@code " -> result"} after
		 * either.
		 * @param declaration the declaration
		 * @return the signature
		 */
		static Signature parse(String declaration) {
			int open = declaration.indexOf('(');
			int dot = declaration.lastIndexOf('.', open);
			String name = declaration.substring(dot + 1, open);
			Builder builder = new Builder().name(name)
				.helper("RONTOLISP::%STARLARK-" + (dot < 0 ? "B-" : "M-") + name.toUpperCase(Locale.ROOT));
			if (dot >= 0) {
				builder.kinds(List.of(declaration.substring(0, dot).split("\\|")));
			}
			int close = declaration.lastIndexOf(')');
			builder.result(declaration.substring(close + 1).replace(" -> ", ""));
			String parameters = declaration.substring(open + 1, close);
			for (String part : parameters.isEmpty() ? new String[0] : parameters.split(",")) {
				String parameter = part.strip();
				if (parameter.equals("/")) {
					builder.positionalOnly();
				}
				else if (parameter.startsWith("**")) {
					builder.kwargs();
				}
				else if (parameter.startsWith("*")) {
					builder.star(parameter.length() > 1);
				}
				else {
					int equals = parameter.indexOf('=');
					builder.parameter(equals < 0 ? parameter : parameter.substring(0, equals),
							equals < 0 ? null : constant(parameter.substring(equals + 1)));
				}
			}
			return builder.build();
		}

		private static LispVal constant(String spelling) {
			return switch (spelling) {
				case "None" -> NONE;
				case "True" -> LispTrue.INSTANCE;
				case "False" -> LispNil.INSTANCE;
				case "unset" -> UNSET;
				default -> spelling.startsWith("'") ? LispString.literal(spelling.substring(1, spelling.length() - 1))
						: new LispInteger(Long.parseLong(spelling));
			};
		}

		boolean isMethod() {
			return !this.kinds.isEmpty();
		}

		/**
		 * The index of the parameter a named argument fills.
		 * @param keyword the argument's name
		 * @return the index, or -1 when no parameter takes it by name
		 */
		int named(String keyword) {
			int index = this.params.indexOf(keyword);
			return index >= this.namedFrom ? index : -1;
		}

		/**
		 * A method as its helper is called: the receiver is its first parameter, filled
		 * by position only.
		 * @return the signature over the receiver and the method's parameters
		 */
		Signature withReceiver() {
			List<String> params = new ArrayList<>(List.of("self"));
			params.addAll(this.params);
			List<@Nullable LispVal> defaults = new ArrayList<>();
			defaults.add(null);
			defaults.addAll(this.defaults);
			return new Signature(this.name, this.helper, this.kinds, List.copyOf(params),
					Collections.unmodifiableList(defaults), this.positional + 1, this.namedFrom + 1, this.varargs,
					this.kwargs, this.result);
		}

		/** Builds a signature parameter by parameter, as a declaration lists them. */
		static final class Builder {

			private @Nullable String name;

			private @Nullable String helper;

			private List<String> kinds = List.of();

			private final List<String> params = new ArrayList<>();

			private final List<@Nullable LispVal> defaults = new ArrayList<>();

			private int positional = -1;

			private int namedFrom;

			private boolean varargs;

			private boolean kwargs;

			private String result = "";

			Builder name(String name) {
				this.name = name;
				return this;
			}

			Builder helper(String helper) {
				this.helper = helper;
				return this;
			}

			Builder kinds(List<String> kinds) {
				this.kinds = List.copyOf(kinds);
				return this;
			}

			/** The next parameter, with the constant it takes when unfilled. */
			Builder parameter(String name, @Nullable LispVal defaultValue) {
				this.params.add(name);
				this.defaults.add(defaultValue);
				return this;
			}

			/** {@code /}: the parameters so far are filled by position only. */
			Builder positionalOnly() {
				this.namedFrom = this.params.size();
				return this;
			}

			/** {@code *} or {@code *args}: the parameters after it are filled by name. */
			Builder star(boolean varargs) {
				this.positional = this.params.size();
				this.varargs = varargs;
				return this;
			}

			/** {@code **kwargs}. */
			Builder kwargs() {
				this.kwargs = true;
				return this;
			}

			/** What every call that returns answers: {@code "int"}, {@code "bool"}. */
			Builder result(String result) {
				this.result = result;
				return this;
			}

			Signature build() {
				return new Signature(Objects.requireNonNull(this.name, "name is required"),
						Objects.requireNonNull(this.helper, "helper is required"), this.kinds, List.copyOf(this.params),
						Collections.unmodifiableList(new ArrayList<>(this.defaults)),
						this.positional < 0 ? this.params.size() : this.positional, this.namedFrom, this.varargs,
						this.kwargs, this.result);
			}

		}

	}

	/**
	 * The built-in function of a name.
	 * @param name the name as written
	 * @return its signature, or {@code null} when there is none
	 */
	static @Nullable Signature function(String name) {
		return FUNCTIONS.get(name);
	}

	/**
	 * The method of a name, whatever type has it.
	 * @param name the name as written after the dot
	 * @return its signature, or {@code null} when no type has such a method
	 */
	static @Nullable Signature method(String name) {
		return METHODS.get(name);
	}

	/**
	 * Every method, in the order the table declares them.
	 * @return the signatures
	 */
	static List<Signature> methods() {
		return List.copyOf(METHODS.values());
	}

	/**
	 * Every built-in, the functions first.
	 * @return the signatures
	 */
	static List<Signature> signatures() {
		List<Signature> signatures = new ArrayList<>(FUNCTIONS.values());
		signatures.addAll(METHODS.values());
		return signatures;
	}

	private static Map<String, Signature> table(String... declarations) {
		Map<String, Signature> table = new LinkedHashMap<>();
		for (String declaration : declarations) {
			Signature signature = Signature.parse(declaration);
			table.put(signature.name(), signature);
		}
		return Collections.unmodifiableMap(table);
	}

}
