package am.ik.rontolisp.java;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaEmitter.Context;
import am.ik.rontolisp.java.JavaEmitter.Invocation;
import am.ik.rontolisp.java.JavaEmitter.Typed;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.java.JavaType.TypeParameter;
import am.ik.rontolisp.java.JavaType.Variable;
import org.jspecify.annotations.Nullable;

import static am.ik.rontolisp.java.JavaForms.call;
import static am.ik.rontolisp.java.JavaForms.cl;
import static am.ik.rontolisp.java.JavaForms.integer;
import static am.ik.rontolisp.java.JavaForms.list;
import static am.ik.rontolisp.java.JavaForms.quote;
import static am.ik.rontolisp.java.JavaForms.runtimeCall;

/**
 * The classes of the library whose values are Lisp values, so that their members are
 * forms and not Java source: a {@code String} is the host's string, a boxed {@code int}
 * the number and a boxed {@code long} the number in a cons, a {@code StringBuilder} and
 * an {@code ArrayList} adjustable vectors, a {@code HashMap} an {@code equal} hash table.
 * Written here from the API specification's names and signatures; nothing of a JDK is
 * read ({@code .kb/java-frontend-provenance.md}). Each class has the members programs
 * reached so far, not its whole surface: a member missing here is refused by name.
 */
final class JavaIntrinsics {

	private static final String OBJECT = "java.lang.Object";

	private static final String STRING = "java.lang.String";

	/** The boxes, and what each holds. */
	private static final Map<String, Primitive> BOXES = Map.of("java.lang.Integer", Primitive.INT, "java.lang.Long",
			Primitive.LONG, "java.lang.Double", Primitive.DOUBLE, "java.lang.Boolean", Primitive.BOOLEAN,
			"java.lang.Character", Primitive.CHAR);

	/** What a map's key may be: a value {@code equal} compares as {@code equals} does. */
	private static final Set<String> KEYS = Set.of(STRING, "java.lang.Integer", "java.lang.Long", "java.lang.Character",
			"java.lang.Boolean");

	private JavaIntrinsics() {
	}

	/**
	 * The primitive a box class holds, or {@code null} for any other type; a type
	 * variable bounded by a box is the box.
	 */
	static @Nullable Primitive unboxed(JavaType type) {
		if (type instanceof Variable variable) {
			for (JavaType bound : variable.parameter().bounds) {
				Primitive held = unboxed(bound);
				if (held != null) {
					return held;
				}
			}
			return null;
		}
		return type instanceof ClassType boxed ? BOXES.get(boxed.declaration().name) : null;
	}

	/** The box class of a primitive type. */
	static ClassType boxed(Primitive primitive, JavaClasses classes) {
		for (Map.Entry<String, Primitive> box : BOXES.entrySet()) {
			if (box.getValue() == primitive) {
				return classes.libraryType(box.getKey());
			}
		}
		throw new IllegalStateException("no box of " + primitive);
	}

	/**
	 * The function testing that a value is an instance of an intrinsic class, or
	 * {@code null} where the subset has no test yet.
	 */
	static @Nullable LispSymbol predicate(JavaClass declaration) {
		return switch (declaration.name) {
			case STRING -> cl("simple-string-p");
			// a boxed int is the number, a boxed long the number in a cons
			case "java.lang.Integer" -> cl("integerp");
			case "java.lang.Long" -> cl("consp");
			case "java.lang.Double" -> cl("floatp");
			case "java.lang.Character" -> cl("characterp");
			case "java.lang.Boolean" -> JavaForms.runtime("boolean-p");
			case "java.lang.StringBuilder" -> JavaForms.runtime("sb-p");
			case "java.util.ArrayList", "java.util.List" -> JavaForms.runtime("list-p");
			case "java.util.HashMap", "java.util.Map" -> cl("hash-table-p");
			default -> null;
		};
	}

	/** The intrinsic classes, as the class table asks for them. */
	static final JavaClasses.Library LIBRARY = new JavaClasses.Library() {
		@Override
		public @Nullable JavaClass create(String name, JavaClasses classes) {
			return JavaIntrinsics.create(name, classes);
		}

		@Override
		public void check(ClassType type, Source source, int pos) {
			checkArguments(type, source, pos);
		}

		@Override
		public boolean isComparable(String name) {
			return name.equals(STRING) || (BOXES.containsKey(name) && !name.equals("java.lang.Boolean"));
		}
	};

