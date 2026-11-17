package am.ik.rontolisp.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.java.JavaClass.Field;
import am.ik.rontolisp.java.JavaClass.Method;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaEmitter.Invocation;
import am.ik.rontolisp.java.JavaEmitter.Typed;
import am.ik.rontolisp.java.JavaSyntax.ArrayInitializer;
import am.ik.rontolisp.java.JavaSyntax.Assign;
import am.ik.rontolisp.java.JavaSyntax.Binary;
import am.ik.rontolisp.java.JavaSyntax.Block;
import am.ik.rontolisp.java.JavaSyntax.BooleanLiteral;
import am.ik.rontolisp.java.JavaSyntax.Break;
import am.ik.rontolisp.java.JavaSyntax.Call;
import am.ik.rontolisp.java.JavaSyntax.Cast;
import am.ik.rontolisp.java.JavaSyntax.Catch;
import am.ik.rontolisp.java.JavaSyntax.CharLiteral;
import am.ik.rontolisp.java.JavaSyntax.CompilationUnit;
import am.ik.rontolisp.java.JavaSyntax.Conditional;
import am.ik.rontolisp.java.JavaSyntax.ConstructorCall;
import am.ik.rontolisp.java.JavaSyntax.Continue;
import am.ik.rontolisp.java.JavaSyntax.Declarator;
import am.ik.rontolisp.java.JavaSyntax.DoWhile;
import am.ik.rontolisp.java.JavaSyntax.DoubleLiteral;
import am.ik.rontolisp.java.JavaSyntax.Empty;
import am.ik.rontolisp.java.JavaSyntax.Expression;
import am.ik.rontolisp.java.JavaSyntax.ExpressionStatement;
import am.ik.rontolisp.java.JavaSyntax.For;
import am.ik.rontolisp.java.JavaSyntax.ForEach;
import am.ik.rontolisp.java.JavaSyntax.If;
import am.ik.rontolisp.java.JavaSyntax.Index;
import am.ik.rontolisp.java.JavaSyntax.InstanceOf;
import am.ik.rontolisp.java.JavaSyntax.IntLiteral;
import am.ik.rontolisp.java.JavaSyntax.Lambda;
import am.ik.rontolisp.java.JavaSyntax.LocalVariable;
import am.ik.rontolisp.java.JavaSyntax.Name;
import am.ik.rontolisp.java.JavaSyntax.New;
import am.ik.rontolisp.java.JavaSyntax.NewArray;
import am.ik.rontolisp.java.JavaSyntax.NullLiteral;
import am.ik.rontolisp.java.JavaSyntax.Parameter;
import am.ik.rontolisp.java.JavaSyntax.Return;
import am.ik.rontolisp.java.JavaSyntax.Select;
import am.ik.rontolisp.java.JavaSyntax.Statement;
import am.ik.rontolisp.java.JavaSyntax.StringLiteral;
import am.ik.rontolisp.java.JavaSyntax.This;
import am.ik.rontolisp.java.JavaSyntax.Throw;
import am.ik.rontolisp.java.JavaSyntax.Try;
import am.ik.rontolisp.java.JavaSyntax.TypeName;
import am.ik.rontolisp.java.JavaSyntax.Unary;
import am.ik.rontolisp.java.JavaSyntax.While;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

import static am.ik.rontolisp.java.JavaForms.BREAK_BLOCK;
import static am.ik.rontolisp.java.JavaForms.CONTINUE_BLOCK;
import static am.ik.rontolisp.java.JavaForms.FUNCTION_BLOCK;
import static am.ik.rontolisp.java.JavaForms.NIL;
import static am.ik.rontolisp.java.JavaForms.T;
import static am.ik.rontolisp.java.JavaForms.THIS;
import static am.ik.rontolisp.java.JavaForms.call;
import static am.ik.rontolisp.java.JavaForms.cl;
import static am.ik.rontolisp.java.JavaForms.form;
import static am.ik.rontolisp.java.JavaForms.function;
import static am.ik.rontolisp.java.JavaForms.integer;
import static am.ik.rontolisp.java.JavaForms.isSimple;
import static am.ik.rontolisp.java.JavaForms.list;
import static am.ik.rontolisp.java.JavaForms.progn;
import static am.ik.rontolisp.java.JavaForms.quote;
import static am.ik.rontolisp.java.JavaForms.runtimeCall;
import static am.ik.rontolisp.java.JavaForms.string;

/**
 * Lowers a Java program -- the classes its file declares and the classes of the library
 * it names -- to the Common Lisp core forms the pipeline already consumes, so it joins
 * {@code CompileFrontend.expand} and {@code LispEvaluator} unchanged and no backend
 * learns a Java name. The lowering table and the decisions behind each row:
 * {@code .kb/java-frontend.md}.
 *
 * <p>
 * The shape of the pass:
 * <ul>
 * <li><b>Static types pick the form.</b> Every expression is lowered to a form and its
 * type ({@link Typed}): {@code +} is a string concatenation, a wrapped integer addition
 * or a double addition by its operands' types, and a conversion -- a widening, a boxing,
 * a number's text -- is made where the types say, never tested when the program
 * runs.</li>
 * <li><b>A class is a structure</b>, {@code extends} its {@code :include}, an instance
 * field a slot, a static field a global, an instance method a function taking
 * {@code this} first. A method's function carries its signature in its name
 * ({@link JavaNames}), so overloads never meet.</li>
 * <li><b>The program is closed, so a call is direct when it can be.</b> One
 * implementation under the receiver's static type is a plain call; several are one
 * function testing the structure predicates, deepest class first, made once per static
 * type and signature. A lambda is a {@code lambda}: an interface no class implements is
 * called with {@code funcall}.</li>
 * <li><b>A construct the subset does not have is refused by name</b>, at its position,
 * and so is a program a compiler refuses where the lowering would otherwise have to
 * guess: nothing is lowered as something else.</li>
 * <li><b>Lowered until the class table is still.</b> A library class is read where the
 * program first names it, which can be inside a method body lowered after a call whose
 * dispatch it changes; {@link #lower} lowers again from the start when a pass added a
 * class.</li>
 * </ul>
 */
final class JavaLowering implements JavaEmitter.Context {

	private record Local(LispSymbol symbol, JavaType type) {
	}

	/** A function body being lowered: whether a {@code return} leaves from its middle. */
	private static final class Frame {

		boolean exits;

	}

	/** A loop being lowered: the exits its body takes. */
	private static final class Loop {

		final int finallyDepth;

		boolean breaks;

		boolean continues;

		Loop(int finallyDepth) {
			this.finallyDepth = finallyDepth;
		}

	}

	/** Where a value is stored: how it is read, and how a new one is written. */
	private record Place(JavaType type, LispVal read, Function<LispVal, LispVal> write, List<LispVal> bindings) {
	}

	private static final LispSymbol OBJECT_STRING = new LispSymbol("%string");

	private static final LispSymbol OBJECT_EQUALS = new LispSymbol("%equals");

	private static final LispSymbol OBJECT_HASH = new LispSymbol("%hash");

	private static final LispSymbol CLASS_NAME = new LispSymbol("%class-name");

	private static final LispSymbol SIMPLE_NAME = new LispSymbol("%simple-name");

	private static final LispSymbol LISP_ERROR = new LispSymbol("%lisp-error");

	private static final LispSymbol RECEIVER = new LispSymbol("%o");

	private static final Object NOT_CONSTANT = new Object();

	private final JavaClasses classes;

	private final Source program;

	private final List<JavaClass> declared;

	private final ClassType stringType;

	private final ClassType objectType;

	// ---- what one pass makes

	private final List<LispVal> functions = new ArrayList<>();

	private final Map<String, LispVal> dispatchers = new LinkedHashMap<>();

	private final List<LispVal> variables = new ArrayList<>();

	private final List<LispVal> initializers = new ArrayList<>();

	/** The interfaces {@code instanceof} or a cast tests, with where one first does. */
	private final Map<JavaClass, Integer> interfaceTests = new LinkedHashMap<>();

	/** The interfaces a lambda of the program implements. */
	private final Set<JavaClass> lambdaTargets = new LinkedHashSet<>();

	private final Set<LispSymbol> generated = new LinkedHashSet<>();

	private final Map<Field, Object> constants = new HashMap<>();

	/** The string forms known to be no {@code null}. */
	private final Set<LispVal> nonNull = Collections.newSetFromMap(new IdentityHashMap<>());

	private int temporaries;

	/** Whether the program has a {@code throw} or a cast that can fail. */
	private boolean raises;

	// ---- where the lowering stands

	private JavaClass currentClass;

	private JavaClasses.Unit unit;

	private boolean staticContext;

	private JavaType returnType = Primitive.VOID;

	/** The static field whose initializer is being lowered, or {@code null}. */
	private @Nullable Field initializing;

	private Deque<Map<String, Local>> scopes = new ArrayDeque<>();

	private final Deque<Frame> frames = new ArrayDeque<>();

	private final Deque<Loop> loops = new ArrayDeque<>();

	private int finallyDepth;

	private SourceProvenance.@Nullable Unit provenance;

	private int @Nullable [] lineStarts;

	private JavaLowering(JavaClasses classes, List<JavaClass> declared, Source program) {
		this.classes = classes;
		this.declared = declared;
		this.program = program;
		this.stringType = classes.libraryType("java.lang.String");
		this.objectType = classes.libraryType("java.lang.Object");
		this.currentClass = declared.get(0);
		this.unit = classes.unit(this.currentClass);
	}

	/**
	 * Lowers a program.
	 * @param tree the program's file
	 * @param source its text
	 * @param file the origin file, or {@code null} when unknown
	 * @return the top-level forms
	 * @throws LispReadException at the first construct the subset does not have and at
	 * the first error a compiler reports that the lowering depends on
	 */
	static List<LispVal> lower(CompilationUnit tree, String source, @Nullable String file) {
		Source program = new Source(file, source);
		if (tree.classes().isEmpty()) {
			throw JavaClasses.error(program, 0, "the file declares no class");
		}
		JavaClasses classes = new JavaClasses(JavaIntrinsics.LIBRARY);
		List<JavaClass> declared = classes.declare(tree, program);
		SourceProvenance.Unit provenance = SourceProvenance.unit(file, source);
		while (true) {
			int size = classes.size();
			JavaLowering lowering = new JavaLowering(classes, declared, program);
			lowering.provenance = provenance;
			List<LispVal> forms = lowering.program();
			if (classes.size() == size) {
				return forms;
			}
		}
	}

	// ---- errors and positions

	private LispReadException error(int pos, String message) {
		return JavaClasses.error(this.unit.source(), pos, message);
	}

	@Override
	public LispReadException unsupported(int pos, String what) {
		return JavaClasses.unsupported(this.unit.source(), pos, what);
	}

	/**
	 * A form located at a node's position, as a reader locates what it reads: recorded in
	 * the compile path's table, or for the interpreter answered as a located copy -- so
	 * the caller uses the value returned. Only the program's own file is located.
	 */
	private LispVal located(LispVal form, int pos) {
		Source source = this.unit.source();
		if (source != this.program || source.file() == null || !(form instanceof LispCons cons)
				|| form instanceof LocatedCons) {
			return form;
		}
		if (this.provenance != null) {
			SourceProvenance.record(cons, this.provenance, pos);
			return form;
		}
		if (this.lineStarts == null) {
			List<Integer> starts = new ArrayList<>(List.of(0));
			for (int i = 0; i < source.text().length(); i++) {
				if (source.text().charAt(i) == '\n') {
					starts.add(i + 1);
				}
			}
			this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
		}
		int found = java.util.Arrays.binarySearch(this.lineStarts, pos);
		return new LocatedCons(cons.car(), cons.cdr(), source.file(), found >= 0 ? found + 1 : -found - 1);
	}

	private LispSymbol temporary() {
		return new LispSymbol("%t" + (this.temporaries++));
	}

	// ---- the program

	private List<LispVal> program() {
		List<JavaClass> all = List.copyOf(this.classes.all());
		List<LispVal> structures = new ArrayList<>();
		Set<JavaClass> defined = new HashSet<>();
		for (JavaClass declaration : all) {
			structure(declaration, defined, structures);
		}
		for (JavaClass declaration : all) {
			if (!declaration.intrinsic) {
				implementations(declaration);
				lowerClass(declaration);
			}
		}
		LispVal entry = entry();
		List<LispVal> forms = new ArrayList<>(structures);
		forms.addAll(this.variables);
		forms.addAll(this.functions);
		forms.addAll(this.dispatchers.values());
		forms.addAll(generatedFunctions());
		forms.addAll(this.initializers);
		forms.add(entry);
		return List.copyOf(forms);
	}

