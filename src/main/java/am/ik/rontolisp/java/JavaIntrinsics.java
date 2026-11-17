package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaEmitter.Context;
import am.ik.rontolisp.java.JavaEmitter.Invocation;
import am.ik.rontolisp.java.JavaEmitter.Typed;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
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
 * the number and a boxed {@code long} the number in a cons, a {@code StringBuilder} an
 * adjustable character vector; and the classes that hold no value at all ({@code Math},
 * {@code System}). Every other class of the library is Java source in the jar, and a
 * member of one that Java cannot say is declared {@code native} there and is a form here
 * ({@link #NATIVES}). Written here from the API specification's names and signatures;
 * nothing of a JDK is read ({@code .kb/java-frontend-provenance.md}). Each class has the
 * members programs reach, not its whole surface: a member missing here is refused by
 * name.
 */
final class JavaIntrinsics {

	private static final String OBJECT = "java.lang.Object";

	private static final String STRING = "java.lang.String";

	private static final String NUMBER = "java.lang.Number";

	private static final String CHARS = "java.lang.CharSequence";

	private static final String COMPARABLE = "java.lang.Comparable";

	/** The source class holding the members of these classes that are written in Java. */
	private static final String SUPPORT = "rontolisp.internal.Support";

	/** The boxes, and what each holds. */
	private static final Map<String, Primitive> BOXES = Map.of("java.lang.Integer", Primitive.INT, "java.lang.Long",
			Primitive.LONG, "java.lang.Double", Primitive.DOUBLE, "java.lang.Boolean", Primitive.BOOLEAN,
			"java.lang.Character", Primitive.CHAR, "java.lang.Byte", Primitive.BYTE, "java.lang.Short", Primitive.SHORT,
			"java.lang.Float", Primitive.FLOAT);

	private static final LispSymbol OUT = cl("*standard-output*");

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

	/** Whether a class's values are told from every other's by {@link #predicate}. */
	static boolean isPlain(JavaClass declaration) {
		return switch (declaration.name) {
			case "java.lang.StringBuilder", "java.lang.StringBuffer", NUMBER -> true;
			default -> BOXES.containsKey(declaration.name);
		};
	}

	/**
	 * The function testing that a value is an instance of an intrinsic class, or
	 * {@code null} for a class holding no value.
	 */
	static @Nullable LispSymbol predicate(JavaClass declaration) {
		return switch (declaration.name) {
			case STRING -> cl("simple-string-p");
			// a boxed int is the number, a boxed long the number in a cons with its tag
			case "java.lang.Integer" -> cl("integerp");
			case "java.lang.Long" -> JavaForms.runtime("long-p");
			case "java.lang.Byte" -> JavaForms.runtime("byte-p");
			case "java.lang.Short" -> JavaForms.runtime("short-p");
			case "java.lang.Float" -> JavaForms.runtime("float-p");
			case "java.lang.Double" -> cl("floatp");
			case "java.lang.Character" -> cl("characterp");
			case "java.lang.Boolean" -> JavaForms.runtime("boolean-p");
			case NUMBER -> JavaForms.runtime("number-p");
			case "java.lang.StringBuilder", "java.lang.StringBuffer" -> JavaForms.runtime("sb-p");
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
		public List<String> implemented(String name) {
			return switch (name) {
				case STRING -> List.of(COMPARABLE, CHARS);
				case "java.lang.StringBuilder", "java.lang.StringBuffer" -> List.of(CHARS);
				default -> BOXES.containsKey(name) ? List.of(COMPARABLE) : List.of();
			};
		}

		@Override
		public @Nullable JavaEmitter emitter(String member) {
			return NATIVES.get(member);
		}

		@Override
		public void meets(JavaClass intrinsic, JavaClass named, JavaClasses classes) {
			JavaIntrinsics.meets(intrinsic, named, classes);
		}
	};

	/**
	 * Adds to an intrinsic class the members whose types name an interface of the
	 * library's Java source, once a program names that interface: named from the start,
	 * every program would hold the interface.
	 */
	private static void meets(JavaClass intrinsic, JavaClass named, JavaClasses classes) {
		boolean text = intrinsic.name.equals(STRING);
		boolean builder = intrinsic.name.equals("java.lang.StringBuilder")
				|| intrinsic.name.equals("java.lang.StringBuffer");
		if (named.name.equals(CHARS) && (text || builder)) {
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "subSequence", List.of(Primitive.INT, Primitive.INT),
					new ClassType(named, List.of()), false, false, null, runtime(text ? "substring" : "sb-substring"),
					false));
		}
		if (named.name.equals("java.lang.Iterable") && text) {
			intrinsic.methods.add(new JavaClass.Method(intrinsic, "join",
					List.of(classes.libraryType(OBJECT), new ClassType(named, List.of())),
					new ClassType(intrinsic, List.of()), true, false, null, (lowering, call) -> lowering
						.library(SUPPORT, "join", List.of(text(lowering, call, 0), call.argument(1))),
					false));
		}
	}

	// ---- emitters

	/** A member that is a call of a run-time function: its receiver, its arguments. */
	private static JavaEmitter runtime(String name) {
		return (lowering, call) -> JavaForms.form(JavaForms.runtime(name), operands(call));
	}

	/** A member that is a call of a host operator: its receiver, its arguments. */
	private static JavaEmitter host(String name) {
		return (lowering, call) -> JavaForms.form(cl(name), operands(call));
	}

	/** A member written in Java, in the library's own source. */
	private static JavaEmitter support(String name) {
		return (lowering, call) -> lowering.library(SUPPORT, name, operands(call));
	}

	/** A member that is one of fdlibm's functions over doubles. */
	private static JavaEmitter strict(String name) {
		return (lowering, call) -> JavaForms.form(cl("%strict-math"),
				prepend(new LispSymbol(":" + name.toUpperCase(java.util.Locale.ROOT)), operands(call)));
	}

	private static List<LispVal> operands(Invocation call) {
		List<LispVal> operands = new ArrayList<>();
		if (call.receiver() != null) {
			operands.add(call.self());
		}
		for (Typed argument : call.arguments()) {
			operands.add(argument.form());
		}
		return operands;
	}

	private static List<LispVal> prepend(LispVal first, List<LispVal> rest) {
		List<LispVal> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	private static final class Builder {

		private final JavaClass made;

		Builder(String name, boolean isAbstract, JavaClasses classes) {
			this.made = new JavaClass(name, false, isAbstract, true, null, null);
			classes.register(this.made);
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

		/** The member added last takes the arguments past its others as an array. */
		Builder varargs() {
			this.made.methods.get(this.made.methods.size() - 1).varargs = true;
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
				// an Object that is no other class's is a vector nothing else makes
				builder
					.constructor((lowering, call) -> list(cl("make-array"), integer(0), new LispSymbol(":FILL-POINTER"),
							integer(0)))
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
			case "java.lang.StringBuffer":
				return stringBuilder(new Builder(name, false, classes), classes);
			case "java.lang.Math":
				return math(new Builder(name, false, classes));
			case NUMBER:
				return new Builder(name, true, classes).method("intValue", Primitive.INT, runtime("number-int"))
					.method("longValue", Primitive.LONG, runtime("number-long"))
					.method("floatValue", Primitive.FLOAT,
							(lowering, call) -> call("%single-float", runtimeCall("number-double", call.self())))
					.method("doubleValue", Primitive.DOUBLE, runtime("number-double"))
					.method("byteValue", Primitive.BYTE,
							(lowering, call) -> JavaForms.signed(runtimeCall("number-int", call.self()), 8))
					.method("shortValue", Primitive.SHORT,
							(lowering, call) -> JavaForms.signed(runtimeCall("number-int", call.self()), 16)).made;
			case "java.lang.Integer":
				return integerClass(new Builder(name, false, classes), classes);
			case "java.lang.Long":
				return longClass(new Builder(name, false, classes), classes);
			case "java.lang.Double":
				return doubleClass(new Builder(name, false, classes), classes);
			case "java.lang.Float":
				return floatClass(new Builder(name, false, classes), classes);
			case "java.lang.Byte":
				return smallClass(new Builder(name, false, classes), classes, Primitive.BYTE);
			case "java.lang.Short":
				return smallClass(new Builder(name, false, classes), classes, Primitive.SHORT);
			case "java.lang.Boolean":
				return booleanClass(new Builder(name, false, classes), classes);
			case "java.lang.Character":
				return character(new Builder(name, false, classes), classes);
			case "java.lang.System": {
				ClassType stream = classes.libraryType("java.io.PrintStream");
				ClassType object = classes.libraryType(OBJECT);
				return new Builder(name, false, classes).field("out", stream, OUT)
					.field("err", stream, cl("*error-output*"))
					.statik("currentTimeMillis", Primitive.LONG,
							(lowering, call) -> call("values",
									call("floor", call("%unix-nano-time"), integer(1000000))))
					.statik("nanoTime", Primitive.LONG, host("%nano-time"))
					.statik("exit", Primitive.VOID, runtime("exit"), Primitive.INT)
					.statik("arraycopy", Primitive.VOID, (lowering, call) -> runtimeCall("arraycopy", call.argument(0),
							call.argument(1), call.argument(2), call.argument(3), call.argument(4),
							arrayKind(call.arguments().get(0).type()), arrayKind(call.arguments().get(2).type())),
							object, Primitive.INT, object, Primitive.INT, Primitive.INT)
					.statik("identityHashCode", Primitive.INT, runtime("identity-hash-code"), object)
					.statik("lineSeparator", classes.libraryType(STRING),
							(lowering, call) -> JavaForms.string("\n")).made;
			}
			case "java.io.PrintStream": {
				ClassType object = classes.libraryType(OBJECT);
				Builder builder = new Builder(name, false, classes);
				ClassType self = new ClassType(builder.made, List.of());
				return builder
					.any("println", false, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-line", call.self()))
					.method("println", Primitive.VOID, (lowering, call) -> newline(call.self()))
					.any("print", false, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-string", call.self()))
					.method("printf", self, JavaIntrinsics::printf, classes.libraryType(STRING), new ArrayType(object))
					.varargs()
					.method("format", self, JavaIntrinsics::printf, classes.libraryType(STRING), new ArrayType(object))
					.varargs()
					.method("flush", Primitive.VOID, host("finish-output")).made;
			}
			case "java.lang.IO": {
				ClassType object = classes.libraryType(OBJECT);
				ClassType string = classes.libraryType(STRING);
				return new Builder(name, false, classes)
					.any("println", true, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-line", OUT))
					.statik("println", Primitive.VOID, (lowering, call) -> newline(OUT))
					.any("print", true, Primitive.VOID, object,
							(lowering, call) -> print(lowering, call, "write-string", OUT))
					.statik("readln", string, runtime("readln"))
					.statik("readln", string, (lowering, call) -> call("progn", call("write-string", call.argument(0)),
							call("finish-output"), runtimeCall("readln")), string).made;
			}
			default:
				return null;
		}
	}

	private static LispVal newline(LispVal stream) {
		return stream.equals(OUT) ? call("terpri") : call("terpri", stream);
	}

	private static LispVal print(Context lowering, Invocation call, String writer, LispVal stream) {
		LispVal text = text(lowering, call, 0);
		return stream.equals(OUT) ? call(writer, text) : call(writer, text, stream);
	}

	private static LispVal printf(Context lowering, Invocation call) {
		LispVal text = runtimeCall("format", call.argument(0), call.argument(1), lowering.stringFunction());
		LispSymbol stream = new LispSymbol("%stream");
		return JavaForms.let(stream, call.self(), List.of(call("write-string", text, stream), stream));
	}

	/** An argument as the text {@code String.valueOf} makes of it. */
	private static LispVal text(Context lowering, Invocation call, int index) {
		Typed argument = call.arguments().get(index);
		if (argument.type().equals(new ArrayType(Primitive.CHAR))) {
			return runtimeCall("chars-string", argument.form(), integer(0), JavaForms.NIL);
		}
		return lowering.stringOf(argument, call.pos());
	}

	private static JavaClass string(Builder builder, JavaClasses classes) {
		ClassType string = new ClassType(builder.made, List.of());
		ClassType object = classes.libraryType(OBJECT);
		Primitive i = Primitive.INT;
		Primitive b = Primitive.BOOLEAN;
		ArrayType characters = new ArrayType(Primitive.CHAR);
		JavaEmitter format = (lowering, call) -> runtimeCall("format", call.self(), call.argument(0),
				lowering.stringFunction());
		return builder.constructor((lowering, call) -> JavaForms.string(""))
			.constructor(runtime("string-copy"), string)
			.constructor((lowering, call) -> runtimeCall("chars-string", call.argument(0), integer(0), JavaForms.NIL),
					characters)
			.constructor((lowering, call) -> runtimeCall("chars-string", call.argument(0), call.argument(1),
					call.argument(2)), characters, i, i)
			.method("length", i, host("length"))
			.method("charAt", Primitive.CHAR, JavaIntrinsics::charAt, i)
			.method("isEmpty", b, (lowering, call) -> call("=", call("length", call.self()), integer(0)))
			.method("isBlank", b, runtime("blank-p"))
			.method("equals", b, (lowering, call) -> {
				JavaType other = call.arguments().get(0).type();
				return other.equals(string) || other == JavaType.Null.NULL
						? call("equal", call.self(), call.argument(0))
						: runtimeCall("string-equals", call.self(), call.argument(0));
			}, object)
			.method("equalsIgnoreCase", b, runtime("string-equals-ignore-case"), string)
			.method("hashCode", i, runtime("string-hash"))
			.method("compareTo", i, runtime("string-compare"), string)
			.method("compareToIgnoreCase", i, runtime("string-compare-ignore-case"), string)
			.method("toString", string, (lowering, call) -> call.self())
			.method("intern", string, runtime("intern"))
			.method("substring", string,
					(lowering, call) -> runtimeCall("substring", call.self(), call.argument(0), JavaForms.NIL), i)
			.method("substring", string, runtime("substring"), i, i)
			.method("indexOf", i,
					(lowering, call) -> call("or", call("search", call.argument(0), call.self()), integer(-1)), string)
			.method("indexOf", i, runtime("index-of"), string, i)
			.method("indexOf", i,
					(lowering, call) -> runtimeCall("index-of-char", call.self(), call.argument(0), integer(0)), i)
			.method("indexOf", i, runtime("index-of-char"), i, i)
			.method("lastIndexOf", i, runtime("last-index-of"), string)
			.method("lastIndexOf", i, runtime("last-index-of-char"), i)
			.method("contains", b,
					(lowering, call) -> call("if", call("search", text(lowering, call, 0), call.self()), JavaForms.T,
							JavaForms.NIL),
					object)
			.method("startsWith", b,
					(lowering, call) -> runtimeCall("starts-with", call.self(), call.argument(0), integer(0)), string)
			.method("startsWith", b, runtime("starts-with"), string, i)
			.method("endsWith", b, runtime("ends-with"), string)
			.method("toUpperCase", string, host("%string-upcase-piece"))
			.method("toLowerCase", string, host("%string-downcase-piece"))
			.method("trim", string, runtime("trim"))
			.method("strip", string, (lowering, call) -> runtimeCall("strip", call.self(), JavaForms.T, JavaForms.T))
			.method("stripLeading", string,
					(lowering, call) -> runtimeCall("strip", call.self(), JavaForms.T, JavaForms.NIL))
			.method("stripTrailing", string,
					(lowering, call) -> runtimeCall("strip", call.self(), JavaForms.NIL, JavaForms.T))
			.method("repeat", string, runtime("repeat"), i)
			.method("replace", string, runtime("replace-char"), Primitive.CHAR, Primitive.CHAR)
			.method("replace", string,
					(lowering, call) -> runtimeCall("replace-text", call.self(), text(lowering, call, 0),
							text(lowering, call, 1)),
					object, object)
			.method("concat", string, (lowering, call) -> call("%string-concat", call.self(), call.argument(0)), string)
			.method("toCharArray", characters, runtime("to-chars"))
			.method("split", new ArrayType(string),
					(lowering, call) -> runtimeCall("split", call.self(), call.argument(0), integer(0)), string)
			.method("split", new ArrayType(string), runtime("split"), string, i)
			.method("formatted", string, format, new ArrayType(object))
			.varargs()
			.statik("format", string,
					(lowering, call) -> runtimeCall("format", call.argument(0), call.argument(1),
							lowering.stringFunction()),
					string, new ArrayType(object))
			.varargs()
			.statik("join", string,
					(lowering, call) -> runtimeCall("join", text(lowering, call, 0), call.argument(1),
							lowering.stringFunction()),
					object, new ArrayType(object))
			.varargs()
			.any("valueOf", true, string, object, (lowering, call) -> text(lowering, call, 0)).made;
	}

	/**
	 * An array's type as the message of {@code System.arraycopy} names it: the element's
	 * primitive type, {@code object array}; {@code nil} for a type that is no array.
	 */
	private static LispVal arrayKind(JavaType type) {
		if (!(type instanceof ArrayType array)) {
			return JavaForms.NIL;
		}
		return JavaForms.string(array.element() instanceof Primitive primitive ? primitive.toString() : "object array");
	}

	/**
	 * {@code String.charAt}: the index tested against the string's length where it is
	 * read -- the host's {@code char} checks nothing on the compiled backends -- and the
	 * language's exception outside it, then the host's character. A call of a function of
	 * the run time doing both cost a loop over a string twice the time on wasm
	 * (.kb/java-frontend.md, "Exceptions").
	 */
	private static LispVal charAt(Context lowering, Invocation call) {
		List<LispVal> bindings = new ArrayList<>();
		LispVal text = held(lowering, call.self(), bindings);
		LispVal index = held(lowering, call.argument(0), bindings);
		LispVal test = call("if", call("or", call("<", index, integer(0)), call(">=", index, call("length", text))),
				runtimeCall("string-index", index, text));
		LispVal read = call("char-code", call("char", text, index));
		return bindings.isEmpty() ? call("progn", test, read) : list(cl("let*"), list(bindings), test, read);
	}

	/** A form read twice: itself when it is simple, else a variable bound to it. */
	private static LispVal held(Context lowering, LispVal form, List<LispVal> bindings) {
		if (JavaForms.isSimple(form)) {
			return form;
		}
		LispSymbol variable = lowering.temporary();
		bindings.add(list(variable, form));
		return variable;
	}

	private static LispVal characters() {
		return list(cl("make-array"), integer(16), new LispSymbol(":ELEMENT-TYPE"), quote(cl("character")),
				new LispSymbol(":ADJUSTABLE"), JavaForms.T, new LispSymbol(":FILL-POINTER"), integer(0));
	}

	private static JavaClass stringBuilder(Builder builder, JavaClasses classes) {
		ClassType self = new ClassType(builder.made, List.of());
		ClassType string = classes.libraryType(STRING);
		ClassType object = classes.libraryType(OBJECT);
		Primitive i = Primitive.INT;
		return builder.constructor((lowering, call) -> characters())
			.constructor((lowering, call) -> characters(), i)
			.constructor((lowering, call) -> runtimeCall("sb-append", characters(), call.argument(0)), string)
			.constructor((lowering, call) -> runtimeCall("sb-append", characters(), text(lowering, call, 0)), object)
			.any("append", false, self, object,
					(lowering, call) -> call.arguments().get(0).type() == Primitive.CHAR
							? runtimeCall("sb-append-char", call.self(), call.argument(0))
							: runtimeCall("sb-append", call.self(), text(lowering, call, 0)))
			.method("insert", self,
					(lowering, call) -> runtimeCall("sb-insert", call.self(), call.argument(0), text(lowering,
							new Invocation(call.receiver(), call.arguments().subList(1, 2), false, call.pos()), 0)),
					i, object)
			.method("reverse", self, runtime("sb-reverse"))
			.method("setLength", Primitive.VOID, runtime("sb-set-length"), i)
			.method("setCharAt", Primitive.VOID, runtime("sb-set-char"), i, Primitive.CHAR)
			.method("deleteCharAt", self,
					(lowering, call) -> runtimeCall("sb-delete-char", call.self(), call.argument(0)), i)
			.method("delete", self, runtime("sb-delete"), i, i)
			.method("replace", self, runtime("sb-replace"), i, i, string)
			.method("indexOf", i, runtime("sb-index-of"), string)
			.method("toString", string, runtime("sb-string"))
			.method("length", i, host("length"))
			.method("isEmpty", Primitive.BOOLEAN,
					(lowering, call) -> call("=", call("length", call.self()), integer(0)))
			.method("charAt", Primitive.CHAR, runtime("sb-char"), i)
			.method("substring", string,
					(lowering, call) -> runtimeCall("sb-substring", call.self(), call.argument(0), JavaForms.NIL), i)
			.method("substring", string, runtime("sb-substring"), i, i).made;
	}

	private static JavaClass integerClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		return builder.field("MAX_VALUE", i, integer(Integer.MAX_VALUE))
			.field("MIN_VALUE", i, integer(Integer.MIN_VALUE))
			.statik("parseInt", i,
					(lowering, call) -> lowering.library(SUPPORT, "parseInt", List.of(call.argument(0), integer(10))),
					string)
			.statik("parseInt", i, support("parseInt"), string, i)
			.statik("valueOf", self,
					(lowering, call) -> lowering.library(SUPPORT, "parseInt", List.of(call.argument(0), integer(10))),
					string)
			.statik("toString", string, host("%decimal-piece"), i)
			.statik("toString", string, runtime("radix-string"), i, i)
			.statik("toBinaryString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(2), integer(32)), i)
			.statik("toHexString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(16), integer(32)), i)
			.statik("toOctalString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(8), integer(32)), i)
			.statik("valueOf", self, (lowering, call) -> call.argument(0), i)
			.statik("compare", i, runtime("compare"), i, i)
			.statik("signum", i, host("signum"), i)
			.statik("sum", i, (lowering, call) -> JavaForms.wrap(i, call("+", call.argument(0), call.argument(1))), i,
					i)
			.statik("max", i, host("max"), i, i)
			.statik("min", i, host("min"), i, i)
			.statik("bitCount", i,
					(lowering, call) -> call("logcount", call("logand", call.argument(0), integer(0xFFFFFFFFL))), i)
			.statik("hashCode", i, (lowering, call) -> call.argument(0), i)
			.method("compareTo", i, runtime("compare"), self)
			.method("intValue", i, (lowering, call) -> call.self())
			.method("longValue", Primitive.LONG, (lowering, call) -> call.self())
			.method("doubleValue", Primitive.DOUBLE,
					(lowering, call) -> call("float", call.self(), new LispDouble(1.0))).made;
	}

	private static JavaClass longClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		Primitive l = Primitive.LONG;
		return builder.field("MAX_VALUE", l, integer(Long.MAX_VALUE))
			.field("MIN_VALUE", l, integer(Long.MIN_VALUE))
			.statik("parseLong", l,
					(lowering, call) -> lowering.library(SUPPORT, "parseLong", List.of(call.argument(0), integer(10))),
					string)
			.statik("parseLong", l, support("parseLong"), string, i)
			.statik("valueOf", self,
					(lowering, call) -> call("cons",
							lowering.library(SUPPORT, "parseLong", List.of(call.argument(0), integer(10))),
							JavaForms.BOXED_LONG),
					string)
			.statik("toString", string, host("%decimal-piece"), l)
			.statik("toString", string, runtime("radix-string"), l, i)
			.statik("toBinaryString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(2), integer(64)), l)
			.statik("toHexString", string,
					(lowering, call) -> runtimeCall("unsigned-string", call.argument(0), integer(16), integer(64)), l)
			.statik("hashCode", i, runtime("long-hash"), l)
			.statik("valueOf", self, (lowering, call) -> call("cons", call.argument(0), JavaForms.BOXED_LONG), l)
			.statik("compare", i, runtime("compare"), l, l)
			.statik("signum", i, host("signum"), l)
			.statik("sum", l, (lowering, call) -> JavaForms.wrap(l, call("+", call.argument(0), call.argument(1))), l,
					l)
			.statik("max", l, host("max"), l, l)
			.statik("min", l, host("min"), l, l)
			.statik("bitCount", i,
					(lowering, call) -> call("logcount", call("logand", call.argument(0),
							integer(java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE)))),
					l)
			.method("compareTo", i,
					(lowering, call) -> runtimeCall("compare", call("car", call.self()), call("car", call.argument(0))),
					self)
			.method("intValue", i, (lowering, call) -> JavaForms.wrap(i, call("car", call.self())))
			.method("longValue", l, (lowering, call) -> call("car", call.self()))
			.method("doubleValue", Primitive.DOUBLE,
					(lowering, call) -> call("float", call("car", call.self()), new LispDouble(1.0))).made;
	}

	private static JavaClass doubleClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		Primitive d = Primitive.DOUBLE;
		Primitive b = Primitive.BOOLEAN;
		JavaEmitter nan = (lowering, call) -> runtimeCall("nan-p", operands(call).get(0));
		JavaEmitter infinite = (lowering, call) -> runtimeCall("infinite-p", operands(call).get(0));
		return builder.field("MAX_VALUE", d, new LispDouble(Double.MAX_VALUE))
			.field("MIN_VALUE", d, new LispDouble(Double.MIN_VALUE))
			.field("POSITIVE_INFINITY", d, runtimeCall("infinity", JavaForms.NIL))
			.field("NEGATIVE_INFINITY", d, runtimeCall("infinity", JavaForms.T))
			.field("NaN", d, runtimeCall("nan"))
			.statik("parseDouble", d, support("parseDouble"), string)
			.statik("valueOf", self, support("parseDouble"), string)
			.statik("toString", string, runtime("double-string"), d)
			.statik("compare", i, runtime("double-compare"), d, d)
			.statik("hashCode", i, runtime("double-hash"), d)
			.statik("doubleToLongBits", Primitive.LONG, runtime("double-bits"), d)
			.statik("valueOf", self, (lowering, call) -> call.argument(0), d)
			.statik("isNaN", b, nan, d)
			.statik("isInfinite", b, infinite, d)
			.statik("isFinite", b,
					(lowering, call) -> call("not",
							call("or", runtimeCall("nan-p", call.argument(0)),
									runtimeCall("infinite-p", call.argument(0)))),
					d)
			.statik("sum", d, host("+"), d, d)
			.statik("max", d, runtime("double-max"), d, d)
			.statik("min", d, runtime("double-min"), d, d)
			.method("isNaN", b, nan)
			.method("isInfinite", b, infinite)
			.method("compareTo", i, runtime("double-compare"), self)
			.method("intValue", i, runtime("double-to-int"))
			.method("longValue", Primitive.LONG, runtime("double-to-long"))
			.method("doubleValue", d, (lowering, call) -> call.self()).made;
	}

	/**
	 * {@code Byte} and {@code Short}: the number in a cons with its tag, {@code :byte} or
	 * {@code :short}.
	 */
	private static JavaClass smallClass(Builder builder, JavaClasses classes, Primitive held) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		boolean isByte = held == Primitive.BYTE;
		LispSymbol tag = java.util.Objects.requireNonNull(JavaForms.boxTag(held));
		String parse = isByte ? "parseByte" : "parseShort";
		JavaEmitter box = (lowering, call) -> call("cons", call.argument(0), tag);
		JavaEmitter value = (lowering, call) -> call("car", call.self());
		return builder.field("MAX_VALUE", held, integer(isByte ? Byte.MAX_VALUE : Short.MAX_VALUE))
			.field("MIN_VALUE", held, integer(isByte ? Byte.MIN_VALUE : Short.MIN_VALUE))
			.statik(parse, held,
					(lowering, call) -> lowering.library(SUPPORT, parse, List.of(call.argument(0), integer(10))),
					string)
			.statik(parse, held, support(parse), string, i)
			.statik("valueOf", self,
					(lowering, call) -> call("cons",
							lowering.library(SUPPORT, parse, List.of(call.argument(0), integer(10))), tag),
					string)
			.statik("valueOf", self, box, held)
			.statik("toString", string, host("%decimal-piece"), held)
			.statik("compare", i, host("-"), held, held)
			.statik("hashCode", i, (lowering, call) -> call.argument(0), held)
			.statik("toUnsignedInt", i,
					(lowering, call) -> call("logand", call.argument(0), integer(isByte ? 0xFF : 0xFFFF)), held)
			.method("compareTo", i,
					(lowering, call) -> call("-", call("car", call.self()), call("car", call.argument(0))), self)
			.method(isByte ? "byteValue" : "shortValue", held, value)
			.method("intValue", i, value)
			.method("longValue", Primitive.LONG, value)
			.method("floatValue", Primitive.FLOAT,
					(lowering, call) -> call("float", call("car", call.self()), new LispDouble(1.0)))
			.method("doubleValue", Primitive.DOUBLE,
					(lowering, call) -> call("float", call("car", call.self()), new LispDouble(1.0))).made;
	}

	/**
	 * {@code Float}: the float, a double holding a binary32, in a cons with
	 * {@code :float}.
	 */
	private static JavaClass floatClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		builder.made.superclass = classes.library(NUMBER);
		Primitive i = Primitive.INT;
		Primitive f = Primitive.FLOAT;
		Primitive b = Primitive.BOOLEAN;
		LispSymbol tag = java.util.Objects.requireNonNull(JavaForms.boxTag(f));
		JavaEmitter value = (lowering, call) -> call("car", call.self());
		return builder.field("MAX_VALUE", f, new LispDouble(Float.MAX_VALUE))
			.field("MIN_VALUE", f, new LispDouble(Float.MIN_VALUE))
			.field("MIN_NORMAL", f, new LispDouble(Float.MIN_NORMAL))
			.field("POSITIVE_INFINITY", f, runtimeCall("infinity", JavaForms.NIL))
			.field("NEGATIVE_INFINITY", f, runtimeCall("infinity", JavaForms.T))
			.field("NaN", f, runtimeCall("nan"))
			.statik("parseFloat", f, support("parseFloat"), string)
			.statik("valueOf", self,
					(lowering, call) -> call("cons", lowering.library(SUPPORT, "parseFloat", List.of(call.argument(0))),
							tag),
					string)
			.statik("valueOf", self, (lowering, call) -> call("cons", call.argument(0), tag), f)
			.statik("toString", string, runtime("float-string"), f)
			.statik("compare", i, runtime("double-compare"), f, f)
			.statik("hashCode", i, runtime("float-bits"), f)
			.statik("floatToIntBits", i, runtime("float-bits"), f)
			.statik("isNaN", b, runtime("nan-p"), f)
			.statik("isInfinite", b, runtime("infinite-p"), f)
			.statik("isFinite", b,
					(lowering, call) -> call("not",
							call("or", runtimeCall("nan-p", call.argument(0)),
									runtimeCall("infinite-p", call.argument(0)))),
					f)
			.statik("sum", f, (lowering, call) -> call("%single-float", call("+", call.argument(0), call.argument(1))),
					f, f)
			.statik("max", f, runtime("double-max"), f, f)
			.statik("min", f, runtime("double-min"), f, f)
			.method("isNaN", b, (lowering, call) -> runtimeCall("nan-p", call("car", call.self())))
			.method("isInfinite", b, (lowering, call) -> runtimeCall("infinite-p", call("car", call.self())))
			.method("compareTo", i,
					(lowering, call) -> runtimeCall("double-compare", call("car", call.self()),
							call("car", call.argument(0))),
					self)
			.method("floatValue", f, value)
			.method("doubleValue", Primitive.DOUBLE, value)
			.method("intValue", i, (lowering, call) -> runtimeCall("double-to-int", call("car", call.self())))
			.method("longValue", Primitive.LONG,
					(lowering, call) -> runtimeCall("double-to-long", call("car", call.self()))).made;
	}

	private static JavaClass booleanClass(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		Primitive i = Primitive.INT;
		Primitive b = Primitive.BOOLEAN;
		JavaEmitter compare = (lowering, call) -> {
			List<LispVal> operands = operands(call);
			return call("-", call("if", operands.get(0), integer(1), integer(0)),
					call("if", operands.get(1), integer(1), integer(0)));
		};
		return builder.field("TRUE", self, JavaForms.T)
			.field("FALSE", self, JavaForms.BOXED_FALSE)
			.statik("parseBoolean", b, runtime("parse-boolean"), string)
			.statik("valueOf", self, (lowering, call) -> call.argument(0), b)
			.statik("valueOf", self,
					(lowering, call) -> call("if", runtimeCall("parse-boolean", call.argument(0)), JavaForms.T,
							JavaForms.BOXED_FALSE),
					string)
			.statik("toString", string,
					(lowering, call) -> call("if", call.argument(0), JavaForms.string("true"),
							JavaForms.string("false")),
					b)
			.statik("hashCode", i, (lowering, call) -> call("if", call.argument(0), integer(1231), integer(1237)), b)
			.statik("compare", i, compare, b, b)
			.statik("logicalAnd", b, host("and"), b, b)
			.statik("logicalOr", b, host("or"), b, b)
			.statik("logicalXor", b, (lowering, call) -> call("not", call("eq", call.argument(0), call.argument(1))), b,
					b)
			.method("booleanValue", b, (lowering, call) -> runtimeCall("unbox-boolean", call.self()))
			.method("compareTo", i,
					(lowering, call) -> call("-",
							call("if", runtimeCall("unbox-boolean", call.self()), integer(1), integer(0)),
							call("if", runtimeCall("unbox-boolean", call.argument(0)), integer(1), integer(0))),
					self).made;
	}

	private static JavaClass character(Builder builder, JavaClasses classes) {
		ClassType string = classes.libraryType(STRING);
		ClassType self = new ClassType(builder.made, List.of());
		Primitive i = Primitive.INT;
		Primitive c = Primitive.CHAR;
		Primitive b = Primitive.BOOLEAN;
		// a boxed char is a character, a char its code
		builder.field("MAX_VALUE", c, integer(0xFFFF))
			.field("MIN_VALUE", c, integer(0))
			.statik("valueOf", self, (lowering, call) -> call("code-char", call.argument(0)), c)
			.statik("toString", string, runtime("char-string"), c)
			.statik("compare", i, host("-"), c, c)
			.statik("hashCode", i, (lowering, call) -> call.argument(0), c)
			.statik("getNumericValue", i, runtime("numeric-value"), c)
			.statik("digit", i, runtime("digit"), c, i)
			.statik("forDigit", c, runtime("for-digit"), i, i)
			.statik("toUpperCase", c, runtime("char-upcase"), c)
			.statik("toLowerCase", c, runtime("char-downcase"), c)
			.method("compareTo", i,
					(lowering, call) -> call("-", call("char-code", call.self()), call("char-code", call.argument(0))),
					self)
			.method("charValue", c, (lowering, call) -> call("char-code", call.self()));
		for (String test : List.of("isDigit", "isLetter", "isLetterOrDigit", "isAlphabetic", "isWhitespace",
				"isSpaceChar", "isUpperCase", "isLowerCase")) {
			String name = "char-"
					+ test.substring(2).replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT) + "-p";
			builder.statik(test, b, runtime(name), c);
		}
		return builder.made;
	}

	private static JavaClass math(Builder builder) {
		Primitive i = Primitive.INT;
		Primitive l = Primitive.LONG;
		Primitive f = Primitive.FLOAT;
		Primitive d = Primitive.DOUBLE;
		builder.field("PI", d, new LispDouble(Math.PI))
			.field("E", d, new LispDouble(Math.E))
			.statik("pow", d, strict("pow"), d, d)
			.statik("atan2", d, strict("atan2"), d, d)
			.statik("hypot", d, strict("hypot"), d, d)
			.statik("abs", i, (lowering, call) -> JavaForms.wrap(i, call("abs", call.argument(0))), i)
			.statik("abs", l, (lowering, call) -> JavaForms.wrap(l, call("abs", call.argument(0))), l)
			.statik("abs", d, host("abs"), d)
			.statik("abs", f, host("abs"), f)
			.statik("round", l, runtime("round"), d)
			.statik("round", i,
					(lowering, call) -> call("max", integer(Integer.MIN_VALUE),
							call("min", integer(Integer.MAX_VALUE), runtimeCall("round", call.argument(0)))),
					f)
			.statik("signum", f, strict("signum"), f)
			.statik("random", d, (lowering, call) -> call("random", new LispDouble(1.0)))
			.statik("toRadians", d,
					(lowering, call) -> call("*", call.argument(0), new LispDouble(0.017453292519943295)), d)
			.statik("toDegrees", d, (lowering, call) -> call("*", call.argument(0), new LispDouble(57.29577951308232)),
					d)
			.statik("floorDiv", i,
					(lowering, call) -> JavaForms.wrap(i,
							call("values", call("floor", call.argument(0), call.argument(1)))),
					i, i)
			.statik("floorDiv", l,
					(lowering, call) -> JavaForms.wrap(l,
							call("values", call("floor", call.argument(0), call.argument(1)))),
					l, l)
			.statik("floorMod", i, host("mod"), i, i)
			.statik("floorMod", l, host("mod"), l, l);
		for (String name : List.of("sqrt", "cbrt", "floor", "ceil", "rint", "exp", "log", "log10", "sin", "cos", "tan",
				"asin", "acos", "atan", "sinh", "cosh", "tanh", "signum")) {
			builder.statik(name, d, strict(name), d);
		}
		for (String name : List.of("max", "min")) {
			for (Primitive type : List.of(i, l)) {
				builder.statik(name, type, host(name), type, type);
			}
			builder.statik(name, f, runtime("double-" + name), f, f);
			builder.statik(name, d, runtime("double-" + name), d, d);
		}
		for (String name : List.of("addExact", "subtractExact", "multiplyExact")) {
			builder.statik(name, i, support(name), i, i).statik(name, l, support(name + "Long"), l, l);
		}
		builder.statik("negateExact", i, support("negateExact"), i).statik("toIntExact", i, support("toIntExact"), l);
		return builder.made;
	}

	// ---- the native members of the library's Java source

	/**
	 * The {@code native} members of the library's Java source, by class, name and erased
	 * parameter types: what Java cannot say, or says slowly, over the value model.
	 */
	private static final Map<String, JavaEmitter> NATIVES = natives();

	private static Map<String, JavaEmitter> natives() {
		Map<String, JavaEmitter> natives = new HashMap<>();
		String arrays = "java.util.Arrays.";
		// an array of references is an untyped vector whatever its element type
		natives.put(arrays + "newArray(Object[],int)", (lowering, call) -> call("make-array", call.argument(1),
				new LispSymbol(":INITIAL-ELEMENT"), JavaForms.NIL));
		natives.put(arrays + "sortRange(int[],int,int)", runtime("sort-ints"));
		natives.put(arrays + "sortRange(long[],int,int)", runtime("sort-numbers"));
		natives.put(arrays + "sortRange(double[],int,int)", runtime("sort-doubles"));
		natives.put(arrays + "sortRange(char[],int,int)", runtime("sort-numbers"));
		natives.put("rontolisp.internal.Support.readDouble(String)", runtime("read-double"));
		natives.put("rontolisp.internal.Support.floatHash(float)", runtime("float-bits"));
		// a Float is the float in a cons with its tag
		natives.put("rontolisp.internal.Support.readFloat(String)", (lowering, call) -> {
			LispSymbol read = new LispSymbol("%float");
			return JavaForms.let(read, runtimeCall("read-float", call.argument(0)),
					List.of(call("if", read,
							call("cons", read, java.util.Objects.requireNonNull(JavaForms.boxTag(Primitive.FLOAT))),
							JavaForms.NIL)));
		});
		return Map.copyOf(natives);
	}

}