	private static void checkArguments(ClassType type, Source source, int pos) {
		String name = type.declaration().name;
		if ((!name.equals("java.util.HashMap") && !name.equals("java.util.Map")) || type.arguments().isEmpty()) {
			return;
		}
		JavaType key = type.arguments().get(0);
		if (key instanceof JavaType.Wildcard wildcard) {
			key = wildcard.upper() != null ? wildcard.upper() : wildcard.lower();
		}
		if (key instanceof Variable variable && variable.parameter().open) {
			// what the map is used as says
			return;
		}
		// a key is found by equal: a key of a class of the program, or of a type the
		// map's
		// own code does not know, would be found by identity
		if (key instanceof ClassType named ? !KEYS.contains(named.declaration().name) : key != null) {
			throw JavaClasses.unsupported(source, pos, "a map keyed by " + key);
		}
	}

	private static final class Builder {

		private final JavaClass made;

		Builder(String name, boolean isInterface, JavaClasses classes, String... typeParameters) {
			this.made = new JavaClass(name, isInterface, isInterface, true, null, null);
			for (String parameter : typeParameters) {
				TypeParameter declared = new TypeParameter(parameter, false, false);
				declared.owner = this.made;
				this.made.typeParameters.add(declared);
			}
			classes.register(this.made);
		}

		/** A type parameter of the class as a member's signature names it. */
		Variable variable(int index) {
			return new Variable(this.made.typeParameters.get(index));
		}

		/** The interface of the library the class implements with its own parameters. */
		void implement(JavaClass implemented) {
			this.made.interfaces.add(implemented);
			if (!this.made.typeParameters.isEmpty()) {
				this.made.parameterized.put(implemented, new ClassType(implemented, this.made.self().arguments()));
			}
		}

		Builder method(String name, JavaType returnType, JavaEmitter emitter, JavaType... parameters) {
			this.made.methods.add(new JavaClass.Method(this.made, name, Arrays.asList(parameters), returnType, false,
					false, null, emitter, false));
			return this;
		}

		Builder statik(String name, JavaType returnType, JavaEmitter emitter, JavaType... parameters) {
			this.made.methods.add(new JavaClass.Method(this.made, name, Arrays.asList(parameters), returnType, true,
					false, null, emitter, false));
			return this;
		}

		/** A member of one argument of any type, which it takes as it is typed. */
		Builder any(String name, boolean isStatic, JavaType returnType, JavaType parameter, JavaEmitter emitter) {
			this.made.methods.add(new JavaClass.Method(this.made, name, List.of(parameter), returnType, isStatic, false,
					null, emitter, true));
			return this;
		}

		Builder constructor(JavaEmitter emitter, JavaType... parameters) {
			return method("<init>", Primitive.VOID, emitter, parameters);
		}

		Builder field(String name, JavaType type, LispVal form) {
			this.made.fields.add(new JavaClass.Field(this.made, name, type, true, true, null, form));
			return this;
		}

	}