	private void structure(JavaClass declaration, Set<JavaClass> defined, List<LispVal> out) {
		if (declaration.intrinsic || declaration.isInterface || !defined.add(declaration)) {
			return;
		}
		List<LispVal> header = new ArrayList<>();
		header.add(JavaNames.structure(declaration));
		if (declaration.superclass != null) {
			structure(declaration.superclass, defined, out);
			header.add(list(new LispSymbol(":INCLUDE"), JavaNames.structure(declaration.superclass)));
		}
		header.add(list(new LispSymbol(":CONC-NAME"), JavaNames.accessorPrefix(declaration)));
		header.add(list(new LispSymbol(":CONSTRUCTOR"), JavaNames.allocator(declaration), NIL));
		header.add(list(new LispSymbol(":PREDICATE"), JavaNames.predicate(declaration)));
		header.add(list(new LispSymbol(":COPIER"), NIL));
		List<LispVal> structure = new ArrayList<>();
		structure.add(cl("defstruct"));
		structure.add(list(header));
		for (Field field : declaration.fields) {
			if (field.isStatic) {
				continue;
			}
			Field hidden = declaration.superclass == null ? null : field(declaration.superclass, field.name);
			if (hidden != null && !hidden.isStatic) {
				// a structure has one slot of a name
				throw JavaClasses.unsupported(declaration.source, Objects.requireNonNull(field.declaration).pos(),
						"a field hiding " + hidden.owner.simpleName() + "." + field.name);
			}
			structure.add(list(JavaNames.slot(field.name), defaultValue(field.type)));
		}
		out.add(list(structure));
	}

	private static LispVal defaultValue(JavaType type) {
		if (type == Primitive.DOUBLE) {
			return new LispDouble(0.0);
		}
		return type.isIntegral() ? integer(0) : NIL;
	}