	/**
	 * Makes the intrinsic class of a qualified name and adds it to the table.
	 * @param name the qualified name
	 * @param classes the table
	 * @return the class, or {@code null} when the name is no intrinsic class's
	 */
	private static @Nullable JavaClass create(String name, JavaClasses classes) {
		switch (name) {
			case OBJECT: {
				Builder builder = new Builder(name, false, classes);
				ClassType object = new ClassType(builder.made, List.of());
				builder
					.method("toString", classes.libraryType(STRING),
							(lowering, call) -> lowering.objectMethod("toString()",
									java.util.Objects.requireNonNull(call.receiver()), call))
					.method("equals", Primitive.BOOLEAN,
							(lowering, call) -> lowering.objectMethod("equals(Object)",
									java.util.Objects.requireNonNull(call.receiver()), call),
							object)
					.method("hashCode", Primitive.INT, (lowering, call) -> lowering.objectMethod("hashCode()",
							java.util.Objects.requireNonNull(call.receiver()), call));
				return builder.made;
			}
			case STRING:
				return string(new Builder(name, false, classes), classes);
			case "java.lang.StringBuilder":
				return stringBuilder(new Builder(name, false, classes), classes);
			case "java.util.ArrayList":
			case "java.util.List":
				return listClass(new Builder(name, name.endsWith(".List"), classes, "E"), classes);
			case "java.util.HashMap":
			case "java.util.Map":
				return map(new Builder(name, name.endsWith(".Map"), classes, "K", "V"), classes);
			case "java.lang.Math":
				return math(new Builder(name, false, classes));
			case "java.lang.Integer": {
				ClassType string = classes.libraryType(STRING);
				Builder builder = new Builder(name, false, classes);
				ClassType self = new ClassType(builder.made, List.of());
				return builder.field("MAX_VALUE", Primitive.INT, integer(Integer.MAX_VALUE))
					.field("MIN_VALUE", Primitive.INT, integer(Integer.MIN_VALUE))
					.statik("parseInt", Primitive.INT, (lowering, call) -> runtimeCall("parse-int", call.argument(0)),
							string)
					.statik("toString", string, (lowering, call) -> call("%decimal-piece", call.argument(0)),
							Primitive.INT)
					.statik("valueOf", self, (lowering, call) -> call.argument(0), Primitive.INT)
					.statik("compare", Primitive.INT,
							(lowering, call) -> runtimeCall("compare", call.argument(0), call.argument(1)),
							Primitive.INT, Primitive.INT)
					.method("compareTo", Primitive.INT,
							(lowering, call) -> runtimeCall("compare", call.self(), call.argument(0)), self)
					.method("intValue", Primitive.INT, (lowering, call) -> call.self()).made;
			}
			case "java.lang.Long": {
				Builder builder = new Builder(name, false, classes);
				ClassType self = new ClassType(builder.made, List.of());
				return builder.field("MAX_VALUE", Primitive.LONG, integer(Long.MAX_VALUE))
					.field("MIN_VALUE", Primitive.LONG, integer(Long.MIN_VALUE))
					.statik("hashCode", Primitive.INT, (lowering, call) -> runtimeCall("long-hash", call.argument(0)),
							Primitive.LONG)
					.statik("valueOf", self, (lowering, call) -> call("cons", call.argument(0), JavaForms.BOXED_LONG),
							Primitive.LONG)
					.statik("compare", Primitive.INT,
							(lowering, call) -> runtimeCall("compare", call.argument(0), call.argument(1)),
							Primitive.LONG, Primitive.LONG)
					.method("compareTo", Primitive.INT,
							(lowering, call) -> runtimeCall("compare", call("car", call.self()),
									call("car", call.argument(0))),
							self)
					.method("longValue", Primitive.LONG, (lowering, call) -> call("car", call.self())).made;
			}
			case "java.lang.Double": {
				Builder builder = new Builder(name, false, classes);
				ClassType self = new ClassType(builder.made, List.of());
				return builder
					.statik("compare", Primitive.INT,
							(lowering, call) -> runtimeCall("double-compare", call.argument(0), call.argument(1)),
							Primitive.DOUBLE, Primitive.DOUBLE)
					.statik("hashCode", Primitive.INT, (lowering, call) -> runtimeCall("hash-code", call.argument(0)),
							Primitive.DOUBLE)
					.statik("valueOf", self, (lowering, call) -> call.argument(0), Primitive.DOUBLE)
					.method("compareTo", Primitive.INT,
							(lowering, call) -> runtimeCall("double-compare", call.self(), call.argument(0)), self)
					.method("doubleValue", Primitive.DOUBLE, (lowering, call) -> call.self()).made;
			}
			case "java.lang.Boolean":
				return new Builder(name, false, classes).statik("hashCode", Primitive.INT,
						(lowering, call) -> call("if", call.argument(0), integer(1231), integer(1237)),
						Primitive.BOOLEAN).made;
			case "java.lang.Character": {
				Builder builder = new Builder(name, false, classes);
				ClassType self = new ClassType(builder.made, List.of());
				// a boxed char is a character, a char its code
				return builder
					.statik("compare", Primitive.INT, (lowering, call) -> call("-", call.argument(0), call.argument(1)),
							Primitive.CHAR, Primitive.CHAR)
					.method("compareTo", Primitive.INT,
							(lowering, call) -> call("-", call("char-code", call.self()),
									call("char-code", call.argument(0))),
							self)
					.method("charValue", Primitive.CHAR, (lowering, call) -> call("char-code", call.self())).made;
			}
			case "java.lang.System": {
				ClassType stream = classes.libraryType("java.io.PrintStream");
				return new Builder(name, false, classes).field("out", stream, cl("*standard-output*"))
					.field("err", stream, cl("*error-output*")).made;
			}
			case "java.io.PrintStream": {
				ClassType object = classes.libraryType(OBJECT);
				return new Builder(name, false, classes)
					.any("println", false, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-line", call.self()))
					.method("println", Primitive.VOID, (lowering, call) -> newline(call.self()))
					.any("print", false, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-string", call.self())).made;
			}
			case "java.lang.IO": {
				ClassType object = classes.libraryType(OBJECT);
				LispSymbol out = cl("*standard-output*");
				return new Builder(name, false, classes)
					.any("println", true, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-line", out))
					.statik("println", Primitive.VOID, (lowering, call) -> newline(out))
					.any("print", true, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-string", out)).made;
			}
			default:
				return null;
		}
	}

	private static LispVal newline(LispVal stream) {
		return stream.equals(cl("*standard-output*")) ? call("terpri") : call("terpri", stream);
	}

	private static LispVal print(Context lowering, Invocation call, String writer, LispVal stream) {
		LispVal text = text(lowering, call, 0);
		return stream.equals(cl("*standard-output*")) ? call(writer, text) : call(writer, text, stream);
	}

	/** An argument as the text {@code String.valueOf} makes of it. */
	private static LispVal text(Context lowering, Invocation call, int index) {
		Typed argument = call.arguments().get(index);
		if (argument.type().equals(new ArrayType(Primitive.CHAR))) {
			throw lowering.unsupported(call.pos(), "the text of a char[]");
		}
		return lowering.stringOf(argument, call.pos());
	}

	private static JavaClass string(Builder builder, JavaClasses classes) {
		ClassType string = new ClassType(builder.made, List.of());
		ClassType object = classes.libraryType(OBJECT);
		return builder.method("length", Primitive.INT, (lowering, call) -> call("length", call.self()))
			.method("charAt", Primitive.CHAR,
					(lowering, call) -> call("char-code", call("char", call.self(), call.argument(0))), Primitive.INT)
			.method("isEmpty", Primitive.BOOLEAN,
					(lowering, call) -> call("=", call("length", call.self()), integer(0)))
			.method("equals", Primitive.BOOLEAN, (lowering, call) -> {
				JavaType other = call.arguments().get(0).type();
				return other.equals(string) || other == JavaType.Null.NULL
						? call("equal", call.self(), call.argument(0))
						: runtimeCall("string-equals", call.self(), call.argument(0));
			}, object)
			.method("hashCode", Primitive.INT, (lowering, call) -> runtimeCall("string-hash", call.self()))
			.method("compareTo", Primitive.INT,
					(lowering, call) -> runtimeCall("string-compare", call.self(), call.argument(0)), string)
			.method("toString", string, (lowering, call) -> call.self())
			.method("substring", string,
					(lowering, call) -> runtimeCall("substring", call.self(), call.argument(0), JavaForms.NIL),
					Primitive.INT)
			.method("substring", string,
					(lowering, call) -> runtimeCall("substring", call.self(), call.argument(0), call.argument(1)),
					Primitive.INT, Primitive.INT)
			.method("indexOf", Primitive.INT,
					(lowering, call) -> call("or", call("search", call.argument(0), call.self()), integer(-1)), string)
			.method("toUpperCase", string, (lowering, call) -> call("%string-upcase-piece", call.self()))
			.method("toLowerCase", string, (lowering, call) -> call("%string-downcase-piece", call.self()))
			.any("valueOf", true, string, object, (lowering, call) -> text(lowering, call, 0)).made;
	}

	private static LispVal characters() {
		return list(cl("make-array"), integer(16), new LispSymbol(":ELEMENT-TYPE"), quote(cl("character")),
				new LispSymbol(":ADJUSTABLE"), JavaForms.T, new LispSymbol(":FILL-POINTER"), integer(0));
	}

	private static JavaClass stringBuilder(Builder builder, JavaClasses classes) {
		ClassType self = new ClassType(builder.made, List.of());
		ClassType string = classes.libraryType(STRING);
		return builder.constructor((lowering, call) -> characters())
			.constructor((lowering, call) -> runtimeCall("sb-append", characters(), call.argument(0)), string)
			.any("append", false, self, classes.libraryType(OBJECT),
					(lowering, call) -> call.arguments().get(0).type() == Primitive.CHAR
							? runtimeCall("sb-append-char", call.self(), call.argument(0))
							: runtimeCall("sb-append", call.self(), text(lowering, call, 0)))
			.method("toString", string, (lowering, call) -> runtimeCall("sb-string", call.self()))
			.method("length", Primitive.INT, (lowering, call) -> call("length", call.self()))
			.method("charAt", Primitive.CHAR, (lowering, call) -> runtimeCall("sb-char", call.self(), call.argument(0)),
					Primitive.INT).made;
	}

	private static JavaClass listClass(Builder builder, JavaClasses classes) {
		if (!builder.made.isInterface) {
			builder.constructor((lowering, call) -> list(cl("make-array"), integer(8), new LispSymbol(":ADJUSTABLE"),
					JavaForms.T, new LispSymbol(":FILL-POINTER"), integer(0)));
			builder.implement(classes.library("java.util.List"));
		}
		Variable element = builder.variable(0);
		return builder.method("add", Primitive.BOOLEAN, (lowering, call) -> {
			LispVal push = call("vector-push-extend", call.argument(0), call.self());
			return call.discard() ? push : call("progn", push, JavaForms.T);
		}, element)
			.method("get", element, (lowering, call) -> runtimeCall("list-get", call.self(), call.argument(0)),
					Primitive.INT)
			.method("set", element,
					(lowering, call) -> runtimeCall("list-set", call.self(), call.argument(0), call.argument(1)),
					Primitive.INT, element)
			.method("size", Primitive.INT, (lowering, call) -> call("length", call.self()))
			.method("isEmpty", Primitive.BOOLEAN,
					(lowering, call) -> call("=", call("length", call.self()), integer(0))).made;
	}

	private static JavaClass map(Builder builder, JavaClasses classes) {
		ClassType object = classes.libraryType(OBJECT);
		if (!builder.made.isInterface) {
			builder.constructor(
					(lowering, call) -> list(cl("make-hash-table"), new LispSymbol(":TEST"), quote(cl("equal"))));
			builder.implement(classes.library("java.util.Map"));
		}
		Variable key = builder.variable(0);
		Variable value = builder.variable(1);
		return builder
			.method("put", value,
					(lowering, call) -> call.discard()
							? call("setf", call("gethash", call.argument(0), call.self()), call.argument(1))
							: runtimeCall("map-put", call.self(), call.argument(0), call.argument(1)),
					key, value)
			.method("get", value, (lowering, call) -> call("gethash", call.argument(0), call.self()), object)
			.method("getOrDefault", value,
					(lowering, call) -> call("gethash", call.argument(0), call.self(), call.argument(1)), object, value)
			.method("containsKey", Primitive.BOOLEAN,
					(lowering, call) -> call("nth-value", integer(1), call("gethash", call.argument(0), call.self())),
					object)
			.method("size", Primitive.INT, (lowering, call) -> call("hash-table-count", call.self()))
			.method("isEmpty", Primitive.BOOLEAN,
					(lowering, call) -> call("=", call("hash-table-count", call.self()), integer(0))).made;
	}

	private static JavaClass math(Builder builder) {
		Primitive i = Primitive.INT;
		Primitive l = Primitive.LONG;
		Primitive d = Primitive.DOUBLE;
		builder.field("PI", d, new LispDouble(Math.PI))
			.field("E", d, new LispDouble(Math.E))
			.statik("sqrt", d, (lowering, call) -> runtimeCall("sqrt", call.argument(0)), d)
			.statik("floor", d, (lowering, call) -> call("ffloor", call.argument(0)), d)
			.statik("pow", d, (lowering, call) -> call("expt", call.argument(0), call.argument(1)), d, d)
			.statik("abs", i, (lowering, call) -> JavaForms.wrap(i, call("abs", call.argument(0))), i)
			.statik("abs", l, (lowering, call) -> JavaForms.wrap(l, call("abs", call.argument(0))), l)
			.statik("abs", d, (lowering, call) -> call("abs", call.argument(0)), d);
		for (String name : List.of("max", "min")) {
			for (Primitive type : List.of(i, l, d)) {
				builder.statik(name, type, (lowering, call) -> call(name, call.argument(0), call.argument(1)), type,
						type);
			}
		}
		return builder.made;
	}

}