	/** A class that can be instantiated implements every abstract method above it. */
	private void implementations(JavaClass declaration) {
		if (declaration.isAbstract) {
			return;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		for (JavaClass type : above) {
			for (Method method : type.methods) {
				if (method.isAbstract && implementation(declaration, method.signature()) == null) {
					throw JavaClasses.error(declaration.source, Objects.requireNonNull(declaration.declaration).pos(),
							declaration.simpleName() + " is not abstract and does not override abstract method "
									+ method.signature() + " in " + type.simpleName());
				}
			}
		}
	}

	private static void supertypes(JavaClass declaration, Set<JavaClass> into) {
		if (!into.add(declaration)) {
			return;
		}
		if (declaration.superclass != null) {
			supertypes(declaration.superclass, into);
		}
		for (JavaClass implemented : declaration.interfaces) {
			supertypes(implemented, into);
		}
	}

	private void lowerClass(JavaClass declaration) {
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		for (Method method : declaration.methods) {
			if (method.isConstructor()) {
				constructor(method);
			}
			else if (!method.isAbstract) {
				method(method);
			}
		}
		for (Field field : declaration.fields) {
			if (!field.isStatic) {
				continue;
			}
			LispSymbol global = JavaNames.global(field);
			this.variables.add(list(cl("defvar"), global));
			JavaSyntax.Field written = Objects.requireNonNull(field.declaration);
			LispVal value = defaultValue(field.type);
			Expression initializer = written.initializer();
			if (initializer != null) {
				value = inContext(declaration, true, () -> {
					this.initializing = field;
					try {
						return assign(expression(initializer, field.type), field.type, written.pos());
					}
					finally {
						this.initializing = null;
					}
				});
			}
			this.initializers.add(list(cl("setq"), global, value));
		}
	}

	/** Runs a lowering with no local in scope, in a class of the program. */
	private <R> R inContext(JavaClass declaration, boolean isStatic, Supplier<R> lowering) {
		JavaClass savedClass = this.currentClass;
		JavaClasses.Unit savedUnit = this.unit;
		boolean savedStatic = this.staticContext;
		Deque<Map<String, Local>> savedScopes = this.scopes;
		Field savedInitializing = this.initializing;
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		this.staticContext = isStatic;
		this.scopes = new ArrayDeque<>();
		this.scopes.push(new HashMap<>());
		this.initializing = null;
		try {
			return lowering.get();
		}
		finally {
			this.currentClass = savedClass;
			this.unit = savedUnit;
			this.staticContext = savedStatic;
			this.scopes = savedScopes;
			this.initializing = savedInitializing;
		}
	}

	private List<LispSymbol> bindParameters(Method method) {
		List<LispSymbol> symbols = new ArrayList<>();
		JavaSyntax.Method written = method.declaration;
		if (written == null) {
			return symbols;
		}
		for (int i = 0; i < written.parameters().size(); i++) {
			Parameter parameter = written.parameters().get(i);
			symbols.add(bind(parameter.name(), method.parameters.get(i), parameter.pos()).symbol());
		}
		return symbols;
	}

	private void method(Method method) {
		JavaSyntax.Method written = Objects.requireNonNull(method.declaration);
		this.staticContext = method.isStatic;
		this.returnType = method.returnType;
		this.scopes.push(new HashMap<>());
		List<LispVal> parameters = new ArrayList<>();
		if (!method.isStatic) {
			parameters.add(THIS);
		}
		parameters.addAll(bindParameters(method));
		LispVal body = functionBody(Objects.requireNonNull(written.body()));
		this.scopes.pop();
		this.functions.add(list(cl("defun"), JavaNames.function(method), list(parameters), body));
	}

	/**
	 * Whether a constructor does nothing: no parameter, no statement, no field of its
	 * class initialized, and the same above it. Such a constructor is never called.
	 */
	private static boolean isTrivial(Method constructor) {
		JavaSyntax.Method written = constructor.declaration;
		if (!constructor.parameters.isEmpty()
				|| (written != null && !Objects.requireNonNull(written.body()).statements().isEmpty())) {
			return false;
		}
		JavaClass owner = constructor.owner;
		for (Field field : owner.fields) {
			if (!field.isStatic && Objects.requireNonNull(field.declaration).initializer() != null) {
				return false;
			}
		}
		if (owner.superclass == null) {
			return true;
		}
		for (Method above : owner.superclass.methods) {
			if (above.isConstructor() && above.parameters.isEmpty()) {
				return isTrivial(above);
			}
		}
		return false;
	}

	private void constructor(Method constructor) {
		JavaClass owner = constructor.owner;
		JavaSyntax.Method written = constructor.declaration;
		int pos = written != null ? written.pos() : Objects.requireNonNull(owner.declaration).pos();
		this.staticContext = false;
		this.returnType = Primitive.VOID;
		this.scopes.push(new HashMap<>());
		List<LispSymbol> parameters = bindParameters(constructor);
		List<Statement> statements = written == null ? List.of() : Objects.requireNonNull(written.body()).statements();
		ConstructorCall explicit = !statements.isEmpty() && statements.get(0) instanceof ExpressionStatement first
				&& first.expression() instanceof ConstructorCall call ? call : null;
		List<LispVal> forms = new ArrayList<>();
		if (explicit != null) {
			JavaClass target = explicit.onSuper() ? owner.superclass : owner;
			if (target == null) {
				if (!explicit.arguments().isEmpty()) {
					throw error(explicit.pos(), "constructor Object in class Object cannot be applied to given types");
				}
			}
			else {
				forms.add(constructorCall(target, explicit.arguments(), explicit.pos()));
			}
		}
		else if (owner.superclass != null) {
			forms.add(constructorCall(owner.superclass, List.of(), pos));
		}
		forms.removeIf(form -> form == NIL);
		if (explicit == null || explicit.onSuper()) {
			for (Field field : owner.fields) {
				JavaSyntax.Field declaration = Objects.requireNonNull(field.declaration);
				Expression initializer = declaration.initializer();
				if (!field.isStatic && initializer != null) {
					LispVal value = inContext(owner, false,
							() -> assign(expression(initializer, field.type), field.type, declaration.pos()));
					forms.add(call("setf", list(JavaNames.accessor(field), THIS), value));
				}
			}
		}
		Frame frame = new Frame();
		this.frames.push(frame);
		this.loops.clear();
		forms.addAll(sequence(statements, explicit == null ? 0 : 1, false));
		this.frames.pop();
		this.scopes.pop();
		boolean trivial = isTrivial(constructor);
		LispVal instance = list(JavaNames.allocator(owner));
		List<LispVal> arguments = new ArrayList<>(parameters);
		if (!trivial) {
			List<LispVal> all = new ArrayList<>();
			all.add(THIS);
			all.addAll(parameters);
			LispVal body = frame.exits ? form(cl("block"), prepend(FUNCTION_BLOCK, forms)) : progn(forms);
			this.functions.add(list(cl("defun"), JavaNames.function(constructor), list(all), body));
			instance = JavaForms.let(THIS, instance, List.of(form(JavaNames.function(constructor), all), THIS));
		}
		if (!owner.isAbstract) {
			this.functions.add(list(cl("defun"), JavaNames.creator(constructor), list(arguments), instance));
		}
	}

	private static List<LispVal> prepend(LispVal first, List<LispVal> rest) {
		List<LispVal> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	/**
	 * {@code this(..)} or {@code super(..)}: {@code nil} when the constructor is trivial.
	 */
	private LispVal constructorCall(JavaClass target, List<Expression> arguments, int pos) {
		Resolved resolved = resolve(constructors(target), List.of(), arguments, "constructor " + target.simpleName(),
				target, pos);
		if (isTrivial(resolved.method)) {
			return NIL;
		}
		return located(form(JavaNames.function(resolved.method), prepend(THIS, resolved.forms())), pos);
	}

	private LispVal functionBody(Block body) {
		Frame frame = new Frame();
		this.frames.push(frame);
		Deque<Loop> savedLoops = new ArrayDeque<>(this.loops);
		this.loops.clear();
		int savedFinally = this.finallyDepth;
		this.finallyDepth = 0;
		this.scopes.push(new HashMap<>());
		List<LispVal> forms = sequence(body.statements(), 0, true);
		this.scopes.pop();
		this.finallyDepth = savedFinally;
		this.loops.addAll(savedLoops);
		this.frames.pop();
		return frame.exits ? form(cl("block"), prepend(FUNCTION_BLOCK, forms)) : progn(forms);
	}

	/**
	 * The call that starts the program, as the launch protocol picks it (measured against
	 * a JDK 25's source launcher): the first class of the file when it has a
	 * {@code main}, else the class named as the file and no other; in it
	 * {@code main(String[])} before {@code main()}, declared or inherited; an instance
	 * {@code main} called on an instance of the class declaring it, made by its
	 * constructor of no argument.
	 */
	private LispVal entry() {
		JavaClass first = this.declared.get(0);
		Method main = mainOf(first);
		JavaClass launched = first;
		if (main == null && this.program.file() != null) {
			String file = this.program.file().replace('\\', '/');
			String name = file.substring(file.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "");
			for (JavaClass declaration : this.declared) {
				if (declaration.simpleName().equals(name)) {
					launched = declaration;
					main = mainOf(declaration);
				}
			}
		}
		if (main == null) {
			throw JavaClasses.error(this.program, Objects.requireNonNull(first.declaration).pos(),
					"no main method found in " + first.simpleName()
							+ ": the program starts at the first class of the file or the class named as the file");
		}
		// an inherited instance main runs on an instance of the class declaring it
		JavaClass started = main.owner;
		this.currentClass = started;
		this.unit = this.classes.unit(started);
		int at = Objects.requireNonNull(launched.declaration).pos();
		List<LispVal> arguments = new ArrayList<>();
		if (!main.isStatic) {
			if (started.isAbstract) {
				throw JavaClasses.error(this.program, at,
						started.simpleName() + " is abstract; cannot be instantiated");
			}
			Method constructor = constructors(started).stream()
				.filter(candidate -> candidate.parameters.isEmpty())
				.findFirst()
				.orElseThrow(() -> JavaClasses.error(this.program, at,
						"an instance main needs a constructor of no argument in " + started.simpleName()));
			arguments.add(list(JavaNames.creator(constructor)));
		}
		if (!main.parameters.isEmpty()) {
			arguments.add(call("vector"));
		}
		LispVal start = located(form(JavaNames.function(main), arguments),
				Objects.requireNonNull(main.declaration).pos());
		// A program that throws starts inside an unwind-protect protecting nothing: a
		// wasm module holding one reports the condition nothing caught, as the
		// interpreter and the JVM always do.
		return this.raises ? list(cl("unwind-protect"), start, NIL) : start;
	}

	private @Nullable Method mainOf(JavaClass declaration) {
		ArrayType strings = new ArrayType(this.stringType);
		Method found = null;
		for (JavaClass type = declaration; type != null && found == null; type = type.superclass) {
			for (Method method : type.methods) {
				if (method.name.equals("main") && method.returnType == Primitive.VOID && !isPrivate(method)
						&& (method.parameters.isEmpty() || method.parameters.equals(List.of(strings)))
						&& (found == null || !method.parameters.isEmpty())) {
					found = method;
				}
			}
		}
		return found;
	}

	// ---- the functions made for the whole program

	private List<LispVal> generatedFunctions() {
		List<LispVal> forms = new ArrayList<>();
		List<JavaClass> deepestFirst = this.classes.all()
			.stream()
			.filter(declaration -> !declaration.intrinsic && !declaration.isInterface)
			.sorted(Comparator.comparingInt(JavaClass::depth).reversed())
			.toList();
		for (Map.Entry<JavaClass, Integer> test : this.interfaceTests.entrySet()) {
			forms.add(interfaceTest(test.getKey(), test.getValue()));
		}
		if (this.generated.contains(OBJECT_STRING)) {
			forms.add(objectFunction(OBJECT_STRING, "toString()", List.of(), deepestFirst,
					runtimeCall("to-string", RECEIVER, function(OBJECT_STRING))));
		}
		if (this.generated.contains(OBJECT_EQUALS)) {
			LispSymbol other = new LispSymbol("%a0");
			forms.add(objectFunction(OBJECT_EQUALS, "equals(Object)", List.of(other), deepestFirst,
					runtimeCall("equals", RECEIVER, other)));
		}
		if (this.generated.contains(OBJECT_HASH)) {
			forms.add(objectFunction(OBJECT_HASH, "hashCode()", List.of(), deepestFirst,
					runtimeCall("hash-code", RECEIVER)));
		}
		for (LispSymbol name : List.of(CLASS_NAME, SIMPLE_NAME)) {
			if (!this.generated.contains(name)) {
				continue;
			}
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			for (JavaClass declaration : deepestFirst) {
				if (!declaration.isAbstract) {
					arms.add(list(list(JavaNames.predicate(declaration), RECEIVER),
							string(name == CLASS_NAME ? declaration.name : declaration.simpleName())));
				}
			}
			arms.add(list(T, runtimeCall("class-name", RECEIVER, name == CLASS_NAME ? T : NIL)));
			forms.add(list(cl("defun"), name, list(RECEIVER), list(arms)));
		}
		if (this.generated.contains(LISP_ERROR)) {
			LispSymbol condition = new LispSymbol("%c");
			forms.add(list(cl("defun"), LISP_ERROR, list(condition),
					call("if", runtimeCall("division-by-zero-p", condition),
							list(creator("java.lang.ArithmeticException"), string("/ by zero")),
							list(creator("java.lang.RuntimeException"), runtimeCall("condition-text", condition)))));
		}
		return forms;
	}

	/** The function making a library exception of one message. */
	private LispSymbol creator(String exception) {
		for (Method constructor : constructors(this.classes.library(exception))) {
			if (constructor.parameters.equals(List.of(this.stringType))) {
				return JavaNames.creator(constructor);
			}
		}
		throw new IllegalStateException(exception + " has no constructor of a message");
	}

	/**
	 * One of {@code Object}'s methods over any value: the classes of the program
	 * declaring it, deepest first, then the run-time library's answer for every other
	 * value.
	 */
	private LispVal objectFunction(LispSymbol name, String signature, List<LispSymbol> parameters,
			List<JavaClass> deepestFirst, LispVal otherwise) {
		List<LispVal> all = new ArrayList<>();
		all.add(RECEIVER);
		all.addAll(parameters);
		List<LispVal> arms = new ArrayList<>();
		arms.add(cl("cond"));
		for (JavaClass declaration : deepestFirst) {
			for (Method method : declaration.methods) {
				if (!method.isStatic && !method.isAbstract && method.signature().equals(signature)) {
					arms.add(list(list(JavaNames.predicate(declaration), RECEIVER),
							form(JavaNames.function(method), all)));
				}
			}
		}
		arms.add(list(T, otherwise));
		return list(cl("defun"), name, list(all), list(arms));
	}

	/**
	 * {@code instanceof} of an interface: the classes implementing it, and a function
	 * when every lambda of the program implements it. A program some of whose lambdas do
	 * and some do not is refused: a lambda does not tell which interface it was made for.
	 */
	private LispVal interfaceTest(JavaClass declaration, int pos) {
		long implementing = this.lambdaTargets.stream().filter(target -> target.isSubclassOf(declaration)).count();
		if (implementing != 0 && implementing != this.lambdaTargets.size()) {
			throw JavaClasses.unsupported(this.program, pos, "instanceof or a cast to " + declaration.simpleName()
					+ ", which some lambdas of the program implement and others do not,");
		}
		List<LispVal> tests = new ArrayList<>();
		tests.add(cl("or"));
		if (implementing != 0) {
			tests.add(call("functionp", RECEIVER));
		}
		for (JavaClass candidate : this.classes.all()) {
			if (!candidate.intrinsic && !candidate.isInterface && candidate.isSubclassOf(declaration)
					&& (candidate.superclass == null || !candidate.superclass.isSubclassOf(declaration))) {
				tests.add(list(JavaNames.predicate(candidate), RECEIVER));
			}
		}
		return list(cl("defun"), JavaNames.interfaceTest(declaration), list(RECEIVER), list(tests));
	}

	// ---- scopes

	private @Nullable Local local(String name) {
		for (Map<String, Local> scope : this.scopes) {
			Local found = scope.get(name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private Local bind(String name, JavaType type, int pos) {
		if (local(name) != null) {
			throw error(pos, "variable " + name + " is already defined");
		}
		Local bound = new Local(JavaNames.symbol(name), type);
		Objects.requireNonNull(this.scopes.peek()).put(name, bound);
		return bound;
	}

	// ---- the class table

	private static @Nullable Field field(JavaClass declaration, String name) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			for (Field field : type.fields) {
				if (field.name.equals(name)) {
					return field;
				}
			}
		}
		return null;
	}

	private static List<Method> constructors(JavaClass declaration) {
		return declaration.methods.stream().filter(Method::isConstructor).toList();
	}

	/** The methods of a name a class has, an overridden one left out. */
	private List<Method> methods(JavaClass declaration, String name) {
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		above.add(this.objectType.declaration());
		List<Method> found = new ArrayList<>();
		Set<String> signatures = new HashSet<>();
		for (JavaClass type : above) {
			for (Method method : type.methods) {
				if (method.name.equals(name) && !method.isConstructor() && signatures.add(method.signature())) {
					found.add(method);
				}
			}
		}
		return found;
	}

	/** The body a class runs for a signature: its own or the nearest one above it. */
	private static @Nullable Method implementation(JavaClass declaration, String signature) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			for (Method method : type.methods) {
				if (!method.isAbstract && !method.isStatic && method.signature().equals(signature)) {
					return method;
				}
			}
		}
		return null;
	}

	/** The one abstract method of a functional interface, or {@code null}. */
	private static @Nullable Method functional(JavaType type) {
		if (!(type instanceof ClassType target) || !target.declaration().isInterface
				|| target.declaration().intrinsic) {
			return null;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(target.declaration(), above);
		Map<String, Method> found = new LinkedHashMap<>();
		for (JavaClass declaration : above) {
			for (Method method : declaration.methods) {
				if (method.isAbstract) {
					found.putIfAbsent(method.signature(), method);
				}
			}
		}
		return found.size() == 1 ? found.values().iterator().next() : null;
	}

	private static JavaType substitute(JavaType type, List<JavaType> arguments) {
		return switch (type) {
			case Variable variable -> variable.index() < arguments.size() ? arguments.get(variable.index()) : type;
			case ArrayType array -> new ArrayType(substitute(array.element(), arguments));
			default -> type;
		};
	}

	// ---- conversions

	private boolean isSubtype(JavaType from, JavaType to) {
		if (from.equals(to)) {
			return true;
		}
		if (!from.isReference() || !to.isReference() || to == JavaType.Null.NULL) {
			return false;
		}
		if (from == JavaType.Null.NULL || to.equals(this.objectType)) {
			return true;
		}
		if (from instanceof ClassType source && to instanceof ClassType target) {
			return source.declaration().isSubclassOf(target.declaration())
					&& source.arguments().equals(target.arguments());
		}
		if (from instanceof ArrayType source && to instanceof ArrayType target) {
			return source.element().isReference() && target.element().isReference()
					&& isSubtype(source.element(), target.element());
		}
		return false;
	}

	private static int rank(JavaType type) {
		return type == Primitive.CHAR ? 0
				: type == Primitive.INT ? 1 : type == Primitive.LONG ? 2 : type == Primitive.DOUBLE ? 3 : -1;
	}

	/** A primitive value widened to another primitive type. */
	private static Typed widen(Typed value, JavaType to) {
		if (value.type() == to) {
			return value;
		}
		Object constant = value.constant();
		if (to == Primitive.DOUBLE) {
			if (constant != null) {
				return constant(((Number) number(constant)).doubleValue());
			}
			return new Typed(call("float", value.form(), new LispDouble(1.0)), to);
		}
		if (constant != null) {
			long number = ((Number) number(constant)).longValue();
			return to == Primitive.LONG ? constant(number) : constant((int) number);
		}
		return new Typed(value.form(), to);
	}

	/** A constant as a number: a character is its code. */
	private static Object number(Object constant) {
		return constant instanceof Character character ? (Object) (int) character.charValue() : constant;
	}

	private static Typed constant(Object value) {
		return switch (value) {
			case Integer number -> new Typed(integer(number), Primitive.INT, value);
			case Long number -> new Typed(integer(number), Primitive.LONG, value);
			case Double number -> new Typed(new LispDouble(number), Primitive.DOUBLE, value);
			case Boolean truth -> new Typed(truth ? T : NIL, Primitive.BOOLEAN, value);
			case Character character -> new Typed(integer(character), Primitive.CHAR, value);
			default -> throw new IllegalStateException("no constant: " + value);
		};
	}

	private Typed constantString(String value) {
		LispVal form = string(value);
		this.nonNull.add(form);
		return new Typed(form, this.stringType, value);
	}

	private Typed box(Typed value) {
		Primitive primitive = (Primitive) value.type();
		ClassType box = JavaIntrinsics.boxed(primitive, this.classes);
		LispVal form = value.form();
		if (primitive == Primitive.BOOLEAN) {
			form = form == T ? T : form == NIL ? JavaForms.BOXED_FALSE : call("if", form, T, JavaForms.BOXED_FALSE);
		}
		else if (primitive == Primitive.CHAR) {
			form = call("code-char", form);
		}
		return new Typed(form, box);
	}

	/** A value of a box type as the primitive it holds; any other value as it is. */
	private static Typed unbox(Typed value) {
		Primitive primitive = JavaIntrinsics.unboxed(value.type());
		if (primitive == null) {
			return value;
		}
		LispVal form = value.form();
		if (primitive == Primitive.BOOLEAN) {
			form = runtimeCall("unbox-boolean", form);
		}
		else if (primitive == Primitive.CHAR) {
			form = call("char-code", form);
		}
		return new Typed(form, primitive);
	}

	/**
	 * A value converted to a type as an assignment or a call converts it, or {@code null}
	 * when it cannot be.
	 * @param boxing whether a boxing or an unboxing may be part of it
	 * @param narrowing whether an {@code int} constant may become a {@code char}
	 */
	private @Nullable LispVal conversion(Typed value, JavaType to, boolean boxing, boolean narrowing) {
		JavaType from = value.type();
		if (from.equals(to)) {
			return value.form();
		}
		if (from.isPrimitive() && to.isPrimitive()) {
			if (rank(from) >= 0 && rank(from) < rank(to)) {
				return widen(value, to).form();
			}
			if (narrowing && to == Primitive.CHAR && value.constant() instanceof Integer number && number >= 0
					&& number <= Character.MAX_VALUE) {
				return value.form();
			}
			return null;
		}
		if (from.isPrimitive() && to.isReference()) {
			if (!boxing) {
				return null;
			}
			Typed boxed = box(value);
			return isSubtype(boxed.type(), to) ? boxed.form() : null;
		}
		if (to.isPrimitive()) {
			Typed unboxed = unbox(value);
			if (!boxing || unboxed == value) {
				return null;
			}
			return conversion(unboxed, to, false, false);
		}
		return isSubtype(from, to) ? value.form() : null;
	}

	/** A value as its target takes it in an assignment, a return or an initializer. */
	private LispVal assign(Typed value, JavaType to, int pos) {
		LispVal form = conversion(value, to, true, true);
		if (form == null) {
			throw error(pos, "incompatible types: " + value.type() + " cannot be converted to " + to);
		}
		return form;
	}

	private Typed condition(Expression expression) {
		Typed value = unbox(expression(expression, null));
		if (value.type() != Primitive.BOOLEAN) {
			throw error(expression.pos(), "incompatible types: " + value.type() + " cannot be converted to boolean");
		}
		return value;
	}

	/** A value as text, as string concatenation and {@code String.valueOf} make it. */
	@Override
	public LispVal stringOf(Typed value, int pos) {
		JavaType type = value.type();
		LispVal form = value.form();
		Object constant = value.constant();
		if (constant != null) {
			return constantString(String.valueOf(constant)).form();
		}
		LispVal text;
		if (type.equals(this.stringType)) {
			return this.nonNull.contains(form) ? form : call("or", form, string("null"));
		}
		else if (type == Primitive.INT || type == Primitive.LONG) {
			text = call("%decimal-piece", form);
		}
		else if (type == Primitive.CHAR) {
			text = runtimeCall("char-string", form);
		}
		else if (type == Primitive.BOOLEAN) {
			text = call("if", form, string("true"), string("false"));
		}
		else if (type == Primitive.DOUBLE) {
			text = runtimeCall("double-string", form);
		}
		else if (type == JavaType.Null.NULL) {
			text = string("null");
		}
		else if (type == Primitive.VOID) {
			throw error(pos, "'void' type not allowed here");
		}
		else if (JavaIntrinsics.unboxed(type) != null || type instanceof ArrayType || (type instanceof ClassType builder
				&& builder.declaration().name.equals("java.lang.StringBuilder"))) {
			text = runtimeCall("to-string", form, NIL);
		}
		else {
			text = objectString(value, pos);
		}
		this.nonNull.add(text);
		return text;
	}

	private LispVal objectString(Typed value, int pos) {
		if (value.type() instanceof ClassType collection && collection.declaration().name.endsWith("Map")
				&& collection.declaration().intrinsic) {
			throw unsupported(pos, "the text of a map");
		}
		this.generated.add(OBJECT_STRING);
		return list(OBJECT_STRING, value.form());
	}

	/**
	 * A call of one of {@code Object}'s methods no class of the receiver's type declares.
	 */
	@Override
	public LispVal objectMethod(String signature, Typed receiver, Invocation call) {
		JavaType type = receiver.type();
		boolean plain = JavaIntrinsics.unboxed(type) != null || type instanceof ArrayType
				|| (type instanceof ClassType builder && builder.declaration().name.equals("java.lang.StringBuilder"));
		if (type instanceof ClassType collection && collection.declaration().typeParameters > 0
				&& !signature.equals("toString()")) {
			throw unsupported(call.pos(), collection.declaration().simpleName() + "." + signature);
		}
		switch (signature) {
			case "toString()":
				return plain ? runtimeCall("to-string", receiver.form(), NIL) : objectString(receiver, call.pos());
			case "equals(Object)":
				if (plain) {
					return runtimeCall("equals", receiver.form(), call.argument(0));
				}
				this.generated.add(OBJECT_EQUALS);
				return list(OBJECT_EQUALS, receiver.form(), call.argument(0));
			default:
				if (plain) {
					return runtimeCall("hash-code", receiver.form());
				}
				this.generated.add(OBJECT_HASH);
				return list(OBJECT_HASH, receiver.form());
		}
	}

	// ---- expressions

	private JavaType type(TypeName written) {
		return this.classes.type(written, this.unit);
	}

	private Typed thisValue(int pos) {
		if (this.staticContext) {
			throw error(pos, "non-static variable this cannot be referenced from a static context");
		}
		return new Typed(THIS, new ClassType(this.currentClass, List.of()));
	}

	/** The dotted name an expression spells when it is names and selections only. */
	private static @Nullable String dotted(Expression expression) {
		if (expression instanceof Name name) {
			return name.identifier();
		}
		if (expression instanceof Select select) {
			String target = dotted(select.target());
			return target == null ? null : target + "." + select.name();
		}
		return null;
	}

	/** The class an expression names, when it names one and no variable. */
	private @Nullable JavaClass classNamed(Expression expression) {
		String name = dotted(expression);
		if (name == null) {
			return null;
		}
		String first = name.indexOf('.') < 0 ? name : name.substring(0, name.indexOf('.'));
		if (local(first) != null || field(this.currentClass, first) != null) {
			return null;
		}
		return this.classes.find(name, this.unit);
	}

	private Typed expression(Expression expression, @Nullable JavaType expected) {
		return expression(expression, expected, false);
	}

	/**
	 * Lowers an expression.
	 * @param expected the type its context gives it, which a lambda, a diamond and an
	 * array initializer take theirs from, or {@code null}
	 * @param discard whether its value is not used
	 */
	private Typed expression(Expression expression, @Nullable JavaType expected, boolean discard) {
		switch (expression) {
			case IntLiteral literal:
				return literal.isLong() ? constant(literal.value()) : constant((int) literal.value());
			case DoubleLiteral literal:
				return constant(literal.value());
			case CharLiteral literal:
				return constant((char) literal.value());
			case StringLiteral literal:
				return constantString(literal.value());
			case BooleanLiteral literal:
				return constant(literal.value());
			case NullLiteral literal:
				return new Typed(NIL, JavaType.Null.NULL);
			case This self:
				return thisValue(self.pos());
			case Name name: {
				Local local = local(name.identifier());
				if (local != null) {
					return new Typed(local.symbol(), local.type());
				}
				Field field = field(this.currentClass, name.identifier());
				if (field == null) {
					throw error(name.pos(), "cannot find symbol: variable " + name.identifier());
				}
				return fieldRead(field, field.isStatic ? null : thisValue(name.pos()).form(), name.pos());
			}
			case Select select:
				return select(select);
			case Call call:
				return invocation(call, discard);
			case ConstructorCall call:
				throw unsupported(call.pos(), "a statement ahead of this(..) or super(..)");
			case New creation:
				return creation(creation, expected);
			case NewArray creation: {
				ArrayType type = (ArrayType) type(creation.type());
				if (creation.initializer() != null) {
					return arrayInitializer(creation.initializer(), type);
				}
				Typed length = unbox(expression(Objects.requireNonNull(creation.length()), null));
				if (length.type() != Primitive.INT && length.type() != Primitive.CHAR) {
					throw error(creation.pos(), "incompatible types: " + length.type() + " cannot be converted to int");
				}
				List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), length.form()));
				made.addAll(elementType(type.element()));
				made.add(new LispSymbol(":INITIAL-ELEMENT"));
				made.add(defaultValue(type.element()));
				return new Typed(list(made), type);
			}
			case ArrayInitializer initializer:
				if (!(expected instanceof ArrayType type)) {
					throw error(initializer.pos(), "illegal initializer for " + expected);
				}
				return arrayInitializer(initializer, type);
			case Index index: {
				Place place = place(index, false);
				return new Typed(place.read(), place.type());
			}
			case Unary unary:
				return unary(unary, discard);
			case Binary binary:
				return binary(binary);
			case Assign assignment:
				return assignment(assignment);
			case Conditional conditional:
				return conditional(conditional, expected);
			case Cast cast:
				return cast(cast);
			case InstanceOf test:
				return instanceOf(test);
			case Lambda lambda:
				return lambda(lambda, expected);
		}
	}

	private static List<LispVal> elementType(JavaType element) {
		if (element == Primitive.INT) {
			return List.of(new LispSymbol(":ELEMENT-TYPE"), quote(list(cl("signed-byte"), integer(32))));
		}
		if (element == Primitive.DOUBLE) {
			return List.of(new LispSymbol(":ELEMENT-TYPE"), quote(cl("double-float")));
		}
		return List.of();
	}

	private Typed arrayInitializer(ArrayInitializer initializer, ArrayType type) {
		List<LispVal> elements = new ArrayList<>();
		for (Expression element : initializer.elements()) {
			elements.add(assign(expression(element, type.element()), type.element(), element.pos()));
		}
		List<LispVal> typed = elementType(type.element());
		if (typed.isEmpty()) {
			return new Typed(form(cl("vector"), elements), type);
		}
		List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), integer(elements.size())));
		made.addAll(typed);
		made.add(new LispSymbol(":INITIAL-CONTENTS"));
		made.add(form(cl("list"), elements));
		return new Typed(list(made), type);
	}

	private Typed fieldRead(Field field, @Nullable LispVal object, int pos) {
		if (field.form != null) {
			Object constant = field.form instanceof LispInteger number
					? (field.type == Primitive.LONG ? (Object) number.value() : (Object) (int) number.value())
					: field.form instanceof LispDouble number ? (Object) number.value() : null;
			return new Typed(field.form, field.type, constant);
		}
		if (!field.isStatic) {
			return new Typed(list(JavaNames.accessor(field), Objects.requireNonNull(object)), field.type);
		}
		Object constant = constantOf(field);
		if (constant != null) {
			return constant instanceof String text ? constantString(text) : constant(constant);
		}
		if (this.initializing != null && (field.owner != this.initializing.owner
				|| field.owner.fields.indexOf(field) >= field.owner.fields.indexOf(this.initializing))) {
			throw unsupported(pos, "a static field initializer reading " + field.owner.simpleName() + "." + field.name
					+ ", which is set by the time it runs only if the class was initialized first,");
		}
		return new Typed(JavaNames.global(field), field.type);
	}

	/**
	 * The value of a constant variable -- a {@code static final} field of a primitive
	 * type or {@code String} whose initializer is a constant expression --, which every
	 * read of it is; {@code null} for any other field.
	 */
	private @Nullable Object constantOf(Field field) {
		JavaSyntax.Field written = field.declaration;
		Expression initializer = written == null ? null : written.initializer();
		if (!field.isStatic || !field.isFinal || initializer == null
				|| !(field.type.isPrimitive() || field.type.equals(this.stringType))) {
			return null;
		}
		Object known = this.constants.get(field);
		if (known == null) {
			// a field read while its own initializer is lowered is no constant
			this.constants.put(field, NOT_CONSTANT);
			known = inContext(field.owner, true, () -> {
				this.initializing = field;
				Typed value = expression(initializer, field.type);
				Object constant = value.constant();
				if (constant == null || conversion(value, field.type, false, true) == null) {
					return NOT_CONSTANT;
				}
				if (field.type.isPrimitive() && value.type() != field.type) {
					constant = field.type == Primitive.CHAR ? (Object) (char) ((Number) number(constant)).intValue()
							: widen(value, field.type).constant();
				}
				return Objects.requireNonNull(constant);
			});
			this.constants.put(field, known);
		}
		return known == NOT_CONSTANT ? null : known;
	}

	private Typed select(Select select) {
		JavaClass named = classNamed(select.target());
		if (named != null) {
			Field field = field(named, select.name());
			if (field == null) {
				throw named.intrinsic ? unsupported(select.pos(), named.simpleName() + "." + select.name())
						: error(select.pos(),
								"cannot find symbol: variable " + select.name() + " in class " + named.simpleName());
			}
			if (!field.isStatic) {
				throw error(select.pos(),
						"non-static variable " + select.name() + " cannot be referenced from a static context");
			}
			return fieldRead(field, null, select.pos());
		}
		Place place = place(select, false);
		return new Typed(place.read(), place.type());
	}

	/**
	 * Where an expression stores: a local, a field, an array element.
	 * @param stable whether the place is read and then written, so that what it is made
	 * of is evaluated once: an operand that is more than a variable is bound first
	 */
	private Place place(Expression expression, boolean stable) {
		List<LispVal> bindings = new ArrayList<>();
		switch (expression) {
			case Name name: {
				Local local = local(name.identifier());
				if (local != null) {
					return new Place(local.type(), local.symbol(), value -> call("setq", local.symbol(), value),
							bindings);
				}
				Field field = field(this.currentClass, name.identifier());
				if (field == null) {
					throw error(name.pos(), "cannot find symbol: variable " + name.identifier());
				}
				return fieldPlace(field, field.isStatic ? null : thisValue(name.pos()).form(), name.pos(), bindings);
			}
			case Select select: {
				JavaClass named = classNamed(select.target());
				if (named != null) {
					Field field = field(named, select.name());
					if (field == null || !field.isStatic) {
						throw error(select.pos(), "cannot find symbol: static variable " + select.name() + " in class "
								+ named.simpleName());
					}
					return fieldPlace(field, null, select.pos(), bindings);
				}
				Typed object = expression(select.target(), null);
				if (object.type() instanceof ArrayType && select.name().equals("length")) {
					return new Place(Primitive.INT, call("length", object.form()), value -> {
						throw error(select.pos(), "cannot assign a value to final variable length");
					}, bindings);
				}
				if (!(object.type() instanceof ClassType type)) {
					throw error(select.pos(), object.type() + " cannot be dereferenced");
				}
				Field field = field(type.declaration(), select.name());
				if (field == null) {
					throw type.declaration().intrinsic
							? unsupported(select.pos(), type.declaration().simpleName() + "." + select.name())
							: error(select.pos(), "cannot find symbol: variable " + select.name() + " in class "
									+ type.declaration().simpleName());
				}
				return fieldPlace(field, field.isStatic ? null : stabilized(object.form(), stable, bindings),
						select.pos(), bindings);
			}
			case Index index: {
				Typed array = expression(index.array(), null);
				if (!(array.type() instanceof ArrayType type)) {
					throw error(index.pos(), "array required, but " + array.type() + " found");
				}
				Typed at = unbox(expression(index.index(), null));
				if (at.type() != Primitive.INT && at.type() != Primitive.CHAR) {
					throw error(index.index().pos(),
							"incompatible types: " + at.type() + " cannot be converted to int");
				}
				LispVal element = call("aref", stabilized(array.form(), stable, bindings),
						stabilized(at.form(), stable, bindings));
				return new Place(type.element(), element, value -> call("setf", element, value), bindings);
			}
			default:
				throw error(expression.pos(), "unexpected type; required: variable, found: value");
		}
	}

	private LispVal stabilized(LispVal form, boolean stable, List<LispVal> bindings) {
		if (!stable || isSimple(form)) {
			return form;
		}
		LispSymbol temporary = temporary();
		bindings.add(list(temporary, form));
		return temporary;
	}

	private Place fieldPlace(Field field, @Nullable LispVal object, int pos, List<LispVal> bindings) {
		Typed read = fieldRead(field, object, pos);
		Function<LispVal, LispVal> write = value -> {
			if (field.form != null || read.constant() != null) {
				throw error(pos, "cannot assign a value to final variable " + field.name);
			}
			return field.isStatic ? call("setq", JavaNames.global(field), value) : call("setf", read.form(), value);
		};
		return new Place(field.type, read.form(), write, bindings);
	}

	private static LispVal bound(List<LispVal> bindings, LispVal body) {
		return bindings.isEmpty() ? body : list(cl("let*"), list(bindings), body);
	}

	private Typed assignment(Assign assignment) {
		Place place = place(assignment.target(), !assignment.operator().equals("="));
		Typed value = expression(assignment.value(), place.type());
		Typed read = new Typed(place.read(), place.type());
		LispVal stored;
		if (assignment.operator().equals("=")) {
			stored = assign(value, place.type(), assignment.pos());
		}
		else {
			// a compound assignment casts its result to the place's type
			stored = castTo(place.type(), operation(assignment.operator(), read, value, assignment.pos()),
					assignment.pos())
				.form();
		}
		return new Typed(bound(place.bindings(), place.write().apply(stored)), place.type());
	}

	private Typed unary(Unary unary, boolean discard) {
		String operator = unary.operator();
		if (operator.equals("++") || operator.equals("--")) {
			Place place = place(unary.operand(), true);
			if (!unbox(new Typed(place.read(), place.type())).type().isNumeric()) {
				throw error(unary.pos(), "bad operand type " + place.type() + " for unary operator '" + operator + "'");
			}
			Typed read = new Typed(place.read(), place.type());
			LispVal next = castTo(place.type(), operation(operator.substring(1), read, constant(1), unary.pos()),
					unary.pos())
				.form();
			LispVal store = place.write().apply(next);
			LispVal form = unary.postfix() && !discard ? call("prog1", place.read(), store) : store;
			return new Typed(bound(place.bindings(), form), place.type());
		}
		Typed operand = unbox(expression(unary.operand(), null));
		JavaType type = operand.type();
		Object constant = operand.constant();
		switch (operator) {
			case "!":
				if (type != Primitive.BOOLEAN) {
					break;
				}
				return constant instanceof Boolean truth ? constant(!truth)
						: new Typed(call("not", operand.form()), type);
			case "+", "-", "~": {
				if (!type.isNumeric() || (operator.equals("~") && !type.isIntegral())) {
					break;
				}
				Typed promoted = type == Primitive.CHAR ? widen(operand, Primitive.INT) : operand;
				if (operator.equals("+")) {
					return promoted;
				}
				if (promoted.constant() instanceof Double number) {
					return constant(-number);
				}
				if (promoted.constant() != null) {
					Object folded = fold(operator.equals("-") ? "-" : "^",
							operator.equals("-") ? zero(promoted.type()) : minusOne(promoted.type()),
							promoted.constant(), promoted.type());
					if (folded != null) {
						return constant(folded);
					}
				}
				return operator.equals("-")
						? new Typed(JavaForms.wrap(promoted.type(), call("-", promoted.form())), promoted.type())
						: new Typed(call("lognot", promoted.form()), promoted.type());
			}
			default:
		}
		throw error(unary.pos(), "bad operand type " + type + " for unary operator '" + operator + "'");
	}

	private static Object zero(JavaType type) {
		return type == Primitive.DOUBLE ? (Object) 0.0 : type == Primitive.LONG ? (Object) 0L : (Object) 0;
	}

	private static Object minusOne(JavaType type) {
		return type == Primitive.LONG ? (Object) (-1L) : (Object) (-1);
	}

	private boolean isString(Typed value) {
		return value.type().equals(this.stringType);
	}

	private Typed binary(Binary binary) {
		String operator = binary.operator();
		if (operator.equals("&&") || operator.equals("||")) {
			Typed left = condition(binary.left());
			Typed right = condition(binary.right());
			if (left.constant() instanceof Boolean a && right.constant() instanceof Boolean b) {
				return constant(operator.equals("&&") ? a && b : a || b);
			}
			return new Typed(call(operator.equals("&&") ? "and" : "or", left.form(), right.form()), Primitive.BOOLEAN);
		}
		return operation(operator, expression(binary.left(), null), expression(binary.right(), null), binary.pos());
	}

	/**
	 * A binary operator over two lowered operands, which a compound assignment shares.
	 */
	private Typed operation(String operator, Typed left, Typed right, int pos) {
		if (operator.equals("+") && (isString(left) || isString(right))) {
			if (left.type() == Primitive.VOID || right.type() == Primitive.VOID) {
				throw error(pos, "'void' type not allowed here");
			}
			if (left.constant() != null && right.constant() != null) {
				return constantString(String.valueOf(left.constant()) + right.constant());
			}
			LispVal joined = call("%string-concat", stringOf(left, pos), stringOf(right, pos));
			this.nonNull.add(joined);
			return new Typed(joined, this.stringType);
		}
		if (operator.equals("==") || operator.equals("!=")) {
			return equality(operator, left, right, pos);
		}
		Typed a = unbox(left);
		Typed b = unbox(right);
		JavaType lt = a.type();
		JavaType rt = b.type();
		boolean logical = lt == Primitive.BOOLEAN && rt == Primitive.BOOLEAN;
		switch (operator) {
			case "<", ">", "<=", ">=": {
				if (!lt.isNumeric() || !rt.isNumeric()) {
					break;
				}
				JavaType promoted = promote(lt, rt);
				a = widen(a, promoted);
				b = widen(b, promoted);
				Object folded = a.constant() != null && b.constant() != null
						? fold(operator, a.constant(), b.constant(), promoted) : null;
				return folded != null ? constant(folded)
						: new Typed(call(operator, a.form(), b.form()), Primitive.BOOLEAN);
			}
			case "&", "|", "^":
				if (logical) {
					if (operator.equals("^")) {
						return new Typed(call("not", call("eq", a.form(), b.form())), Primitive.BOOLEAN);
					}
					// both operands are evaluated: no short circuit
					String connective = operator.equals("&") ? "and" : "or";
					if (isSimple(b.form())) {
						return new Typed(call(connective, a.form(), b.form()), Primitive.BOOLEAN);
					}
					LispSymbol first = temporary();
					LispSymbol second = temporary();
					return new Typed(list(cl("let*"), list(list(first, a.form()), list(second, b.form())),
							call(connective, first, second)), Primitive.BOOLEAN);
				}
				if (!lt.isIntegral() || !rt.isIntegral()) {
					break;
				}
				return arithmetic(operator, a, b, promote(lt, rt));
			case "+", "-", "*", "/", "%":
				if (!lt.isNumeric() || !rt.isNumeric()) {
					break;
				}
				return arithmetic(operator, a, b, promote(lt, rt));
			case "<<", ">>", ">>>": {
				if (!lt.isIntegral() || !rt.isIntegral()) {
					break;
				}
				return shift(operator, lt == Primitive.CHAR ? widen(a, Primitive.INT) : a, b);
			}
			default:
		}
		throw error(pos,
				"bad operand types for binary operator '" + operator + "': " + left.type() + ", " + right.type());
	}

	private static JavaType promote(JavaType left, JavaType right) {
		if (left == Primitive.DOUBLE || right == Primitive.DOUBLE) {
			return Primitive.DOUBLE;
		}
		return left == Primitive.LONG || right == Primitive.LONG ? Primitive.LONG : Primitive.INT;
	}

	private static Typed arithmetic(String operator, Typed left, Typed right, JavaType type) {
		Typed a = widen(left, type);
		Typed b = widen(right, type);
		if (a.constant() != null && b.constant() != null) {
			Object folded = fold(operator, a.constant(), b.constant(), type);
			if (folded != null) {
				return constant(folded);
			}
		}
		LispVal x = a.form();
		LispVal y = b.form();
		LispVal form = switch (operator) {
			case "+", "-", "*" -> JavaForms.wrap(type, call(operator, x, y));
			case "/" -> type == Primitive.DOUBLE ? call("/", x, y) : JavaForms.wrap(type, call("truncate", x, y));
			case "%" -> call("rem", x, y);
			case "&" -> call("logand", x, y);
			case "|" -> call("logior", x, y);
			default -> call("logxor", x, y);
		};
		return new Typed(form, type);
	}

	private static Typed shift(String operator, Typed value, Typed count) {
		JavaType type = value.type();
		int mask = type == Primitive.LONG ? 63 : 31;
		Object by = count.constant() == null ? null : number(count.constant());
		if (value.constant() != null && by != null) {
			Object folded = fold(operator, value.constant(), ((Number) by).intValue(), type);
			if (folded != null) {
				return constant(folded);
			}
		}
		LispVal distance = by != null ? integer(((Number) by).longValue() & mask)
				: call("logand", count.form(), integer(mask));
		LispVal back = by != null ? integer(-(((Number) by).longValue() & mask)) : call("-", distance);
		LispVal form = switch (operator) {
			case "<<" -> JavaForms.wrap(type, call("ash", value.form(), distance));
			case ">>" -> call("ash", value.form(), back);
			default -> JavaForms.wrap(type,
					call("ash", call("logand", value.form(),
							type == Primitive.LONG
									? integer(java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE))
									: integer(0xFFFFFFFFL)),
							back));
		};
		return new Typed(form, type);
	}

	/**
	 * A binary operator over two constants of one promoted type, as the language computes
	 * it, or {@code null} where it raises when the program runs.
	 */
	private static @Nullable Object fold(String operator, Object left, Object right, JavaType type) {
		Number a = (Number) number(left);
		Number b = (Number) number(right);
		if (type == Primitive.DOUBLE) {
			double x = a.doubleValue();
			double y = b.doubleValue();
			return switch (operator) {
				case "+" -> x + y;
				case "-" -> x - y;
				case "*" -> x * y;
				case "<" -> x < y;
				case ">" -> x > y;
				case "<=" -> x <= y;
				case ">=" -> x >= y;
				case "==" -> x == y;
				case "!=" -> x != y;
				// a division is the run time's: it is not exact there yet
				default -> null;
			};
		}
		if (type == Primitive.LONG) {
			long x = a.longValue();
			long y = b.longValue();
			return switch (operator) {
				case "+" -> x + y;
				case "-" -> x - y;
				case "*" -> x * y;
				case "/" -> y == 0 ? null : (Object) (x / y);
				case "%" -> y == 0 ? null : (Object) (x % y);
				case "&" -> x & y;
				case "|" -> x | y;
				case "^" -> x ^ y;
				case "<<" -> x << y;
				case ">>" -> x >> y;
				case ">>>" -> x >>> y;
				case "<" -> x < y;
				case ">" -> x > y;
				case "<=" -> x <= y;
				case ">=" -> x >= y;
				case "==" -> x == y;
				case "!=" -> x != y;
				default -> null;
			};
		}
		int x = a.intValue();
		int y = b.intValue();
		return switch (operator) {
			case "+" -> x + y;
			case "-" -> x - y;
			case "*" -> x * y;
			case "/" -> y == 0 ? null : (Object) (x / y);
			case "%" -> y == 0 ? null : (Object) (x % y);
			case "&" -> x & y;
			case "|" -> x | y;
			case "^" -> x ^ y;
			case "<<" -> x << y;
			case ">>" -> x >> y;
			case ">>>" -> x >>> y;
			case "<" -> x < y;
			case ">" -> x > y;
			case "<=" -> x <= y;
			case ">=" -> x >= y;
			case "==" -> x == y;
			case "!=" -> x != y;
			default -> null;
		};
	}

	private Typed equality(String operator, Typed left, Typed right, int pos) {
		boolean leftBox = JavaIntrinsics.unboxed(left.type()) != null;
		boolean rightBox = JavaIntrinsics.unboxed(right.type()) != null;
		LispVal test;
		if (left.type().isReference() && right.type().isReference()) {
			if (leftBox && rightBox) {
				throw unsupported(pos, operator + " of two boxed values, which compares references,");
			}
			if (isString(left) && isString(right)) {
				throw unsupported(pos, operator + " of two strings, which compares references (equals compares text),");
			}
			if (!isSubtype(left.type(), right.type()) && !isSubtype(right.type(), left.type())
					&& !(isInterface(left.type()) || isInterface(right.type()))) {
				throw error(pos, "incomparable types: " + left.type() + " and " + right.type());
			}
			test = left.type() == JavaType.Null.NULL ? call("null", right.form()) : right.type() == JavaType.Null.NULL
					? call("null", left.form()) : call("eq", left.form(), right.form());
		}
		else {
			Typed a = unbox(left);
			Typed b = unbox(right);
			if (a.type().isNumeric() && b.type().isNumeric()) {
				JavaType promoted = promote(a.type(), b.type());
				a = widen(a, promoted);
				b = widen(b, promoted);
				if (a.constant() != null && b.constant() != null) {
					return constant(Objects.requireNonNull(fold(operator, a.constant(), b.constant(), promoted)));
				}
				test = call("=", a.form(), b.form());
			}
			else if (a.type() == Primitive.BOOLEAN && b.type() == Primitive.BOOLEAN) {
				test = call("eq", a.form(), b.form());
			}
			else {
				throw error(pos, "incomparable types: " + left.type() + " and " + right.type());
			}
		}
		return new Typed(operator.equals("==") ? test : call("not", test), Primitive.BOOLEAN);
	}

	private static boolean isInterface(JavaType type) {
		return type instanceof ClassType declared && declared.declaration().isInterface;
	}

	private Typed conditional(Conditional conditional, @Nullable JavaType expected) {
		Typed test = condition(conditional.condition());
		Typed a = expression(conditional.whenTrue(), expected);
		Typed b = expression(conditional.whenFalse(), expected);
		JavaType type = conditionalType(a, b, expected, conditional.pos());
		LispVal whenTrue = assign(a, type, conditional.whenTrue().pos());
		LispVal whenFalse = assign(b, type, conditional.whenFalse().pos());
		if (test.constant() instanceof Boolean truth && a.constant() != null && b.constant() != null) {
			Typed chosen = truth ? a : b;
			Object constant = Objects.requireNonNull(chosen.constant());
			if (chosen.type().equals(type)) {
				return new Typed(truth ? whenTrue : whenFalse, type, constant);
			}
		}
		LispVal form = call("if", test.form(), whenTrue, whenFalse);
		if (type.equals(this.stringType) && this.nonNull.contains(whenTrue) && this.nonNull.contains(whenFalse)) {
			this.nonNull.add(form);
		}
		return new Typed(form, type);
	}

	private JavaType conditionalType(Typed a, Typed b, @Nullable JavaType expected, int pos) {
		JavaType at = a.type();
		JavaType bt = b.type();
		if (at.equals(bt)) {
			return at;
		}
		JavaType ap = unbox(a).type();
		JavaType bp = unbox(b).type();
		if (ap.isNumeric() && bp.isNumeric()) {
			if (ap == Primitive.CHAR && conversion(b, Primitive.CHAR, false, true) != null) {
				return Primitive.CHAR;
			}
			if (bp == Primitive.CHAR && conversion(a, Primitive.CHAR, false, true) != null) {
				return Primitive.CHAR;
			}
			return ap == bp ? ap : promote(ap, bp);
		}
		if (ap == Primitive.BOOLEAN && bp == Primitive.BOOLEAN) {
			return Primitive.BOOLEAN;
		}
		if (at == JavaType.Null.NULL && bt.isPrimitive()) {
			return JavaIntrinsics.boxed((Primitive) bt, this.classes);
		}
		if (bt == JavaType.Null.NULL && at.isPrimitive()) {
			return JavaIntrinsics.boxed((Primitive) at, this.classes);
		}
		if (at.isReference() && bt.isReference()) {
			if (isSubtype(at, bt)) {
				return bt;
			}
			if (isSubtype(bt, at)) {
				return at;
			}
		}
		if (expected != null && conversion(a, expected, true, false) != null
				&& conversion(b, expected, true, false) != null) {
			return expected;
		}
		throw unsupported(pos, "a conditional over the unrelated types " + at + " and " + bt);
	}

	/**
	 * A value cast to a type: every conversion a cast allows, checked where it can fail.
	 */
	private Typed castTo(JavaType to, Typed value, int pos) {
		JavaType from = value.type();
		if (from.equals(to)) {
			return value;
		}
		if (to.isPrimitive()) {
			Typed operand = unbox(value);
			from = operand.type();
			if (from == to) {
				return operand;
			}
			if (!from.isNumeric() || !to.isNumeric()) {
				throw from.isReference() ? unsupported(pos, "a cast from " + from + " to " + to)
						: error(pos, "incompatible types: " + from + " cannot be converted to " + to);
			}
			if (rank(from) < rank(to)) {
				return widen(operand, to);
			}
			Object constant = operand.constant() == null ? null : number(operand.constant());
			if (constant != null) {
				Number number = (Number) constant;
				return switch ((Primitive) to) {
					case INT ->
						constant(from == Primitive.DOUBLE ? (int) number.doubleValue() : (int) number.longValue());
					case LONG -> constant((long) number.doubleValue());
					default ->
						constant(from == Primitive.DOUBLE ? (char) number.doubleValue() : (char) number.longValue());
				};
			}
			LispVal form = operand.form();
			if (from == Primitive.DOUBLE) {
				form = runtimeCall(to == Primitive.LONG ? "double-to-long" : "double-to-int", form);
			}
			if (to == Primitive.INT && from == Primitive.LONG) {
				form = JavaForms.wrap(Primitive.INT, form);
			}
			else if (to == Primitive.CHAR) {
				form = call("logand", form, integer(0xFFFF));
			}
			return new Typed(form, to);
		}
		LispVal converted = conversion(value, to, true, false);
		if (converted != null) {
			return new Typed(converted, to);
		}
		if (!from.isReference()) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		LispSymbol checked = temporary();
		LispVal test = instanceTest(checked, from, to, pos, "a cast");
		this.classes.library("java.lang.ClassCastException");
		LispVal failure = raise(
				list(JavaNames.creator(constructors(this.classes.library("java.lang.ClassCastException")).stream()
					.filter(constructor -> constructor.parameters.isEmpty())
					.findFirst()
					.orElseThrow())));
		return new Typed(JavaForms.let(checked, value.form(),
				List.of(call("if", call("or", call("null", checked), test), checked, failure))), to);
	}

	private Typed cast(Cast cast) {
		JavaType to = type(cast.type());
		return castTo(to, expression(cast.operand(), to), cast.pos());
	}

	/**
	 * The test that a value of one static type is an instance of another type: a
	 * structure predicate, an interface's test, a library class's own.
	 */
	private LispVal instanceTest(LispVal value, JavaType from, JavaType to, int pos, String what) {
		if (!(to instanceof ClassType target) || !target.arguments().isEmpty()) {
			throw unsupported(pos, what + " to " + to);
		}
		JavaClass declaration = target.declaration();
		if (from instanceof ClassType source && !source.declaration().isInterface && !declaration.isInterface
				&& !source.equals(this.objectType) && !declaration.isSubclassOf(source.declaration())) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		if (from instanceof ArrayType) {
			throw error(pos, "incompatible types: " + from + " cannot be converted to " + to);
		}
		if (declaration.intrinsic) {
			LispSymbol predicate = JavaIntrinsics.predicate(declaration);
			if (predicate == null) {
				throw unsupported(pos, what + " to " + to);
			}
			return list(predicate, value);
		}
		if (declaration.isInterface) {
			this.interfaceTests.putIfAbsent(declaration, pos);
			return list(JavaNames.interfaceTest(declaration), value);
		}
		return list(JavaNames.predicate(declaration), value);
	}

	private Typed instanceOf(InstanceOf test) {
		Typed operand = expression(test.operand(), null);
		if (!operand.type().isReference()) {
			throw error(test.pos(), "unexpected type; required: reference, found: " + operand.type());
		}
		JavaType to = type(test.type());
		if (operand.type() != JavaType.Null.NULL && isSubtype(operand.type(), to)) {
			return new Typed(call("not", call("null", operand.form())), Primitive.BOOLEAN);
		}
		return new Typed(instanceTest(operand.form(), operand.type(), to, test.pos(), "instanceof"), Primitive.BOOLEAN);
	}

	private Typed lambda(Lambda lambda, @Nullable JavaType expected) {
		Method method = expected == null ? null : functional(expected);
		if (method == null) {
			throw expected instanceof ClassType target && target.declaration().intrinsic
					? unsupported(lambda.pos(), "a lambda as a " + target.declaration().simpleName())
					: error(lambda.pos(), "a lambda needs a functional interface of the program as its target"
							+ (expected == null ? "" : ", not " + expected));
		}
		if (this.initializing != null) {
			throw unsupported(lambda.pos(), "a static field initializer that runs the program's code");
		}
		if (method.parameters.size() != lambda.parameters().size()) {
			throw error(lambda.pos(), "incompatible parameter types in lambda expression");
		}
		this.lambdaTargets.add(((ClassType) Objects.requireNonNull(expected)).declaration());
		this.scopes.push(new HashMap<>());
		List<LispVal> parameters = new ArrayList<>();
		for (int i = 0; i < lambda.parameters().size(); i++) {
			Parameter parameter = lambda.parameters().get(i);
			JavaType type = method.parameters.get(i);
			if (parameter.type() != null && !type(parameter.type()).equals(type)) {
				throw error(parameter.pos(), "incompatible parameter types in lambda expression");
			}
			parameters.add(bind(parameter.name(), type, parameter.pos()).symbol());
		}
		JavaType savedReturn = this.returnType;
		this.returnType = method.returnType;
		LispVal body;
		if (lambda.expression() != null) {
			Typed value = expression(lambda.expression(), method.returnType, method.returnType == Primitive.VOID);
			body = method.returnType == Primitive.VOID ? value.form()
					: assign(value, method.returnType, lambda.expression().pos());
		}
		else {
			body = functionBody(Objects.requireNonNull(lambda.block()));
		}
		this.returnType = savedReturn;
		this.scopes.pop();
		return new Typed(list(cl("lambda"), list(parameters), body), expected);
	}

	// ---- calls

	/**
	 * A method chosen for a call, and the call's arguments as its parameters take them.
	 */
	private record Resolved(Method method, List<Typed> arguments, JavaType returnType) {

		List<LispVal> forms() {
			return this.arguments.stream().map(Typed::form).toList();
		}

	}

	/** Whether an argument waits for its parameter's type before it can be lowered. */
	private static boolean needsTarget(Expression argument) {
		return argument instanceof Lambda || (argument instanceof New creation && creation.type().diamond());
	}

	private boolean accepts(JavaType parameter, @Nullable Typed argument, Expression written, boolean boxing) {
		if (argument != null) {
			return conversion(argument, parameter, boxing, false) != null;
		}
		if (written instanceof Lambda lambda) {
			Method method = functional(parameter);
			return method != null && method.parameters.size() == lambda.parameters().size();
		}
		return parameter instanceof ClassType target
				&& this.classes.find(((New) written).type().name(), this.unit) instanceof JavaClass made
				&& made.isSubclassOf(target.declaration());
	}

	/**
	 * Picks the method a call means: the candidates its arguments fit with no boxing, or
	 * else with it, and among those the one whose parameters are the most specific.
	 * @param typeArguments the receiver's type arguments, which a library collection's
	 * member is read against
	 */
	private Resolved resolve(List<Method> candidates, List<JavaType> typeArguments, List<Expression> arguments,
			String what, JavaClass in, int pos) {
		List<@Nullable Typed> typed = new ArrayList<>();
		for (Expression argument : arguments) {
			typed.add(needsTarget(argument) ? null : expression(argument, null));
		}
		List<Method> fitting = List.of();
		for (boolean boxing : new boolean[] { false, true }) {
			fitting = candidates.stream().filter(candidate -> {
				if (candidate.parameters.size() != arguments.size()) {
					return false;
				}
				for (int i = 0; i < arguments.size(); i++) {
					if (!candidate.raw && !accepts(substitute(candidate.parameters.get(i), typeArguments), typed.get(i),
							arguments.get(i), boxing)) {
						return false;
					}
				}
				return true;
			}).toList();
			if (!fitting.isEmpty()) {
				break;
			}
		}
		if (fitting.isEmpty()) {
			StringBuilder given = new StringBuilder();
			for (int i = 0; i < typed.size(); i++) {
				Typed argument = typed.get(i);
				given.append(i > 0 ? "," : "").append(argument == null ? "<lambda>" : argument.type().toString());
			}
			if (in.intrinsic) {
				throw unsupported(pos, (what.startsWith("constructor ") ? "new " + in.simpleName()
						: in.simpleName() + "." + what.substring("method ".length())) + "(" + given + ")");
			}
			throw error(pos, candidates.isEmpty() ? "cannot find symbol: " + what + "(" + given + ")"
					: "no suitable " + what + " found for the arguments (" + given + ")");
		}
		Method chosen = null;
		for (Method candidate : fitting) {
			boolean mostSpecific = true;
			for (Method other : fitting) {
				for (int i = 0; i < arguments.size() && mostSpecific; i++) {
					JavaType mine = substitute(candidate.parameters.get(i), typeArguments);
					JavaType theirs = substitute(other.parameters.get(i), typeArguments);
					mostSpecific = other.raw || conversion(new Typed(NIL, mine), theirs, false, false) != null;
				}
			}
			if (mostSpecific) {
				chosen = candidate;
				break;
			}
		}
		if (chosen == null) {
			throw error(pos, "reference to " + what + " is ambiguous");
		}
		List<Typed> converted = new ArrayList<>();
		for (int i = 0; i < arguments.size(); i++) {
			JavaType parameter = substitute(chosen.parameters.get(i), typeArguments);
			Typed argument = typed.get(i);
			if (argument == null) {
				argument = expression(arguments.get(i), parameter);
			}
			converted.add(new Typed(chosen.raw ? argument.form() : assign(argument, parameter, arguments.get(i).pos()),
					argument.type(), argument.constant()));
		}
		return new Resolved(chosen, converted, substitute(chosen.returnType, typeArguments));
	}

	private void refuseWhileInitializing(int pos) {
		if (this.initializing != null) {
			throw unsupported(pos, "a static field initializer that runs the program's code");
		}
	}

	private Typed invocation(Call call, boolean discard) {
		int pos = call.pos();
		if (call.target() instanceof Call inner && inner.name().equals("getClass") && inner.arguments().isEmpty()
				&& (call.name().equals("getName") || call.name().equals("getSimpleName"))
				&& call.arguments().isEmpty()) {
			Typed object = inner.target() == null ? thisValue(inner.pos()) : expression(inner.target(), null);
			if (!object.type().isReference()) {
				throw error(inner.pos(), object.type() + " cannot be dereferenced");
			}
			LispSymbol name = call.name().equals("getName") ? CLASS_NAME : SIMPLE_NAME;
			this.generated.add(name);
			LispVal text = list(name, object.form());
			this.nonNull.add(text);
			return new Typed(text, this.stringType);
		}
		if (call.name().equals("getClass") && call.arguments().isEmpty()) {
			throw unsupported(pos, "getClass() but for its getName() and getSimpleName()");
		}
		if (call.onSuper()) {
			JavaClass above = this.currentClass.superclass;
			if (above == null) {
				throw unsupported(pos, "a call of one of Object's methods through super");
			}
			Resolved resolved = resolve(methods(above, call.name()), List.of(), call.arguments(),
					"method " + call.name(), above, pos);
			Method body = implementation(above, resolved.method().signature());
			if (body == null || resolved.method().emitter != null) {
				throw resolved.method().emitter != null
						? unsupported(pos, "a call of one of Object's methods through super") : error(pos,
								"abstract method " + resolved.method().signature() + " cannot be accessed directly");
			}
			refuseWhileInitializing(pos);
			return new Typed(
					located(form(JavaNames.function(body), prepend(thisValue(pos).form(), resolved.forms())), pos),
					resolved.returnType());
		}
		Typed receiver = null;
		JavaClass in = this.currentClass;
		List<JavaType> typeArguments = List.of();
		boolean qualified = false;
		if (call.target() != null) {
			JavaClass named = classNamed(call.target());
			if (named != null) {
				in = named;
				qualified = true;
			}
			else {
				receiver = expression(call.target(), null);
				if (receiver.type() instanceof ClassType type) {
					in = type.declaration();
					typeArguments = type.arguments();
				}
				else if (receiver.type() instanceof ArrayType) {
					in = this.objectType.declaration();
				}
				else {
					throw error(pos, receiver.type() + " cannot be dereferenced");
				}
			}
		}
		Resolved resolved = resolve(methods(in, call.name()), typeArguments, call.arguments(), "method " + call.name(),
				in, pos);
		Method method = resolved.method();
		if (!method.isStatic && receiver == null) {
			if (qualified) {
				throw error(pos,
						"non-static method " + method.signature() + " cannot be referenced from a static context");
			}
			receiver = thisValue(pos);
		}
		if (method.emitter != null) {
			LispVal form = method.emitter.emit(this,
					new Invocation(method.isStatic ? null : receiver, resolved.arguments(), discard, pos));
			if (resolved.returnType().equals(this.stringType) && method.returnType.equals(this.stringType)) {
				this.nonNull.add(form);
			}
			return new Typed(located(form, pos), resolved.returnType());
		}
		refuseWhileInitializing(pos);
		LispVal form = method.isStatic ? form(JavaNames.function(method), resolved.forms())
				: virtual(in, method, Objects.requireNonNull(receiver).form(), resolved.forms());
		return new Typed(located(form, pos), resolved.returnType());
	}

	private static boolean isPrivate(Method method) {
		return method.declaration != null && method.declaration.modifiers().contains("private");
	}

	/** The bodies a call of a method on a receiver of a static type can run. */
	private List<Method> bodies(JavaClass receiver, Method method) {
		Set<Method> bodies = new LinkedHashSet<>();
		String signature = method.signature();
		for (JavaClass candidate : this.classes.all()) {
			if (!candidate.intrinsic && !candidate.isAbstract && candidate.isSubclassOf(receiver)) {
				Method body = implementation(candidate, signature);
				if (body != null) {
					bodies.add(body);
				}
			}
		}
		return bodies.stream().sorted(Comparator.comparingInt((Method body) -> body.owner.depth()).reversed()).toList();
	}

	/**
	 * The function a call of an instance method goes to: the one body under the
	 * receiver's static type, or the function choosing among several by the receiver's
	 * class; {@code null} when the receiver can only be a lambda.
	 */
	private @Nullable LispSymbol target(JavaClass receiver, Method method) {
		if (isPrivate(method)) {
			return JavaNames.function(method);
		}
		List<Method> bodies = bodies(receiver, method);
		Method single = functional(new ClassType(receiver, List.of()));
		boolean lambda = single != null && single.signature().equals(method.signature());
		if (bodies.isEmpty() && lambda) {
			return null;
		}
		if (bodies.size() == 1 && !lambda) {
			return JavaNames.function(bodies.get(0));
		}
		LispSymbol name = JavaNames.dispatcher(receiver, method);
		if (!this.dispatchers.containsKey(name.name())) {
			List<LispVal> parameters = new ArrayList<>();
			parameters.add(RECEIVER);
			for (int i = 0; i < method.parameters.size(); i++) {
				parameters.add(new LispSymbol("%a" + i));
			}
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			if (lambda) {
				arms.add(list(call("functionp", RECEIVER), form(cl("funcall"), parameters)));
			}
			for (Method body : bodies) {
				arms.add(list(list(JavaNames.predicate(body.owner), RECEIVER),
						form(JavaNames.function(body), parameters)));
			}
			arms.add(list(T, runtimeCall("no-receiver", RECEIVER, string(method.signature()))));
			this.dispatchers.put(name.name(), list(cl("defun"), name, list(parameters), list(arms)));
		}
		return name;
	}

	private LispVal virtual(JavaClass receiver, Method method, LispVal object, List<LispVal> arguments) {
		LispSymbol target = target(receiver, method);
		return target == null ? form(cl("funcall"), prepend(object, arguments))
				: form(target, prepend(object, arguments));
	}

	private Typed creation(New creation, @Nullable JavaType expected) {
		TypeName written = creation.type();
		int pos = creation.pos();
		ClassType type;
		if (written.diamond()) {
			JavaClass made = this.classes.require(written.name(), written.pos(), this.unit);
			if (!(expected instanceof ClassType target) || made.typeParameters == 0
					|| !made.isSubclassOf(target.declaration()) || target.arguments().size() != made.typeParameters) {
				throw error(pos, "cannot infer type arguments for " + made.simpleName() + "<> here");
			}
			type = new ClassType(made, target.arguments());
		}
		else if (type(written) instanceof ClassType named) {
			type = named;
		}
		else {
			throw error(pos, "unexpected type");
		}
		JavaClass made = type.declaration();
		if (made.isAbstract) {
			throw error(pos, made.simpleName() + " is abstract; cannot be instantiated");
		}
		Resolved resolved = resolve(constructors(made), type.arguments(), creation.arguments(),
				"constructor " + made.simpleName(), made, pos);
		if (resolved.method().emitter != null) {
			return new Typed(
					resolved.method().emitter.emit(this, new Invocation(null, resolved.arguments(), false, pos)), type);
		}
		refuseWhileInitializing(pos);
		return new Typed(located(form(JavaNames.creator(resolved.method()), resolved.forms()), pos), type);
	}

	// ---- statements

	/** Whether a statement never completes normally: what follows it is not reached. */
	private static boolean exits(Statement statement) {
		return switch (statement) {
			case Return exit -> true;
			case Throw exit -> true;
			case Block block ->
				!block.statements().isEmpty() && exits(block.statements().get(block.statements().size() - 1));
			case If branch -> branch.otherwise() != null && exits(branch.then()) && exits(branch.otherwise());
			default -> false;
		};
	}

	/**
	 * Lowers the statements of a block from one of them on.
	 * @param tail whether the value of the last one is the value of the function, so that
	 * a {@code return} there is its value and no exit
	 */
	private List<LispVal> sequence(List<Statement> statements, int from, boolean tail) {
		List<LispVal> forms = new ArrayList<>();
		for (int i = from; i < statements.size(); i++) {
			Statement statement = statements.get(i);
			boolean last = i == statements.size() - 1;
			if (statement instanceof LocalVariable variable) {
				forms.add(located(localVariable(variable, statements, i + 1, tail), variable.pos()));
				return forms;
			}
			if (tail && !last && statement instanceof If branch && branch.otherwise() == null && exits(branch.then())) {
				LispVal test = condition(branch.condition()).form();
				LispVal then = progn(scoped(branch.then(), true));
				forms.add(located(call("if", test, then, progn(sequence(statements, i + 1, true))), branch.pos()));
				return forms;
			}
			forms.addAll(statement(statement, tail && last));
		}
		return forms;
	}

	private LispVal localVariable(LocalVariable variable, List<Statement> statements, int rest, boolean tail) {
		List<LispVal> bindings = new ArrayList<>();
		boolean inferred = variable.type().name().equals("var") && variable.type().dimensions() == 0;
		JavaType declared = inferred ? null : type(variable.type());
		for (Declarator declarator : variable.declarators()) {
			JavaType type = declared;
			LispVal value;
			if (declarator.initializer() == null) {
				if (type == null) {
					throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
				}
				value = defaultValue(type);
			}
			else {
				Typed initial = expression(declarator.initializer(), type);
				if (type == null) {
					type = initial.type();
					if (type == JavaType.Null.NULL || type == Primitive.VOID) {
						throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
					}
				}
				value = assign(initial, type, declarator.pos());
			}
			bindings.add(list(bind(declarator.name(), type, declarator.pos()).symbol(), value));
		}
		List<LispVal> let = new ArrayList<>();
		let.add(cl(bindings.size() == 1 ? "let" : "let*"));
		let.add(list(bindings));
		let.addAll(sequence(statements, rest, tail));
		return list(let);
	}

	private List<LispVal> scoped(Statement statement, boolean tail) {
		this.scopes.push(new HashMap<>());
		List<LispVal> forms = statement instanceof Block block ? sequence(block.statements(), 0, tail)
				: sequence(List.of(statement), 0, tail);
		this.scopes.pop();
		return forms;
	}

	private LispVal expressionStatement(Expression expression) {
		boolean allowed = expression instanceof Assign || expression instanceof Call || expression instanceof New
				|| expression instanceof ConstructorCall || (expression instanceof Unary unary
						&& (unary.operator().equals("++") || unary.operator().equals("--")));
		if (!allowed) {
			throw error(expression.pos(), "not a statement");
		}
		return located(expression(expression, null, true).form(), expression.pos());
	}

	private List<LispVal> statement(Statement statement, boolean tail) {
		switch (statement) {
			case ExpressionStatement expression:
				return List.of(expressionStatement(expression.expression()));
			case Block block:
				return scoped(block, tail);
			case Empty empty:
				return List.of();
			case If branch: {
				LispVal test = condition(branch.condition()).form();
				LispVal then = progn(scoped(branch.then(), tail));
				return List.of(located(branch.otherwise() == null ? call("if", test, then)
						: call("if", test, then, progn(scoped(branch.otherwise(), tail))), branch.pos()));
			}
			case While loop:
				return List.of(located(loop(condition(loop.condition()).form(), loop.body(), List.of()), loop.pos()));
			case DoWhile loop: {
				// the test runs after the body, where a continue lands too
				LispSymbol again = temporary();
				Loop frame = new Loop(this.finallyDepth);
				this.loops.push(frame);
				List<LispVal> body = loopBody(scoped(loop.body(), false), frame);
				this.loops.pop();
				body.add(call("setq", again, condition(loop.condition()).form()));
				LispVal form = JavaForms.let(again, T, List.of(form(cl("while"), prepend(again, body))));
				return List.of(located(frame.breaks ? list(cl("block"), BREAK_BLOCK, form) : form, loop.pos()));
			}
			case For loop: {
				this.scopes.push(new HashMap<>());
				List<LispVal> before = new ArrayList<>();
				List<LispVal> bindings = new ArrayList<>();
				for (Statement initializer : loop.initializers()) {
					if (initializer instanceof LocalVariable variable) {
						JavaType declared = variable.type().name().equals("var") && variable.type().dimensions() == 0
								? null : type(variable.type());
						for (Declarator declarator : variable.declarators()) {
							JavaType type = declared;
							LispVal value;
							if (declarator.initializer() == null) {
								if (type == null) {
									throw error(declarator.pos(),
											"cannot infer type for local variable " + declarator.name());
								}
								value = defaultValue(type);
							}
							else {
								Typed initial = expression(declarator.initializer(), type);
								type = type == null ? initial.type() : type;
								value = assign(initial, type, declarator.pos());
							}
							bindings.add(list(bind(declarator.name(), type, declarator.pos()).symbol(), value));
						}
					}
					else {
						before.add(expressionStatement(((ExpressionStatement) initializer).expression()));
					}
				}
				LispVal test = loop.condition() == null ? T : condition(loop.condition()).form();
				List<LispVal> updates = new ArrayList<>();
				for (Expression update : loop.updates()) {
					updates.add(expressionStatement(update));
				}
				before.add(loop(test, loop.body(), updates));
				this.scopes.pop();
				LispVal form = bindings.isEmpty() ? progn(before) : form(cl("let*"), prepend(list(bindings), before));
				return List.of(located(form, loop.pos()));
			}
			case ForEach loop:
				return List.of(located(forEach(loop), loop.pos()));
			case Return exit: {
				LispVal value = NIL;
				if (exit.value() != null) {
					if (this.returnType == Primitive.VOID) {
						throw error(exit.pos(), "incompatible types: unexpected return value");
					}
					value = assign(expression(exit.value(), this.returnType), this.returnType, exit.value().pos());
				}
				else if (this.returnType != Primitive.VOID) {
					throw error(exit.pos(), "incompatible types: missing return value");
				}
				if (tail) {
					return exit.value() == null ? List.of() : List.of(value);
				}
				if (this.finallyDepth > 0) {
					throw unsupported(exit.pos(), "a return inside a finally block");
				}
				Objects.requireNonNull(this.frames.peek()).exits = true;
				return List.of(located(list(cl("return-from"), FUNCTION_BLOCK, value), exit.pos()));
			}
			case Break exit: {
				Loop loop = innermostLoop(exit.pos(), "break");
				loop.breaks = true;
				return List.of(list(cl("return-from"), BREAK_BLOCK, NIL));
			}
			case Continue exit: {
				Loop loop = innermostLoop(exit.pos(), "continue");
				loop.continues = true;
				return List.of(list(cl("return-from"), CONTINUE_BLOCK, NIL));
			}
			case Throw exit: {
				Typed thrown = expression(exit.value(), null);
				if (!isSubtype(thrown.type(), this.classes.libraryType("java.lang.Throwable"))) {
					throw error(exit.pos(),
							"incompatible types: " + thrown.type() + " cannot be converted to Throwable");
				}
				return List.of(located(raise(thrown.form()), exit.pos()));
			}
			case Try attempt:
				return List.of(located(attempt(attempt), attempt.pos()));
			case LocalVariable variable:
				throw error(variable.pos(), "a variable declaration is not allowed here");
		}
	}

	private Loop innermostLoop(int pos, String exit) {
		Loop loop = this.loops.peek();
		if (loop == null) {
			throw error(pos, exit + " outside of loop");
		}
		if (loop.finallyDepth != this.finallyDepth) {
			throw unsupported(pos, "a " + exit + " leaving a finally block");
		}
		return loop;
	}

	/** {@code throw}: the object, and the function the report of an uncaught one asks. */
	private LispVal raise(LispVal thrown) {
		JavaClass throwable = this.classes.library("java.lang.Throwable");
		Method describe = methods(throwable, "toString").get(0);
		LispSymbol target = Objects.requireNonNull(target(throwable, describe));
		this.raises = true;
		return runtimeCall("throw", thrown, function(target));
	}

	private List<LispVal> loopBody(List<LispVal> body, Loop frame) {
		List<LispVal> forms = new ArrayList<>();
		if (frame.continues) {
			forms.add(form(cl("block"), prepend(CONTINUE_BLOCK, body)));
		}
		else {
			forms.addAll(body);
		}
		return forms;
	}

	/** {@code (while test body.. after..)}, inside the blocks its exits need. */
	private LispVal loop(LispVal test, Statement body, List<LispVal> after) {
		Loop frame = new Loop(this.finallyDepth);
		this.loops.push(frame);
		List<LispVal> forms = loopBody(scoped(body, false), frame);
		this.loops.pop();
		forms.addAll(after);
		LispVal form = form(cl("while"), prepend(test, forms));
		return frame.breaks ? list(cl("block"), BREAK_BLOCK, form) : form;
	}

	private LispVal forEach(ForEach loop) {
		Typed iterable = expression(loop.iterable(), null);
		JavaType element;
		boolean array = iterable.type() instanceof ArrayType;
		if (iterable.type() instanceof ArrayType type) {
			element = type.element();
		}
		else if (iterable.type() instanceof ClassType type && type.declaration().name.endsWith("List")
				&& type.declaration().intrinsic) {
			element = type.arguments().get(0);
		}
		else {
			throw unsupported(loop.pos(), "an enhanced for over " + iterable.type());
		}
		LispSymbol items = temporary();
		LispSymbol index = temporary();
		LispSymbol count = temporary();
		JavaType type = loop.type().name().equals("var") && loop.type().dimensions() == 0 ? element : type(loop.type());
		this.scopes.push(new HashMap<>());
		LispSymbol variable = bind(loop.name(), type, loop.pos()).symbol();
		LispVal value = assign(new Typed(call("aref", items, index), element), type, loop.pos());
		Loop frame = new Loop(this.finallyDepth);
		this.loops.push(frame);
		List<LispVal> body = scoped(loop.body(), false);
		this.loops.pop();
		this.scopes.pop();
		List<LispVal> round = loopBody(List.of(JavaForms.let(variable, value, body)), frame);
		round.add(call("setq", index, call("+", index, integer(1))));
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(items, iterable.form()));
		if (array) {
			bindings.add(list(count, call("length", items)));
		}
		bindings.add(list(index, integer(0)));
		LispVal test = call("<", index, array ? count : call("length", items));
		LispVal form = list(cl("let*"), list(bindings), form(cl("while"), prepend(test, round)));
		return frame.breaks ? list(cl("block"), BREAK_BLOCK, form) : form;
	}

	/**
	 * {@code try}: the catch clauses are one {@code handler-case} clause testing the
	 * thrown object's class, clause by clause, and signalling again when none takes it;
	 * {@code finally} is an {@code unwind-protect}. A Lisp {@code error} reaches a clause
	 * whose type is above the exception it is turned into.
	 */
	private LispVal attempt(Try attempt) {
		LispVal body = progn(scoped(attempt.body(), false));
		if (!attempt.catches().isEmpty()) {
			LispSymbol condition = temporary();
			LispSymbol thrown = temporary();
			JavaClass throwable = this.classes.library("java.lang.Throwable");
			JavaClass arithmetic = this.classes.library("java.lang.ArithmeticException");
			JavaClass runtime = this.classes.library("java.lang.RuntimeException");
			boolean takesLispErrors = false;
			List<LispVal> arms = new ArrayList<>();
			arms.add(cl("cond"));
			for (Catch clause : attempt.catches()) {
				if (!(type(clause.type()) instanceof ClassType type) || !type.declaration().isSubclassOf(throwable)) {
					throw error(clause.type().pos(),
							"incompatible types: " + clause.type().name() + " cannot be converted to Throwable");
				}
				takesLispErrors |= arithmetic.isSubclassOf(type.declaration())
						|| runtime.isSubclassOf(type.declaration());
				this.scopes.push(new HashMap<>());
				LispSymbol parameter = bind(clause.name(), type, clause.pos()).symbol();
				List<LispVal> handler = sequence(clause.body().statements(), 0, false);
				this.scopes.pop();
				arms.add(list(list(JavaNames.predicate(type.declaration()), thrown),
						JavaForms.let(parameter, thrown, handler)));
			}
			arms.add(list(T, call("error", condition)));
			LispVal object = runtimeCall("thrown", condition);
			if (takesLispErrors) {
				this.generated.add(LISP_ERROR);
				object = call("or", object, list(LISP_ERROR, condition));
			}
			body = list(cl("handler-case"), body,
					list(cl("error"), list(condition), JavaForms.let(thrown, object, List.of(list(arms)))));
		}
		if (attempt.finallyBlock() != null) {
			this.finallyDepth++;
			List<LispVal> cleanup = scoped(attempt.finallyBlock(), false);
			this.finallyDepth--;
			body = form(cl("unwind-protect"), prepend(body, cleanup));
		}
		return body;
	}

}
