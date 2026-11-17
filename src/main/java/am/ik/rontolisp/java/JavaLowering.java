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
import am.ik.rontolisp.java.JavaInitOrder.Effects;
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
import am.ik.rontolisp.java.JavaSyntax.QualifiedThis;
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

	/** A local variable, a parameter or a pattern's binding. */
	private static final class Local {

		final LispSymbol symbol;

		final JavaType type;

		/** Whether it is {@code final} and has its value where it is declared. */
		boolean isFinal;

		/** Whether it has its value where it is declared: a later one is a second. */
		boolean initialized;

		/** Where it is assigned after its declaration, or -1. */
		int reassigned = -1;

		/** Whether a lambda or a local class reads it. */
		boolean captured;

		Local(LispSymbol symbol, JavaType type) {
			this.symbol = symbol;
			this.type = type;
		}

		LispSymbol symbol() {
			return this.symbol;
		}

		JavaType type() {
			return this.type;
		}

	}

	/**
	 * A scope of names: a block's locals and local classes. A scope that starts a
	 * lambda's body or a local class's marks where a read from inside becomes a capture.
	 */
	private static final class Scope {

		final Map<String, Local> locals = new HashMap<>();

		final Map<String, JavaClass> classes = new HashMap<>();

		/** The local or anonymous class whose body starts here, or {@code null}. */
		final @Nullable JavaClass boundary;

		/** Whether a lambda's body starts here. */
		final boolean lambda;

		Scope(@Nullable JavaClass boundary, boolean lambda) {
			this.boundary = boundary;
			this.lambda = lambda;
		}

	}

	/** A function body being lowered: whether a {@code return} leaves from its middle. */
	private static final class Frame {

		boolean exits;

		/** Whether it is an initializer, which no {@code return} leaves. */
		boolean initializer;

	}

	/**
	 * What a {@code break}, a {@code continue} or a {@code yield} can leave: a loop, a
	 * switch, a labeled statement.
	 */
	private static final class Target {

		final int finallyDepth;

		final @Nullable String label;

		final boolean isLoop;

		/**
		 * Whether an unlabeled {@code break} stops here: a loop or a switch statement.
		 */
		final boolean takesBreak;

		/** Whether it is a switch expression: only a {@code yield} leaves it. */
		final boolean isExpression;

		final LispSymbol breakBlock;

		final LispSymbol continueBlock;

		boolean breaks;

		boolean continues;

		Target(int finallyDepth, @Nullable String label, boolean isLoop, boolean takesBreak, boolean isExpression) {
			this.finallyDepth = finallyDepth;
			this.label = label;
			this.isLoop = isLoop;
			this.takesBreak = takesBreak;
			this.isExpression = isExpression;
			this.breakBlock = isExpression ? YIELD_BLOCK
					: label == null ? BREAK_BLOCK : new LispSymbol(label + "%break");
			this.continueBlock = label == null ? CONTINUE_BLOCK : new LispSymbol(label + "%continue");
		}

	}

	/** What a simple name means where the lowering stands. */
	private sealed interface Named {

	}

	private record LocalRef(Local local) implements Named {
	}

	/** A local of an enclosing method read from a local class: a slot of the class. */
	private record CaptureRef(Typed read) implements Named {
	}

	/**
	 * A field: of the class the lowering stands in, or of a class around it.
	 *
	 * @param field the field
	 * @param object the instance holding it, or {@code null} for a static field and where
	 * no instance can be reached
	 */
	private record FieldRef(Field field, @Nullable LispVal object) implements Named {
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

	private static final LispSymbol YIELD_BLOCK = new LispSymbol("%yield");

	private static final LispSymbol OUTER = new LispSymbol("%outer");

	/** The names an enum constructor's first two parameters have. */
	private static final List<String> ENUM_PARAMETERS = List.of("%name", "%ordinal");

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
	private @Nullable Initializing initializing;

	/**
	 * A static initializer being lowered.
	 *
	 * @param owner its class
	 * @param pos where it stands among the class's initializers
	 * @param field the field it initializes, or {@code null} for a block
	 */
	private record Initializing(JavaClass owner, int pos, @Nullable Field field) {
	}

	/** Whether a static field is read ahead of its own initializer. */
	private boolean forward(Field field) {
		Initializing running = this.initializing;
		return running != null && field.owner == running.owner()
				&& (field.pos > running.pos() || field == running.field()) && field.form == null
				&& field.initializer != null && constantOf(field) == null;
	}

	private Deque<Scope> scopes = new ArrayDeque<>();

	private final Deque<Frame> frames = new ArrayDeque<>();

	private final Deque<Target> targets = new ArrayDeque<>();

	private int finallyDepth;

	/** What the function being lowered does. */
	private Effects effects = new Effects();

	private final Map<Method, Effects> methodEffects = new HashMap<>();

	/** The static initialization of each class: its forms, in the class's order. */
	private final Map<JavaClass, List<LispVal>> staticInits = new LinkedHashMap<>();

	private final Map<JavaClass, Effects> initEffects = new HashMap<>();

	/**
	 * The locals declared with no value that have one for sure where the lowering stands.
	 * A statement the lowering does not follow branch by branch leaves here what any of
	 * its branches assigned, so a read is refused only where no path assigned.
	 */
	private Set<Local> assigned = new HashSet<>();

	/** The local and anonymous classes lowered already in this pass. */
	private final Set<JavaClass> loweredLocals = new HashSet<>();

	/** The bindings of the patterns of the statement being lowered. */
	private List<Local> patternVariables = new ArrayList<>();

	/**
	 * Whether the lowering stands ahead of a constructor's {@code this(..)} or
	 * {@code super(..)}, where the instance is not to be read.
	 */
	private boolean early;

	/** The constructor or initializer being lowered: where a final field is assigned. */
	private @Nullable JavaClass constructing;

	private boolean constructingStatic;

	/** Whether an enum constant's own instance creation is being lowered. */
	private boolean makingConstant;

	private final JavaInitOrder order;

	private SourceProvenance.@Nullable Unit provenance;

	private int @Nullable [] lineStarts;

	private JavaLowering(JavaClasses classes, List<JavaClass> declared, Source program, JavaInitOrder order) {
		this.classes = classes;
		this.declared = declared;
		this.program = program;
		this.order = order;
		classes.localScope = this::localClass;
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
		JavaInitOrder order = new JavaInitOrder();
		while (true) {
			int version = classes.version();
			JavaLowering lowering = new JavaLowering(classes, declared, program, order);
			lowering.provenance = provenance;
			List<LispVal> forms = lowering.program();
			if (classes.version() == version) {
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
		this.launched = launched();
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (!declaration.intrinsic && !declaration.isLocal) {
				implementations(declaration);
				lowerClass(declaration);
			}
		}
		LispVal entry = entry();
		List<LispVal> initialization = initialization();
		List<LispVal> forms = new ArrayList<>();
		Set<JavaClass> defined = new HashSet<>();
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			structure(declaration, defined, forms);
		}
		forms.addAll(this.variables);
		forms.addAll(this.functions);
		forms.addAll(this.dispatchers.values());
		forms.addAll(generatedFunctions());
		forms.addAll(initialization);
		forms.add(entry);
		return List.copyOf(forms);
	}

	/** The slot holding the instance of the class a class's declaration stands in. */
	private static String outerSlot(JavaClass declaration) {
		return "this$" + declaration.depth();
	}

	private static LispSymbol slotAccessor(JavaClass declaration, String slot) {
		return new LispSymbol(declaration.lispName() + "." + JavaNames.mangle(slot));
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
		if (declaration.hasOuterInstance()) {
			structure.add(list(JavaNames.slot(outerSlot(declaration)), NIL));
		}
		for (JavaClass.Capture capture : declaration.captures.values()) {
			structure.add(list(JavaNames.slot(capture.slot()), NIL));
		}
		for (Field field : declaration.fields) {
			if (field.isStatic) {
				continue;
			}
			Field hidden = declaration.superclass == null ? null : field(declaration.superclass, field.name);
			if (hidden != null && !hidden.isStatic) {
				// a structure has one slot of a name
				throw JavaClasses.unsupported(declaration.source, field.pos,
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
					String name = declaration.simpleName().isEmpty() ? "<anonymous " + declaration.binaryName + ">"
							: declaration.simpleName();
					throw JavaClasses.error(declaration.source, Objects.requireNonNull(declaration.declaration).pos(),
							name + " is not abstract and does not override abstract method " + method.signature()
									+ " in " + type.simpleName());
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
		for (Method method : List.copyOf(declaration.methods)) {
			this.currentClass = declaration;
			this.unit = this.classes.unit(declaration);
			if (method.isConstructor()) {
				constructor(method);
			}
			else if (!method.isAbstract) {
				method(method);
			}
		}
		staticInitialization(declaration);
	}

	/**
	 * A class's static initialization: the initializers of its static fields and its
	 * static blocks, in the order they are written. When it runs is
	 * {@link #initialization}'s.
	 */
	private void staticInitialization(JavaClass declaration) {
		record Item(int pos, @Nullable Field field, JavaSyntax.@Nullable Initializer block) {
		}
		List<Item> items = new ArrayList<>();
		for (Field field : declaration.fields) {
			if (!field.isStatic) {
				continue;
			}
			this.variables.add(list(cl("defvar"), JavaNames.global(field)));
			if (field.initializer != null && constantOf(field) == null) {
				items.add(new Item(field.pos, field, null));
			}
		}
		for (JavaSyntax.Initializer block : Objects.requireNonNull(declaration.declaration).initializers()) {
			if (block.isStatic()) {
				items.add(new Item(block.pos(), null, block));
			}
		}
		if (items.isEmpty()) {
			return;
		}
		items.sort(Comparator.comparingInt(Item::pos));
		Effects saved = this.effects;
		this.effects = new Effects();
		List<LispVal> forms = new ArrayList<>();
		for (Item item : items) {
			Field field = item.field();
			forms.addAll(inContext(declaration, true, () -> {
				this.constructing = declaration;
				this.constructingStatic = true;
				this.initializing = new Initializing(declaration, item.pos(), field);
				if (field == null) {
					return patterned(() -> scoped(Objects.requireNonNull(item.block()).body(), false));
				}
				this.makingConstant = field.isConstant;
				Expression initializer = Objects.requireNonNull(field.initializer);
				LispVal value = patterned(
						() -> List.of(assign(expression(initializer, field.type), field.type, field.pos)))
					.get(0);
				return List.of(call("setq", JavaNames.global(field), value));
			}));
		}
		this.staticInits.put(declaration, forms);
		this.initEffects.put(declaration, this.effects);
		this.effects = saved;
	}

	/**
	 * The scopes a class's own members are lowered in: none of a method's, and for a
	 * local class the ones its declaration stands in.
	 */
	private Deque<Scope> scopesAround(JavaClass declaration) {
		Deque<Scope> around = new ArrayDeque<>();
		boolean found = false;
		for (Scope scope : this.scopes) {
			found |= scope.boundary == declaration;
			if (found) {
				around.addLast(scope);
			}
		}
		return around;
	}

	/** Runs a lowering with no local of a method in scope, in a class of the program. */
	private <R> R inContext(JavaClass declaration, boolean isStatic, Supplier<R> lowering) {
		JavaClass savedClass = this.currentClass;
		JavaClasses.Unit savedUnit = this.unit;
		boolean savedStatic = this.staticContext;
		Deque<Scope> savedScopes = this.scopes;
		Initializing savedInitializing = this.initializing;
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		int savedFinally = this.finallyDepth;
		boolean savedEarly = this.early;
		boolean savedMaking = this.makingConstant;
		JavaClass savedConstructing = this.constructing;
		boolean savedConstructingStatic = this.constructingStatic;
		JavaType savedReturn = this.returnType;
		this.scopes = scopesAround(declaration);
		this.scopes.push(new Scope(null, false));
		this.currentClass = declaration;
		this.unit = this.classes.unit(declaration);
		this.staticContext = isStatic;
		this.initializing = null;
		this.targets.clear();
		this.finallyDepth = 0;
		this.early = false;
		this.makingConstant = false;
		this.returnType = Primitive.VOID;
		Frame frame = new Frame();
		frame.initializer = true;
		this.frames.push(frame);
		try {
			return lowering.get();
		}
		finally {
			this.frames.pop();
			this.currentClass = savedClass;
			this.unit = savedUnit;
			this.staticContext = savedStatic;
			this.scopes = savedScopes;
			this.initializing = savedInitializing;
			this.targets.clear();
			this.targets.addAll(savedTargets);
			this.finallyDepth = savedFinally;
			this.early = savedEarly;
			this.makingConstant = savedMaking;
			this.constructing = savedConstructing;
			this.constructingStatic = savedConstructingStatic;
			this.returnType = savedReturn;
		}
	}

	/**
	 * Lowers a local or an anonymous class where it is declared, once a pass: its members
	 * read the locals in scope there, each a slot of the class set where an instance is
	 * made.
	 */
	private void lowerLocal(JavaClass made) {
		if (!this.loweredLocals.add(made)) {
			return;
		}
		JavaClass savedClass = this.currentClass;
		JavaClasses.Unit savedUnit = this.unit;
		boolean savedStatic = this.staticContext;
		JavaType savedReturn = this.returnType;
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		int savedFinally = this.finallyDepth;
		boolean savedEarly = this.early;
		boolean savedMaking = this.makingConstant;
		JavaClass savedConstructing = this.constructing;
		boolean savedConstructingStatic = this.constructingStatic;
		Initializing savedInitializing = this.initializing;
		Effects savedEffects = this.effects;
		List<Local> savedPatterns = this.patternVariables;
		this.patternVariables = new ArrayList<>();
		this.targets.clear();
		this.finallyDepth = 0;
		this.early = false;
		this.makingConstant = false;
		this.initializing = null;
		try {
			lowerMembers(made);
		}
		finally {
			this.currentClass = savedClass;
			this.unit = savedUnit;
			this.staticContext = savedStatic;
			this.returnType = savedReturn;
			this.targets.clear();
			this.targets.addAll(savedTargets);
			this.finallyDepth = savedFinally;
			this.early = savedEarly;
			this.makingConstant = savedMaking;
			this.constructing = savedConstructing;
			this.constructingStatic = savedConstructingStatic;
			this.initializing = savedInitializing;
			this.effects = savedEffects;
			this.patternVariables = savedPatterns;
		}
	}

	/**
	 * A local class and the member types declared in it, each behind its own boundary.
	 */
	private void lowerMembers(JavaClass made) {
		this.scopes.push(new Scope(made, false));
		implementations(made);
		lowerClass(made);
		for (JavaClass member : made.members.values()) {
			member.isLocal = true;
			if (this.loweredLocals.add(member)) {
				lowerMembers(member);
			}
		}
		this.scopes.pop();
	}

	private List<LispSymbol> bindParameters(Method method) {
		List<LispSymbol> symbols = new ArrayList<>();
		JavaSyntax.Method written = method.declaration;
		for (int i = 0; i < method.parameters.size(); i++) {
			Local local;
			if (written == null || i < method.hidden) {
				// an enum constructor's name and ordinal; what an anonymous class's
				// constructor hands on
				String name = method.owner.isEnum && i < method.hidden ? ENUM_PARAMETERS.get(i) : "%a" + i;
				local = bind(name, method.parameters.get(i), 0);
			}
			else {
				Parameter parameter = written.parameters().get(i - method.hidden);
				local = bind(parameter.name(), method.parameters.get(i), parameter.pos());
				local.isFinal = parameter.isFinal();
			}
			local.initialized = true;
			symbols.add(local.symbol());
		}
		return symbols;
	}

	/** The call running a lazy class's static initialization, or {@code null}. */
	private @Nullable LispVal initializationOf(JavaClass declaration) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			if (this.launched != null && this.launched.isSubclassOf(type)) {
				// initialized where the program starts
				return null;
			}
			if (this.order.isLazy(type)) {
				return list(JavaNames.initializer(type));
			}
		}
		return null;
	}

	private void method(Method method) {
		JavaSyntax.Method written = Objects.requireNonNull(method.declaration);
		this.staticContext = method.isStatic;
		this.returnType = method.returnType;
		this.constructing = null;
		Effects saved = this.effects;
		this.effects = new Effects();
		this.methodEffects.put(method, this.effects);
		this.scopes.push(new Scope(null, false));
		List<LispVal> parameters = new ArrayList<>();
		if (!method.isStatic) {
			parameters.add(THIS);
		}
		parameters.addAll(bindParameters(method));
		if (method.returnType != Primitive.VOID && completes(Objects.requireNonNull(written.body()))) {
			throw error(written.pos(), "missing return statement");
		}
		LispVal body = functionBody(Objects.requireNonNull(written.body()));
		this.scopes.pop();
		this.effects = saved;
		LispVal first = method.isStatic ? initializationOf(method.owner) : null;
		if (first != null) {
			body = call("progn", first, body);
		}
		this.functions.add(list(cl("defun"), JavaNames.function(method), list(parameters), body));
	}

	/**
	 * The fields a class's instances start with a value in, and its initializer blocks.
	 */
	private static boolean initializesInstances(JavaClass owner) {
		for (Field field : owner.fields) {
			if (!field.isStatic && field.initializer != null) {
				return true;
			}
		}
		return owner.declaration != null
				&& owner.declaration.initializers().stream().anyMatch(block -> !block.isStatic());
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
		if (initializesInstances(owner)) {
			return false;
		}
		if (constructor.passesTo != null) {
			return isTrivial(constructor.passesTo);
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

	/**
	 * What every constructor not handing over to another runs after its superclass's: the
	 * initializers of the instance fields and the initializer blocks, in the order they
	 * are written.
	 */
	private List<LispVal> instanceInitializers(JavaClass owner) {
		record Item(int pos, @Nullable Field field, JavaSyntax.@Nullable Initializer block) {
		}
		List<Item> items = new ArrayList<>();
		for (Field field : owner.fields) {
			if (!field.isStatic && field.initializer != null) {
				items.add(new Item(field.pos, field, null));
			}
		}
		for (JavaSyntax.Initializer block : Objects.requireNonNull(owner.declaration).initializers()) {
			if (!block.isStatic()) {
				items.add(new Item(block.pos(), null, block));
			}
		}
		items.sort(Comparator.comparingInt(Item::pos));
		List<LispVal> forms = new ArrayList<>();
		for (Item item : items) {
			Field field = item.field();
			forms.addAll(inContext(owner, false, () -> {
				this.constructing = owner;
				this.constructingStatic = false;
				if (field == null) {
					return patterned(() -> scoped(Objects.requireNonNull(item.block()).body(), false));
				}
				Expression initializer = Objects.requireNonNull(field.initializer);
				LispVal value = patterned(
						() -> List.of(assign(expression(initializer, field.type), field.type, field.pos)))
					.get(0);
				return List.of(call("setf", list(JavaNames.accessor(field), THIS), value));
			}));
		}
		return forms;
	}

	/** The {@code this(..)} or {@code super(..)} of the constructor being lowered. */
	private @Nullable ConstructorCall explicitCall;

	private void constructor(Method constructor) {
		JavaClass owner = constructor.owner;
		JavaSyntax.Method written = constructor.declaration;
		int pos = written != null ? written.pos() : Objects.requireNonNull(owner.declaration).pos();
		this.staticContext = false;
		this.returnType = Primitive.VOID;
		Effects saved = this.effects;
		this.effects = new Effects();
		this.methodEffects.put(constructor, this.effects);
		this.scopes.push(new Scope(null, false));
		List<LispSymbol> parameters = bindParameters(constructor);
		List<Statement> statements = written == null ? List.of() : Objects.requireNonNull(written.body()).statements();
		ConstructorCall explicit = null;
		for (Statement statement : statements) {
			if (statement instanceof ExpressionStatement first && first.expression() instanceof ConstructorCall call) {
				explicit = call;
				break;
			}
		}
		Frame frame = new Frame();
		this.frames.push(frame);
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		this.targets.clear();
		this.constructing = owner;
		this.constructingStatic = false;
		List<LispVal> forms = new ArrayList<>();
		if (explicit == null) {
			if (constructor.passesTo != null) {
				// an anonymous class's constructor hands its arguments to its
				// superclass's
				this.effects.calls.add(constructor.passesTo);
				if (!isTrivial(constructor.passesTo)) {
					forms.add(form(JavaNames.function(constructor.passesTo), prepend(THIS, List.copyOf(parameters))));
				}
			}
			else if (owner.superclass != null) {
				List<Expression> arguments = new ArrayList<>();
				if (owner.isEnum) {
					for (String name : ENUM_PARAMETERS) {
						arguments.add(new JavaSyntax.Name(pos, name));
					}
				}
				forms.add(constructorCall(owner.superclass, arguments, pos));
			}
			forms.removeIf(form -> form == NIL);
			forms.addAll(instanceInitializers(owner));
			forms.addAll(sequence(statements, 0, false));
		}
		else {
			this.early = true;
			this.explicitCall = explicit;
			forms.addAll(sequence(statements, 0, false));
			this.explicitCall = null;
			this.early = false;
		}
		this.targets.addAll(savedTargets);
		this.frames.pop();
		this.scopes.pop();
		this.constructing = null;
		this.effects = saved;
		boolean trivial = isTrivial(constructor);
		if (!trivial) {
			List<LispVal> all = new ArrayList<>();
			all.add(THIS);
			all.addAll(parameters);
			LispVal body = frame.exits ? form(cl("block"), prepend(FUNCTION_BLOCK, forms)) : progn(forms);
			this.functions.add(list(cl("defun"), JavaNames.function(constructor), list(all), body));
		}
		if (owner.isAbstract && !owner.isEnum) {
			return;
		}
		// the function making an instance: the slots a nested class starts with -- the
		// instance around it, the locals it reads -- are set ahead of the constructor
		List<LispVal> arguments = new ArrayList<>();
		List<LispVal> setup = new ArrayList<>();
		if (owner.hasOuterInstance()) {
			arguments.add(OUTER);
		}
		for (JavaClass type = owner; type != null; type = type.superclass) {
			if (!type.hasOuterInstance()) {
				continue;
			}
			LispVal outer = type == owner ? OUTER : outerInstance(Objects.requireNonNull(type.outer), owner.outer,
					owner.hasOuterInstance() ? OUTER : null);
			if (outer == null) {
				throw unsupported(pos, "a class extending the inner class " + type.simpleName()
						+ " where no instance of " + Objects.requireNonNull(type.outer).simpleName() + " is around it");
			}
			setup.add(call("setf", list(slotAccessor(type, outerSlot(type)), THIS), outer));
		}
		arguments.addAll(parameters);
		int captured = 0;
		for (JavaClass type = owner; type != null; type = type.superclass) {
			for (JavaClass.Capture capture : type.captures.values()) {
				LispSymbol value = new LispSymbol("%c" + captured++);
				arguments.add(value);
				setup.add(call("setf", list(slotAccessor(type, capture.slot()), THIS), value));
			}
		}
		LispVal instance = list(JavaNames.allocator(owner));
		if (!trivial || !setup.isEmpty()) {
			List<LispVal> made = new ArrayList<>(setup);
			if (!trivial) {
				made.add(form(JavaNames.function(constructor), prepend(THIS, List.copyOf(parameters))));
			}
			made.add(THIS);
			instance = JavaForms.let(THIS, instance, made);
		}
		LispVal first = initializationOf(owner);
		if (first != null) {
			instance = call("progn", first, instance);
		}
		this.functions.add(list(cl("defun"), JavaNames.creator(constructor), list(arguments), instance));
	}

	/**
	 * The instance of a class, or of a subclass of it, that is around a class whose own
	 * outer instance is given: the instance itself, or the one around that.
	 * @param wanted the class an instance is wanted of
	 * @param around the class the given instance is of, or {@code null}
	 * @param instance the instance of {@code around}, or {@code null} where there is none
	 * @return the form, or {@code null} when no such instance is around
	 */
	private static @Nullable LispVal outerInstance(JavaClass wanted, @Nullable JavaClass around,
			@Nullable LispVal instance) {
		LispVal path = instance;
		for (JavaClass type = around; type != null && path != null; type = type.outer) {
			if (type.isSubclassOf(wanted)) {
				return path;
			}
			path = type.hasOuterInstance() ? list(slotAccessor(type, outerSlot(type)), path) : null;
		}
		return null;
	}

	private static List<LispVal> prepend(LispVal first, List<? extends LispVal> rest) {
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
		checkAccess(resolved.method().isPrivate, resolved.method().owner,
				target.simpleName() + resolved.method().parameterList(), pos);
		this.effects.calls.add(resolved.method());
		if (isTrivial(resolved.method())) {
			return NIL;
		}
		return located(form(JavaNames.function(resolved.method()), prepend(THIS, resolved.forms())), pos);
	}

	private LispVal functionBody(Block body) {
		Frame frame = new Frame();
		this.frames.push(frame);
		Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
		this.targets.clear();
		int savedFinally = this.finallyDepth;
		this.finallyDepth = 0;
		List<Local> savedPatterns = this.patternVariables;
		this.patternVariables = new ArrayList<>();
		this.scopes.push(new Scope(null, false));
		List<LispVal> forms = sequence(body.statements(), 0, true);
		this.scopes.pop();
		this.patternVariables = savedPatterns;
		this.finallyDepth = savedFinally;
		this.targets.addAll(savedTargets);
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
		JavaClass launched = launched();
		Method main = mainOf(launched);
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
		// the class the program starts at is initialized before anything of it runs
		for (JavaClass type = launched; type != null; type = type.superclass) {
			if (this.order.isLazy(type)) {
				start = call("progn", list(JavaNames.initializer(type)), start);
				break;
			}
		}
		// A program that throws starts inside an unwind-protect protecting nothing: a
		// wasm module holding one reports the condition nothing caught, as the
		// interpreter and the JVM always do.
		return this.raises ? list(cl("unwind-protect"), start, NIL) : start;
	}

	/**
	 * The class the program starts at: the first class of the file when it has a
	 * {@code main}, else the class named as the file.
	 */
	private JavaClass launched() {
		JavaClass first = this.declared.get(0);
		if (mainOf(first) != null || this.program.file() == null) {
			return first;
		}
		String file = this.program.file().replace('\\', '/');
		String name = file.substring(file.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "");
		JavaClass launched = first;
		for (JavaClass declaration : this.declared) {
			if (declaration.simpleName().equals(name)) {
				launched = declaration;
			}
		}
		return launched;
	}

	/** The class the program starts at: initialized before anything else of it runs. */
	private @Nullable JavaClass launched;

	private @Nullable Method mainOf(JavaClass declaration) {
		ArrayType strings = new ArrayType(this.stringType);
		Method found = null;
		for (JavaClass type = declaration; type != null && found == null; type = type.superclass) {
			for (Method method : type.methods) {
				if (method.name.equals("main") && method.returnType == Primitive.VOID && !method.isPrivate
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
							string(name == CLASS_NAME ? declaration.binaryName : declaration.simpleName())));
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

	/** The local class of a simple name in scope, or {@code null}. */
	private @Nullable JavaClass localClass(String name) {
		for (Scope scope : this.scopes) {
			JavaClass found = scope.classes.get(name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private Local bind(String name, JavaType type, int pos) {
		if (name.equals("_")) {
			// the unnamed variable: a value kept nowhere a name reaches
			return new Local(temporary(), type);
		}
		for (Scope scope : this.scopes) {
			if (scope.locals.containsKey(name)) {
				throw error(pos, "variable " + name + " is already defined");
			}
			if (scope.boundary != null) {
				break;
			}
		}
		Local bound = new Local(JavaNames.symbol(name), type);
		Objects.requireNonNull(this.scopes.peek()).locals.put(name, bound);
		return bound;
	}

	/** The slot of a local class holding a local it reads, made at the first read. */
	private JavaClass.Capture capture(JavaClass reader, String name, Local local) {
		JavaClass.Capture capture = reader.captures.get(name);
		if (capture == null) {
			capture = new JavaClass.Capture(name, local.type(), "val$" + name + "$" + reader.depth());
			reader.captures.put(name, capture);
			this.classes.changed();
		}
		return capture;
	}

	/**
	 * What a simple name means: a local, innermost first; a field of the class the
	 * lowering stands in, its own or inherited; then, for a class declared in a method,
	 * the locals around its declaration, each a slot of the class; then the fields of the
	 * classes around it, reached through the instance each inner class holds.
	 * @param probe whether the name is only looked for: nothing is recorded or refused
	 */
	private @Nullable Named variable(String name, int pos, boolean probe) {
		JavaClass type = this.currentClass;
		LispVal path = this.staticContext ? null : THIS;
		boolean lambda = false;
		JavaClass reader = null;
		for (Scope scope : this.scopes) {
			Local local = scope.locals.get(name);
			if (local != null) {
				if (reader == null && !lambda) {
					return new LocalRef(local);
				}
				if (probe) {
					return new LocalRef(local);
				}
				if (local.reassigned >= 0) {
					throw error(pos,
							"local variables referenced from "
									+ (reader != null ? "an inner class" : "a lambda expression")
									+ " must be final or effectively final");
				}
				local.captured = true;
				if (!local.initialized && !this.assigned.contains(local)) {
					throw error(pos, "variable " + name + " might not have been initialized");
				}
				if (reader == null) {
					return new LocalRef(local);
				}
				if (this.staticContext) {
					throw error(pos, "non-static variable " + name + " cannot be referenced from a static context");
				}
				JavaClass.Capture capture = capture(reader, name, local);
				return new CaptureRef(new Typed(list(slotAccessor(reader, capture.slot()), THIS), local.type()));
			}
			lambda |= scope.lambda;
			if (scope.boundary != null && type != null) {
				Field field = field(type, name);
				if (field != null) {
					return new FieldRef(field, field.isStatic ? null : path);
				}
				if (reader == null) {
					reader = type;
				}
				path = type.hasOuterInstance() && path != null ? list(slotAccessor(type, outerSlot(type)), path) : null;
				type = type.outer;
			}
		}
		while (type != null) {
			Field field = field(type, name);
			if (field != null) {
				return new FieldRef(field, field.isStatic ? null : path);
			}
			path = type.hasOuterInstance() && path != null ? list(slotAccessor(type, outerSlot(type)), path) : null;
			type = type.outer;
		}
		return null;
	}

	/** The instance of a class around the one the lowering stands in, or {@code null}. */
	private @Nullable LispVal enclosing(JavaClass wanted) {
		return outerInstance(wanted, this.currentClass, this.staticContext || this.early ? null : THIS);
	}

	// ---- the class table

	/**
	 * The field of a name a class has: its own, one of a superclass, a constant of an
	 * interface.
	 */
	private static @Nullable Field field(JavaClass declaration, String name) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			for (Field field : type.fields) {
				if (field.name.equals(name)) {
					return field;
				}
			}
			for (JavaClass implemented : type.interfaces) {
				Field constant = field(implemented, name);
				if (constant != null) {
					return constant;
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
		if (declaration.isEnum && (name.equals("values") || name.equals("valueOf"))) {
			this.classes.enumMethod(declaration, name);
		}
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

	/**
	 * The body a class runs for a signature: its own or the nearest one above it, else an
	 * interface's default, the one of the most specific interface.
	 */
	private static @Nullable Method implementation(JavaClass declaration, String signature) {
		for (JavaClass type = declaration; type != null; type = type.superclass) {
			for (Method method : type.methods) {
				if (!method.isAbstract && !method.isStatic && method.signature().equals(signature)) {
					return method;
				}
			}
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(declaration, above);
		Method found = null;
		for (JavaClass type : above) {
			if (!type.isInterface) {
				continue;
			}
			for (Method method : type.methods) {
				if (method.isDefault() && method.signature().equals(signature)
						&& (found == null || type.isSubclassOf(found.owner))) {
					found = method;
				}
			}
		}
		return found;
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

	/**
	 * A lowered condition and the pattern bindings it leaves: the ones set when it is
	 * true, the ones set when it is false.
	 */
	private record Flow(Typed value, Map<String, Local> whenTrue, Map<String, Local> whenFalse) {
	}

	private Flow test(Expression expression) {
		Flow flow = flow(expression);
		if (flow.value().type() != Primitive.BOOLEAN) {
			throw error(expression.pos(),
					"incompatible types: " + flow.value().type() + " cannot be converted to boolean");
		}
		return flow;
	}

	/** Runs a lowering with pattern bindings in scope. */
	private <R> R within(Map<String, Local> bindings, Supplier<R> lowering) {
		if (bindings.isEmpty()) {
			return lowering.get();
		}
		Scope scope = new Scope(null, false);
		scope.locals.putAll(bindings);
		this.scopes.push(scope);
		try {
			return lowering.get();
		}
		finally {
			this.scopes.pop();
		}
	}

	private static Map<String, Local> union(Map<String, Local> a, Map<String, Local> b) {
		if (a.isEmpty()) {
			return b;
		}
		if (b.isEmpty()) {
			return a;
		}
		Map<String, Local> both = new LinkedHashMap<>(a);
		both.putAll(b);
		return both;
	}

	/**
	 * Lowers an expression as a condition, following the bindings of its patterns: what
	 * {@code a && b} binds when true is in scope in {@code b}, {@code !a} swaps the two
	 * sets, {@code a || b} binds when false.
	 */
	private Flow flow(Expression expression) {
		if (expression instanceof InstanceOf test && test.pattern() != null) {
			return matching(test);
		}
		if (expression instanceof Unary unary && unary.operator().equals("!")) {
			Flow operand = flow(unary.operand());
			Typed value = operand.value();
			if (value.type() != Primitive.BOOLEAN) {
				throw error(unary.pos(), "bad operand type " + value.type() + " for unary operator '!'");
			}
			Typed negated = value.constant() instanceof Boolean truth ? constant(!truth)
					: new Typed(call("not", value.form()), Primitive.BOOLEAN);
			return new Flow(negated, operand.whenFalse(), operand.whenTrue());
		}
		if (expression instanceof Binary binary && (binary.operator().equals("&&") || binary.operator().equals("||"))) {
			boolean and = binary.operator().equals("&&");
			Flow left = test(binary.left());
			Flow right = within(and ? left.whenTrue() : left.whenFalse(), () -> test(binary.right()));
			Typed value;
			if (left.value().constant() instanceof Boolean a && right.value().constant() instanceof Boolean b) {
				value = constant(and ? a && b : a || b);
			}
			else {
				value = new Typed(call(and ? "and" : "or", left.value().form(), right.value().form()),
						Primitive.BOOLEAN);
			}
			return and ? new Flow(value, union(left.whenTrue(), right.whenTrue()), Map.of())
					: new Flow(value, Map.of(), union(left.whenFalse(), right.whenFalse()));
		}
		return new Flow(unbox(expression(expression, null)), Map.of(), Map.of());
	}

	/** A binding of a pattern: a variable of the statement the pattern stands in. */
	private Local patternVariable(String name, JavaType type, int pos) {
		if (variable(name, 0, true) instanceof LocalRef) {
			throw error(pos, "variable " + name + " is already defined");
		}
		String symbol = JavaNames.mangle(name);
		for (Local pending : this.patternVariables) {
			if (pending.symbol().name().equals(symbol)) {
				symbol = symbol + "%" + (this.temporaries++);
				break;
			}
		}
		Local bound = new Local(new LispSymbol(symbol), type);
		bound.initialized = true;
		this.patternVariables.add(bound);
		return bound;
	}

	/**
	 * Runs a lowering and declares the bindings of the patterns it met around its forms.
	 */
	private List<LispVal> patterned(Supplier<List<LispVal>> lowering) {
		List<Local> saved = this.patternVariables;
		this.patternVariables = new ArrayList<>();
		try {
			List<LispVal> forms = lowering.get();
			return this.patternVariables.isEmpty() ? forms : List.of(declared(this.patternVariables, forms));
		}
		finally {
			this.patternVariables = saved;
		}
	}

	private static LispVal declared(List<Local> variables, List<LispVal> body) {
		List<LispVal> bindings = new ArrayList<>();
		for (Local variable : variables) {
			bindings.add(list(variable.symbol(), defaultValue(variable.type())));
		}
		return form(cl("let"), prepend(list(bindings), body));
	}

	/**
	 * A pattern against a value: the tests it makes and the stores of its bindings.
	 * @param value the value's form, one that is evaluated as often as wanted
	 * @param from the value's static type
	 * @param top whether the pattern is the whole of an {@code instanceof}'s, which
	 * {@code null} never matches
	 */
	private void pattern(JavaSyntax.Pattern pattern, LispVal value, JavaType from, boolean top, List<LispVal> tests,
			List<LispVal> stores, Map<String, Local> bindings) {
		switch (pattern) {
			case JavaSyntax.TypePattern written: {
				boolean inferred = written.type().name().equals("var") && written.type().dimensions() == 0;
				JavaType to = inferred ? from : type(written.type());
				if (from.isPrimitive() || to.isPrimitive()) {
					if (!from.equals(to)) {
						throw error(written.pos(), "incompatible types: " + from + " cannot be converted to " + to);
					}
				}
				else if (isSubtype(from, to)) {
					if (top) {
						tests.add(call("not", call("null", value)));
					}
				}
				else {
					tests.add(instanceTest(value, from, to, written.pos(), "a pattern"));
				}
				if (!written.name().equals("_")) {
					Local bound = patternVariable(written.name(), to == JavaType.Null.NULL ? this.objectType : to,
							written.pos());
					stores.add(call("setq", bound.symbol(), value));
					bindings.put(written.name(), bound);
				}
				return;
			}
			case JavaSyntax.RecordPattern written: {
				if (!(type(written.type()) instanceof ClassType to) || !to.declaration().isRecord) {
					throw error(written.pos(), "deconstruction patterns can only be applied to records");
				}
				JavaClass record = to.declaration();
				tests.add(isSubtype(from, to) ? call("not", call("null", value))
						: instanceTest(value, from, to, written.pos(), "a pattern"));
				List<Field> components = record.fields.stream().filter(field -> !field.isStatic).toList();
				if (components.size() != written.components().size()) {
					throw error(written.pos(), "incorrect number of nested patterns");
				}
				for (int i = 0; i < components.size(); i++) {
					Field component = components.get(i);
					// the accessor a record declares is the one a pattern reads through
					Method accessor = record.methods.stream()
						.filter(method -> method.name.equals(component.name) && method.parameters.isEmpty()
								&& !method.isStatic && method.declaration != null
								&& Objects.requireNonNull(record.declaration).methods().contains(method.declaration))
						.findFirst()
						.orElse(null);
					LispVal read = accessor != null ? list(JavaNames.function(accessor), value)
							: list(JavaNames.accessor(component), value);
					pattern(written.components().get(i), read, component.type, false, tests, stores, bindings);
				}
				return;
			}
		}
	}

	/**
	 * {@code instanceof} with a pattern: the test, storing the bindings when it holds.
	 */
	private Flow matching(InstanceOf test) {
		Typed operand = expression(test.operand(), null);
		if (!operand.type().isReference()) {
			throw error(test.pos(), "unexpected type; required: reference, found: " + operand.type());
		}
		LispSymbol held = isSimple(operand.form()) ? null : temporary();
		List<LispVal> tests = new ArrayList<>();
		List<LispVal> stores = new ArrayList<>();
		Map<String, Local> bindings = new LinkedHashMap<>();
		pattern(Objects.requireNonNull(test.pattern()), held != null ? held : operand.form(), operand.type(), true,
				tests, stores, bindings);
		LispVal form = matched(tests, stores);
		if (held != null) {
			form = JavaForms.let(held, operand.form(), List.of(form));
		}
		return new Flow(new Typed(form, Primitive.BOOLEAN), bindings, Map.of());
	}

	/** The test of a pattern: every test, then the stores, which hold. */
	private static LispVal matched(List<LispVal> tests, List<LispVal> stores) {
		List<LispVal> all = new ArrayList<>(tests);
		if (!stores.isEmpty()) {
			List<LispVal> stored = new ArrayList<>(stores);
			stored.add(T);
			all.add(progn(stored));
		}
		return all.isEmpty() ? T : all.size() == 1 ? all.get(0) : form(cl("and"), all);
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
		return this.classes.type(written, this.currentClass, this.unit);
	}

	private Typed thisValue(int pos) {
		if (this.staticContext) {
			throw error(pos, "non-static variable this cannot be referenced from a static context");
		}
		if (this.early) {
			throw error(pos, "cannot reference this before supertype constructor has been called");
		}
		return new Typed(THIS, new ClassType(this.currentClass, List.of()));
	}

	/** A member that is {@code private} is reached from its own top-level class only. */
	private void checkAccess(boolean isPrivate, JavaClass owner, String what, int pos) {
		if (isPrivate && owner.topLevel() != this.currentClass.topLevel()) {
			throw error(pos, what + " has private access in " + owner.simpleName());
		}
	}

	/** A simple name read as a value. */
	private Typed read(String name, int pos) {
		Named variable = variable(name, pos, false);
		return switch (variable) {
			case null -> throw error(pos, "cannot find symbol: variable " + name);
			case LocalRef found -> {
				requireAssigned(found.local(), name, pos);
				yield new Typed(found.local().symbol(), found.local().type());
			}
			case CaptureRef found -> found.read();
			case FieldRef found -> {
				Field field = found.field();
				if (!field.isStatic) {
					requireInstance(field, found.object(), pos);
				}
				else if (forward(field)) {
					throw error(pos, "illegal forward reference");
				}
				yield fieldRead(field, found.object(), pos);
			}
		};
	}

	private void requireAssigned(Local local, String name, int pos) {
		if (!local.initialized && !this.assigned.contains(local)) {
			throw error(pos, "variable " + name + " might not have been initialized");
		}
	}

	/** Refuses an instance field named where no instance holds it. */
	private void requireInstance(Field field, @Nullable LispVal object, int pos) {
		if (object == null) {
			throw error(pos,
					"non-static variable " + (this.currentClass.isSubclassOf(field.owner) ? "this" : field.name)
							+ " cannot be referenced from a static context");
		}
		if (this.early && object == THIS) {
			throw error(pos, "cannot reference " + field.name + " before supertype constructor has been called");
		}
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
		if (variable(first, 0, true) != null) {
			return null;
		}
		return this.classes.find(name, this.currentClass, this.unit);
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
			case Name name:
				return read(name.identifier(), name.pos());
			case QualifiedThis self: {
				JavaClass named = classNamed(self.qualifier());
				if (named == null) {
					throw error(self.pos(), "cannot find symbol: class " + dotted(self.qualifier()));
				}
				if (this.staticContext) {
					throw error(self.pos(), "non-static variable this cannot be referenced from a static context");
				}
				LispVal instance = enclosing(named);
				if (instance == null) {
					throw error(self.pos(), "not an enclosing class: " + named.simpleName());
				}
				return new Typed(instance, new ClassType(named, List.of()));
			}
			case Select select:
				return select(select);
			case Call call:
				return invocation(call, discard);
			case ConstructorCall call:
				throw error(call.pos(), "explicit constructor invocation may only appear within a constructor body");
			case New creation:
				return creation(creation, expected);
			case NewArray creation: {
				ArrayType type = (ArrayType) type(creation.type());
				if (creation.initializer() != null) {
					return arrayInitializer(creation.initializer(), type);
				}
				List<LispVal> lengths = new ArrayList<>();
				JavaType leaf = type;
				for (Expression written : creation.lengths()) {
					Typed length = unbox(expression(written, null));
					if (length.type() != Primitive.INT && length.type() != Primitive.CHAR) {
						throw error(written.pos(),
								"incompatible types: " + length.type() + " cannot be converted to int");
					}
					lengths.add(length.form());
					leaf = ((ArrayType) leaf).element();
				}
				if (lengths.size() > 1) {
					// every level but the last holds arrays; the last, the leaf's default
					return new Typed(runtimeCall("new-array", form(cl("list"), lengths),
							integer(leaf == Primitive.INT ? 1 : leaf == Primitive.DOUBLE ? 2 : 0), defaultValue(leaf)),
							type);
				}
				List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), lengths.get(0)));
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
				Place place = place(index, false, false);
				return new Typed(place.read(), place.type());
			}
			case Unary unary:
				return unary.operator().equals("!") ? flow(unary).value() : unary(unary, discard);
			case Binary binary:
				return binary.operator().equals("&&") || binary.operator().equals("||") ? flow(binary).value()
						: binary(binary);
			case Assign assignment:
				return assignment(assignment);
			case Conditional conditional:
				return conditional(conditional, expected);
			case Cast cast:
				return cast(cast);
			case InstanceOf test:
				return test.pattern() != null ? flow(test).value() : instanceOf(test);
			case Lambda lambda:
				return lambda(lambda, expected);
			case JavaSyntax.Switch block:
				return switchExpression(block, expected);
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
		return new Typed(arrayOf(type, elements), type);
	}

	/** An array of an element type holding the values of forms. */
	private static LispVal arrayOf(ArrayType type, List<LispVal> elements) {
		List<LispVal> typed = elementType(type.element());
		if (typed.isEmpty()) {
			return form(cl("vector"), elements);
		}
		List<LispVal> made = new ArrayList<>(List.of(cl("make-array"), integer(elements.size())));
		made.addAll(typed);
		made.add(new LispSymbol(":INITIAL-CONTENTS"));
		made.add(form(cl("list"), elements));
		return list(made);
	}

	/** Whether a class is initialized wherever the code being lowered runs. */
	private boolean initialized(JavaClass owner) {
		if (this.launched != null && this.launched.isSubclassOf(owner)) {
			return true;
		}
		for (JavaClass type = this.currentClass; type != null; type = type.isLocal || type.hasOuterInstance()
				? type.outer : null) {
			if (type.isSubclassOf(owner)) {
				return true;
			}
		}
		return false;
	}

	private Typed fieldRead(Field field, @Nullable LispVal object, int pos) {
		if (field.form != null) {
			Object constant = field.form instanceof LispInteger number
					? (field.type == Primitive.LONG ? (Object) number.value() : (Object) (int) number.value())
					: field.form instanceof LispDouble number ? (Object) number.value() : null;
			return new Typed(field.form, field.type, constant);
		}
		checkAccess(field.isPrivate, field.owner, field.name, pos);
		if (!field.isStatic) {
			return new Typed(list(JavaNames.accessor(field), Objects.requireNonNull(object)), field.type);
		}
		Object constant = constantOf(field);
		if (constant != null) {
			return constant instanceof String text ? constantString(text) : constant(constant);
		}
		this.effects.reads.add(field);
		this.effects.uses.add(field.owner);
		if (forward(field)) {
			this.effects.forward.add(field);
		}
		LispVal global = JavaNames.global(field);
		if (this.order.isLazy(field.owner) && !initialized(field.owner)) {
			return new Typed(call("progn", list(JavaNames.initializer(field.owner)), global), field.type);
		}
		return new Typed(global, field.type);
	}

	/**
	 * The value of a constant variable -- a {@code static final} field of a primitive
	 * type or {@code String} whose initializer is a constant expression --, which every
	 * read of it is; {@code null} for any other field.
	 */
	private @Nullable Object constantOf(Field field) {
		Expression initializer = field.initializer;
		if (!field.isStatic || !field.isFinal || initializer == null
				|| !(field.type.isPrimitive() || field.type.equals(this.stringType))) {
			return null;
		}
		Object known = this.constants.get(field);
		if (known == null) {
			// a field read while its own initializer is lowered is no constant
			this.constants.put(field, NOT_CONSTANT);
			Effects saved = this.effects;
			List<Local> savedPatterns = this.patternVariables;
			this.effects = new Effects();
			this.patternVariables = new ArrayList<>();
			known = inContext(field.owner, true, () -> {
				if (!constantShape(initializer)) {
					return NOT_CONSTANT;
				}
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
			this.effects = saved;
			this.patternVariables = savedPatterns;
			this.constants.put(field, known);
		}
		return known == NOT_CONSTANT ? null : known;
	}

	/**
	 * Whether an expression can be a constant expression by its shape: literals, names,
	 * operators, casts and the conditional. Any other is never lowered to find out, so a
	 * class it declares or a method it calls is met once, where its initializer runs.
	 */
	private static boolean constantShape(Expression expression) {
		return switch (expression) {
			case IntLiteral literal -> true;
			case DoubleLiteral literal -> true;
			case CharLiteral literal -> true;
			case StringLiteral literal -> true;
			case BooleanLiteral literal -> true;
			case Name name -> true;
			case Select select -> constantShape(select.target());
			case Unary unary ->
				!unary.operator().equals("++") && !unary.operator().equals("--") && constantShape(unary.operand());
			case Binary binary -> constantShape(binary.left()) && constantShape(binary.right());
			case Conditional conditional -> constantShape(conditional.condition())
					&& constantShape(conditional.whenTrue()) && constantShape(conditional.whenFalse());
			case Cast cast -> constantShape(cast.operand());
			default -> false;
		};
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
		Place place = place(select, false, false);
		return new Typed(place.read(), place.type());
	}

	/**
	 * Where an expression stores: a local, a field, an array element.
	 * @param stable whether the place is read and then written, so that what it is made
	 * of is evaluated once: an operand that is more than a variable is bound first
	 */
	private Place place(Expression expression, boolean stable, boolean assigning) {
		List<LispVal> bindings = new ArrayList<>();
		switch (expression) {
			case Name name: {
				Named variable = variable(name.identifier(), name.pos(), false);
				if (variable instanceof LocalRef found) {
					Local local = found.local();
					if (!assigning) {
						requireAssigned(local, name.identifier(), name.pos());
					}
					return new Place(local.type(), local.symbol(), value -> {
						this.assigned.add(local);
						if (local.isFinal) {
							throw error(name.pos(), "cannot assign a value to final variable " + name.identifier());
						}
						if (local.captured) {
							throw error(name.pos(), "local variables referenced from a lambda expression or an"
									+ " inner class must be final or effectively final");
						}
						if (local.initialized || !assigning) {
							local.reassigned = name.pos();
						}
						return call("setq", local.symbol(), value);
					}, bindings);
				}
				if (variable instanceof CaptureRef found) {
					return new Place(found.read().type(), found.read().form(), value -> {
						throw error(name.pos(),
								"local variables referenced from an inner class must be final or effectively final");
					}, bindings);
				}
				if (!(variable instanceof FieldRef found)) {
					throw error(name.pos(), "cannot find symbol: variable " + name.identifier());
				}
				Field field = found.field();
				if (!field.isStatic && !(assigning && this.early && found.object() == THIS)) {
					requireInstance(field, found.object(), name.pos());
				}
				return fieldPlace(field, found.object(), name.pos(), bindings);
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
				// a field may be given its value ahead of this(..) or super(..)
				Typed object = assigning && this.early && select.target() instanceof This
						? new Typed(THIS, new ClassType(this.currentClass, List.of()))
						: expression(select.target(), null);
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
			// a final field is given its value once, where its class is constructed
			boolean constructed = this.constructing == field.owner && this.constructingStatic == field.isStatic
					&& field.initializer == null && (field.isStatic || object == THIS);
			if (field.form != null || read.constant() != null || (field.isFinal && !constructed)) {
				throw error(pos, "cannot assign a value to final variable " + field.name);
			}
			if (!field.isStatic) {
				return call("setf", read.form(), value);
			}
			this.effects.writes.add(field);
			LispVal global = JavaNames.global(field);
			// the value first, then the class's initialization, then the store
			return read.form() instanceof LispSymbol ? call("setq", global, value)
					: call("setq", global, call("prog1", value, list(JavaNames.initializer(field.owner))));
		};
		return new Place(field.type, read.form(), write, bindings);
	}

	private static LispVal bound(List<LispVal> bindings, LispVal body) {
		return bindings.isEmpty() ? body : list(cl("let*"), list(bindings), body);
	}

	private Typed assignment(Assign assignment) {
		boolean simple = assignment.operator().equals("=");
		Place place = place(assignment.target(), !simple, simple);
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
			Place place = place(unary.operand(), true, false);
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
		Flow flow = test(conditional.condition());
		Typed test = flow.value();
		Typed a = within(flow.whenTrue(), () -> expression(conditional.whenTrue(), expected));
		Typed b = within(flow.whenFalse(), () -> expression(conditional.whenFalse(), expected));
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
		if (expected != null && expected.isReference() && conversion(a, expected, true, false) != null
				&& conversion(b, expected, true, false) != null) {
			return expected;
		}
		JavaType ar = at.isPrimitive() ? JavaIntrinsics.boxed((Primitive) at, this.classes) : at;
		JavaType br = bt.isPrimitive() ? JavaIntrinsics.boxed((Primitive) bt, this.classes) : bt;
		if (ar.isReference() && br.isReference()) {
			if (isSubtype(ar, br)) {
				return br;
			}
			if (isSubtype(br, ar)) {
				return ar;
			}
			return leastUpperBound(ar, br);
		}
		throw error(pos, "incompatible types in a conditional: " + at + " and " + bt);
	}

	/**
	 * The type two unrelated reference types share: the one class or interface both are
	 * under and no other of theirs is under, else {@code Object}.
	 */
	private JavaType leastUpperBound(JavaType a, JavaType b) {
		if (!(a instanceof ClassType left) || !(b instanceof ClassType right)) {
			return this.objectType;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(left.declaration(), above);
		Set<JavaClass> shared = new LinkedHashSet<>();
		supertypes(right.declaration(), shared);
		shared.retainAll(above);
		shared.removeIf(type -> type.intrinsic);
		List<JavaClass> least = shared.stream()
			.filter(type -> shared.stream().noneMatch(other -> other != type && other.isSubclassOf(type)))
			.toList();
		List<JavaClass> classes = least.stream().filter(type -> !type.isInterface).toList();
		JavaClass chosen = classes.size() == 1 ? classes.get(0) : least.size() == 1 ? least.get(0) : null;
		return chosen == null ? this.objectType : new ClassType(chosen, List.of());
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
		JavaType to = type(Objects.requireNonNull(test.type()));
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
		if (method.parameters.size() != lambda.parameters().size()) {
			throw error(lambda.pos(), "incompatible parameter types in lambda expression");
		}
		this.lambdaTargets.add(((ClassType) Objects.requireNonNull(expected)).declaration());
		this.scopes.push(new Scope(null, true));
		Initializing savedInitializing = this.initializing;
		this.initializing = null;
		List<LispVal> parameters = new ArrayList<>();
		for (int i = 0; i < lambda.parameters().size(); i++) {
			Parameter parameter = lambda.parameters().get(i);
			JavaType type = method.parameters.get(i);
			if (parameter.type() != null && !type(parameter.type()).equals(type)) {
				throw error(parameter.pos(), "incompatible parameter types in lambda expression");
			}
			Local bound = bind(parameter.name(), type, parameter.pos());
			bound.initialized = true;
			parameters.add(bound.symbol());
		}
		JavaType savedReturn = this.returnType;
		this.returnType = method.returnType;
		LispVal body;
		Expression written = lambda.expression();
		if (written != null) {
			Deque<Target> savedTargets = new ArrayDeque<>(this.targets);
			this.targets.clear();
			body = progn(patterned(() -> {
				Typed value = expression(written, method.returnType, method.returnType == Primitive.VOID);
				return List.of(method.returnType == Primitive.VOID ? value.form()
						: assign(value, method.returnType, written.pos()));
			}));
			this.targets.addAll(savedTargets);
		}
		else {
			body = functionBody(Objects.requireNonNull(lambda.block()));
		}
		this.returnType = savedReturn;
		this.initializing = savedInitializing;
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
		return parameter instanceof ClassType target && this.classes.find(((New) written).type().name(),
				this.currentClass, this.unit) instanceof JavaClass made && made.isSubclassOf(target.declaration());
	}

	/**
	 * The type a method's parameter list gives the argument at an index.
	 * @param spread whether the last parameter takes the arguments from its index on,
	 * each as an element
	 */
	private static JavaType parameterAt(Method method, int index, List<JavaType> typeArguments, boolean spread) {
		int last = method.parameters.size() - 1;
		if (spread && index >= last) {
			return substitute(((ArrayType) method.parameters.get(last)).element(), typeArguments);
		}
		return substitute(method.parameters.get(index), typeArguments);
	}

	/**
	 * Picks the method a call means: the candidates its arguments fit with no boxing, or
	 * else with it, or else as the elements of a variable arity parameter, and among
	 * those the one whose parameters are the most specific.
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
		boolean spread = false;
		for (int phase = 0; phase < 3 && fitting.isEmpty(); phase++) {
			boolean boxing = phase > 0;
			boolean spreading = phase == 2;
			spread = spreading;
			fitting = candidates.stream().filter(candidate -> {
				if (spreading ? !candidate.varargs || arguments.size() < candidate.parameters.size() - 1
						: candidate.parameters.size() != arguments.size()) {
					return false;
				}
				for (int i = 0; i < arguments.size(); i++) {
					if (!candidate.raw && !accepts(parameterAt(candidate, i, typeArguments, spreading), typed.get(i),
							arguments.get(i), boxing)) {
						return false;
					}
				}
				return true;
			}).toList();
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
		boolean spreads = spread;
		for (Method candidate : fitting) {
			boolean mostSpecific = true;
			for (Method other : fitting) {
				for (int i = 0; i < arguments.size() && mostSpecific; i++) {
					JavaType mine = parameterAt(candidate, i, typeArguments, spreads);
					JavaType theirs = parameterAt(other, i, typeArguments, spreads);
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
			JavaType parameter = parameterAt(chosen, i, typeArguments, spread);
			Typed argument = typed.get(i);
			if (argument == null) {
				argument = expression(arguments.get(i), parameter);
			}
			converted.add(new Typed(chosen.raw ? argument.form() : assign(argument, parameter, arguments.get(i).pos()),
					argument.type(), argument.constant()));
		}
		if (spread) {
			// the arguments past the fixed parameters are the elements of one array
			int fixed = chosen.parameters.size() - 1;
			ArrayType rest = (ArrayType) substitute(chosen.parameters.get(fixed), typeArguments);
			List<LispVal> elements = converted.subList(fixed, converted.size()).stream().map(Typed::form).toList();
			converted = new ArrayList<>(converted.subList(0, fixed));
			converted.add(new Typed(arrayOf(rest, elements), rest));
		}
		return new Resolved(chosen, converted, substitute(chosen.returnType, typeArguments));
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
			checkAccess(body.isPrivate, body.owner, body.signature(), pos);
			this.effects.calls.add(body);
			return new Typed(
					located(form(JavaNames.function(body), prepend(thisValue(pos).form(), resolved.forms())), pos),
					resolved.returnType());
		}
		Typed receiver = null;
		JavaClass in = this.currentClass;
		LispVal path = this.staticContext ? null : THIS;
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
				else if (receiver.type() instanceof ArrayType array) {
					if (call.name().equals("clone") && call.arguments().isEmpty()) {
						return new Typed(call("copy-seq", receiver.form()), array);
					}
					in = this.objectType.declaration();
				}
				else {
					throw error(pos, receiver.type() + " cannot be dereferenced");
				}
			}
		}
		else {
			// the innermost class around the call that has a method of the name
			JavaClass type = this.currentClass;
			LispVal instance = path;
			while (type != null && methods(type, call.name()).isEmpty()) {
				instance = type.hasOuterInstance() && instance != null
						? list(slotAccessor(type, outerSlot(type)), instance) : null;
				type = type.outer;
			}
			if (type != null) {
				in = type;
				path = instance;
			}
		}
		Resolved resolved = resolve(methods(in, call.name()), typeArguments, call.arguments(), "method " + call.name(),
				in, pos);
		Method method = resolved.method();
		checkAccess(method.isPrivate, method.owner, method.signature(), pos);
		if (!method.isStatic && receiver == null) {
			if (qualified || path == null) {
				throw error(pos,
						"non-static method " + method.signature() + " cannot be referenced from a static context");
			}
			receiver = path == THIS ? thisValue(pos) : new Typed(path, new ClassType(in, List.of()));
		}
		if (method.emitter != null) {
			if (method.owner.name.equals("java.io.PrintStream") || method.owner.name.equals("java.lang.IO")) {
				this.effects.visible = true;
			}
			LispVal form = method.emitter.emit(this,
					new Invocation(method.isStatic ? null : receiver, resolved.arguments(), discard, pos));
			if (resolved.returnType().equals(this.stringType) && method.returnType.equals(this.stringType)) {
				this.nonNull.add(form);
			}
			return new Typed(located(form, pos), resolved.returnType());
		}
		LispVal form;
		if (method.isStatic) {
			this.effects.calls.add(method);
			this.effects.uses.add(method.owner);
			form = form(JavaNames.function(method), resolved.forms());
		}
		else {
			form = virtual(in, method, Objects.requireNonNull(receiver).form(), resolved.forms());
		}
		return new Typed(located(form, pos), resolved.returnType());
	}

	/**
	 * A body a call can run, and the class whose instances run it.
	 *
	 * @param tested the class to test the receiver for: the one declaring the body, or
	 * for an interface's default the topmost class inheriting it
	 * @param method the body
	 */
	private record Body(JavaClass tested, Method method) {
	}

	/** The bodies a call of a method on a receiver of a static type can run. */
	private List<Body> bodies(JavaClass receiver, Method method) {
		Map<JavaClass, Method> tested = new LinkedHashMap<>();
		String signature = method.signature();
		for (JavaClass candidate : this.classes.all()) {
			if (candidate.intrinsic || candidate.isAbstract || !candidate.isSubclassOf(receiver)) {
				continue;
			}
			Method body = implementation(candidate, signature);
			if (body == null) {
				continue;
			}
			JavaClass top = body.owner;
			if (top.isInterface) {
				top = candidate;
				while (top.superclass != null && implementation(top.superclass, signature) == body) {
					top = top.superclass;
				}
			}
			tested.put(top, body);
		}
		return tested.entrySet()
			.stream()
			.map(entry -> new Body(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingInt((Body body) -> body.tested().depth()).reversed())
			.toList();
	}

	/**
	 * The function a call of an instance method goes to: the one body under the
	 * receiver's static type, or the function choosing among several by the receiver's
	 * class; {@code null} when the receiver can only be a lambda.
	 */
	private @Nullable LispSymbol target(JavaClass receiver, Method method) {
		if (method.isPrivate) {
			this.effects.calls.add(method);
			return JavaNames.function(method);
		}
		List<Body> bodies = bodies(receiver, method);
		for (Body body : bodies) {
			this.effects.calls.add(body.method());
		}
		Method single = functional(new ClassType(receiver, List.of()));
		boolean lambda = single != null && single.signature().equals(method.signature());
		// what a lambda runs for a method that is not its own: the interface's default
		Method inherited = single != null && !lambda ? implementation(receiver, method.signature()) : null;
		if (inherited != null) {
			this.effects.calls.add(inherited);
		}
		if (bodies.isEmpty() && lambda) {
			return null;
		}
		if (bodies.isEmpty() && inherited != null) {
			return JavaNames.function(inherited);
		}
		if (bodies.size() == 1 && !lambda && (inherited == null || inherited == bodies.get(0).method())) {
			return JavaNames.function(bodies.get(0).method());
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
			else if (inherited != null) {
				arms.add(list(call("functionp", RECEIVER), form(JavaNames.function(inherited), parameters)));
			}
			for (Body body : bodies) {
				arms.add(list(list(JavaNames.predicate(body.tested()), RECEIVER),
						form(JavaNames.function(body.method()), parameters)));
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

	/**
	 * What the function making an instance takes: the instance around an inner class
	 * first, the constructor's arguments, then the locals a local class reads, as they
	 * are where the instance is made.
	 */
	private List<LispVal> instanceArguments(JavaClass made, @Nullable Expression outer, List<LispVal> arguments,
			int pos) {
		List<LispVal> all = new ArrayList<>();
		if (made.hasOuterInstance()) {
			JavaClass around = Objects.requireNonNull(made.outer);
			LispVal instance;
			if (outer != null) {
				Typed given = expression(outer, null);
				if (!(given.type() instanceof ClassType type) || !type.declaration().isSubclassOf(around)) {
					throw error(outer.pos(),
							"incompatible types: " + given.type() + " cannot be converted to " + around.simpleName());
				}
				instance = given.form();
			}
			else {
				instance = enclosing(around);
				if (instance == null) {
					throw error(pos,
							this.staticContext ? "non-static variable this cannot be referenced from a static context"
									: "an enclosing instance that contains " + around.simpleName() + "."
											+ made.simpleName() + " is required");
				}
			}
			all.add(instance);
		}
		else if (outer != null) {
			throw error(pos, "qualified new of static class");
		}
		all.addAll(arguments);
		for (JavaClass type = made; type != null; type = type.superclass) {
			for (JavaClass.Capture capture : List.copyOf(type.captures.values())) {
				all.add(read(capture.name(), pos).form());
			}
		}
		return all;
	}

	private Typed creation(New creation, @Nullable JavaType expected) {
		TypeName written = creation.type();
		int pos = creation.pos();
		boolean constant = this.makingConstant;
		this.makingConstant = false;
		ClassType type;
		if (creation.outer() != null) {
			// outer.new Inner(..): Inner is a member of the outer instance's class
			Typed outer = expression(creation.outer(), null);
			JavaClass member = outer.type() instanceof ClassType around
					? this.classes.find(written.name(), around.declaration(), this.unit) : null;
			if (member == null || !member.hasOuterInstance()) {
				throw error(written.pos(), "cannot find symbol: class " + written.name());
			}
			type = new ClassType(member, List.of());
		}
		else if (written.diamond()) {
			JavaClass made = this.classes.require(written.name(), written.pos(), this.currentClass, this.unit);
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
		if (made.isEnum && !constant) {
			throw error(pos, "enum classes may not be instantiated");
		}
		if (creation.body() != null) {
			return anonymous(creation, made);
		}
		if (made.isAbstract) {
			throw error(pos, made.simpleName() + " is abstract; cannot be instantiated");
		}
		Resolved resolved = resolve(constructors(made), type.arguments(), creation.arguments(),
				"constructor " + made.simpleName(), made, pos);
		if (resolved.method().emitter != null) {
			return new Typed(
					resolved.method().emitter.emit(this, new Invocation(null, resolved.arguments(), false, pos)), type);
		}
		if (!constant) {
			checkAccess(resolved.method().isPrivate, made, made.simpleName() + resolved.method().parameterList(), pos);
		}
		this.effects.calls.add(resolved.method());
		this.effects.uses.add(made);
		return new Typed(located(form(JavaNames.creator(resolved.method()),
				instanceArguments(made, creation.outer(), resolved.forms(), pos)), pos), type);
	}

	/**
	 * {@code new T(..) { .. }}: a class under {@code T}, declared and lowered where it is
	 * made, whose constructor hands its arguments to the one of {@code T} they pick.
	 */
	private Typed anonymous(New creation, JavaClass extended) {
		int pos = creation.pos();
		if (creation.outer() != null) {
			throw unsupported(pos, "an anonymous class made with an outer instance");
		}
		if (extended.intrinsic && !extended.name.equals("java.lang.Object")) {
			throw unsupported(pos, "an anonymous class of " + extended.name);
		}
		JavaClass made = this.classes.local(Objects.requireNonNull(creation.body()), this.currentClass,
				this.staticContext || this.early, extended);
		Method above = null;
		List<LispVal> arguments = List.of();
		if (extended.isInterface || extended.intrinsic) {
			if (!creation.arguments().isEmpty()) {
				throw error(pos, "anonymous class implements interface; cannot have arguments");
			}
		}
		else {
			Resolved resolved = resolve(constructors(extended), List.of(), creation.arguments(),
					"constructor " + extended.simpleName(), extended, pos);
			if (!extended.isEnum) {
				checkAccess(resolved.method().isPrivate, extended,
						extended.simpleName() + resolved.method().parameterList(), pos);
			}
			above = resolved.method();
			arguments = resolved.forms();
		}
		Method passed = above;
		Method constructor = made.methods.stream()
			.filter(method -> method.isConstructor() && method.passesTo == passed)
			.findFirst()
			.orElse(null);
		if (constructor == null) {
			constructor = new Method(made, "<init>", above == null ? List.of() : above.parameters, Primitive.VOID,
					false, false, null, null, false);
			constructor.passesTo = above;
			made.methods.add(constructor);
			this.classes.changed();
		}
		lowerLocal(made);
		this.effects.calls.add(constructor);
		this.effects.uses.add(made);
		return new Typed(
				located(form(JavaNames.creator(constructor), instanceArguments(made, null, arguments, pos)), pos),
				new ClassType(made, List.of()));
	}

	// ---- statements

	private static boolean isTrue(@Nullable Expression condition) {
		return condition == null || (condition instanceof BooleanLiteral literal && literal.value());
	}

	/**
	 * Whether a {@code break} in a statement can leave a loop or a switch around the
	 * statement: one with no label that no loop or switch inside takes, or any with a
	 * label.
	 */
	private static boolean breaksOut(Statement statement, boolean nested) {
		return switch (statement) {
			case Break exit -> exit.label() != null || !nested;
			case Block block -> block.statements().stream().anyMatch(inner -> breaksOut(inner, nested));
			case If branch -> breaksOut(branch.then(), nested)
					|| (branch.otherwise() != null && breaksOut(branch.otherwise(), nested));
			case JavaSyntax.Labeled labeled -> breaksOut(labeled.body(), nested);
			case While loop -> breaksOut(loop.body(), true);
			case DoWhile loop -> breaksOut(loop.body(), true);
			case For loop -> breaksOut(loop.body(), true);
			case ForEach loop -> breaksOut(loop.body(), true);
			case JavaSyntax.Switch block -> block.cases()
				.stream()
				.anyMatch(written -> written.body().stream().anyMatch(inner -> breaksOut(inner, true)));
			case Try attempt -> breaksOut(attempt.body(), nested)
					|| attempt.catches().stream().anyMatch(clause -> breaksOut(clause.body(), nested))
					|| (attempt.finallyBlock() != null && breaksOut(attempt.finallyBlock(), nested));
			default -> false;
		};
	}

	/**
	 * Whether a statement never completes normally, for sure: what follows it is not
	 * reached.
	 */
	private static boolean exits(Statement statement) {
		return switch (statement) {
			case Return exit -> true;
			case Throw exit -> true;
			case Break exit -> true;
			case Continue exit -> true;
			case JavaSyntax.Yield exit -> true;
			case Block block ->
				!block.statements().isEmpty() && exits(block.statements().get(block.statements().size() - 1));
			case If branch -> branch.otherwise() != null && exits(branch.then()) && exits(branch.otherwise());
			case While loop -> isTrue(loop.condition()) && !breaksOut(loop.body(), false);
			case DoWhile loop -> isTrue(loop.condition()) && !breaksOut(loop.body(), false);
			case For loop -> isTrue(loop.condition()) && !breaksOut(loop.body(), false);
			case JavaSyntax.Labeled labeled -> exits(labeled.body()) && !breaksOut(labeled.body(), true);
			case JavaSyntax.Switch block ->
				block.cases().stream().anyMatch(JavaSyntax.SwitchCase::hasDefault) && !block.cases().isEmpty()
						&& block.cases()
							.stream()
							.noneMatch(written -> written.body().stream().anyMatch(inner -> breaksOut(inner, false)))
						&& (block.arrow()
								? block.cases()
									.stream()
									.allMatch(written -> written.value() == null && exits(written.body().get(0)))
								: !block.cases().get(block.cases().size() - 1).body().isEmpty() && block.cases()
									.stream()
									.allMatch(written -> written.body().isEmpty()
											|| exits(written.body().get(written.body().size() - 1))));
			case Try attempt -> (attempt.finallyBlock() != null && exits(attempt.finallyBlock()))
					|| (exits(attempt.body()) && attempt.catches().stream().allMatch(clause -> exits(clause.body())));
			default -> false;
		};
	}

	/**
	 * Whether a statement completes normally, for sure: a method returning a value whose
	 * body does is missing a {@code return}.
	 */
	private static boolean completes(Statement statement) {
		return switch (statement) {
			case ExpressionStatement plain -> true;
			case LocalVariable plain -> true;
			case JavaSyntax.LocalClass plain -> true;
			case Empty plain -> true;
			case Block block ->
				block.statements().stream().noneMatch(JavaLowering::exits) && (block.statements().isEmpty()
						|| completes(block.statements().get(block.statements().size() - 1)));
			case If branch -> branch.otherwise() == null || completes(branch.then()) || completes(branch.otherwise());
			case While loop -> !isTrue(loop.condition());
			case For loop -> !isTrue(loop.condition());
			case ForEach loop -> true;
			default -> false;
		};
	}

	/** The pattern bindings the statement just lowered leaves in scope after it. */
	private Map<String, Local> introduced = Map.of();

	/** The label of the statement about to be lowered, which a loop or a switch takes. */
	private @Nullable String pendingLabel;

	/**
	 * The type the switch expression being lowered has, or {@code null} while it is
	 * found.
	 */
	private @Nullable JavaType yieldType;

	/** The values of the arms of the switch expression whose type is being found. */
	private @Nullable List<Typed> yields;

	private List<LispVal> sequence(List<Statement> statements, int from, boolean tail) {
		return sequence(statements, from, tail, null);
	}

	/**
	 * Lowers the statements of a block from one of them on.
	 * @param tail whether the value of the last one is the value of the function, so that
	 * a {@code return} there is its value and no exit
	 * @param hoisted where the locals declared among the statements go when their scope
	 * is wider than the statements: a switch's block, whose cases share one; {@code null}
	 * for a block, where a declaration is a {@code let} around what follows it
	 */
	private List<LispVal> sequence(List<Statement> statements, int from, boolean tail,
			@Nullable List<LispVal> hoisted) {
		List<LispVal> forms = new ArrayList<>();
		for (int i = from; i < statements.size(); i++) {
			Statement statement = statements.get(i);
			boolean last = i == statements.size() - 1;
			if (i > 0 && exits(statements.get(i - 1))) {
				throw error(statement.pos(), "unreachable statement");
			}
			if (statement instanceof LocalVariable variable) {
				if (hoisted != null) {
					for (Declarator declarator : variable.declarators()) {
						Declared declared = declare(variable, declarator);
						hoisted.add(list(declared.local().symbol(), defaultValue(declared.local().type())));
						if (declarator.initializer() != null) {
							forms.add(located(call("setq", declared.local().symbol(), declared.value()),
									declarator.pos()));
						}
					}
					continue;
				}
				forms.add(located(localVariable(variable, statements, i + 1, tail), variable.pos()));
				return forms;
			}
			List<Local> saved = this.patternVariables;
			this.patternVariables = new ArrayList<>();
			this.introduced = Map.of();
			List<LispVal> lowered;
			boolean rest = false;
			if (tail && !last && hoisted == null && statement instanceof If branch && branch.otherwise() == null
					&& exits(branch.then())) {
				Flow flow = test(branch.condition());
				Set<Local> before = new HashSet<>(this.assigned);
				LispVal then = progn(within(flow.whenTrue(), () -> scoped(branch.then(), true)));
				this.assigned = before;
				Objects.requireNonNull(this.scopes.peek()).locals.putAll(flow.whenFalse());
				lowered = List.of(located(
						call("if", flow.value().form(), then, progn(sequence(statements, i + 1, true))), branch.pos()));
				rest = true;
			}
			else {
				lowered = statement(statement, tail && last);
			}
			List<Local> variables = this.patternVariables;
			this.patternVariables = saved;
			Map<String, Local> left = this.introduced;
			this.introduced = Map.of();
			if (rest) {
				forms.add(variables.isEmpty() ? lowered.get(0) : declared(variables, lowered));
				return forms;
			}
			if (variables.isEmpty()) {
				forms.addAll(lowered);
				continue;
			}
			if (left.isEmpty() || last) {
				forms.add(declared(variables, lowered));
				continue;
			}
			// the bindings the statement leaves are in scope to the end of its block
			Objects.requireNonNull(this.scopes.peek()).locals.putAll(left);
			List<LispVal> body = new ArrayList<>(lowered);
			body.addAll(sequence(statements, i + 1, tail, hoisted));
			forms.add(declared(variables, body));
			return forms;
		}
		return forms;
	}

	/** A declared local and the value it starts with. */
	private record Declared(Local local, LispVal value) {
	}

	private Declared declare(LocalVariable variable, Declarator declarator) {
		boolean inferred = variable.type().name().equals("var") && variable.type().dimensions() == 0;
		JavaType type = inferred ? null : type(variable.type());
		LispVal value;
		Expression initializer = declarator.initializer();
		if (initializer == null) {
			if (type == null) {
				throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
			}
			value = defaultValue(type);
		}
		else {
			List<Local> saved = this.patternVariables;
			this.patternVariables = new ArrayList<>();
			Typed initial = expression(initializer, type);
			if (type == null) {
				type = initial.type();
				if (type == JavaType.Null.NULL || type == Primitive.VOID) {
					throw error(declarator.pos(), "cannot infer type for local variable " + declarator.name());
				}
			}
			value = assign(initial, type, declarator.pos());
			if (!this.patternVariables.isEmpty()) {
				value = declared(this.patternVariables, List.of(value));
			}
			this.patternVariables = saved;
		}
		Local local = bind(declarator.name(), type, declarator.pos());
		local.initialized = initializer != null;
		local.isFinal = variable.isFinal() && initializer != null;
		return new Declared(local, value);
	}

	private LispVal localVariable(LocalVariable variable, List<Statement> statements, int rest, boolean tail) {
		List<LispVal> bindings = new ArrayList<>();
		for (Declarator declarator : variable.declarators()) {
			Declared declared = declare(variable, declarator);
			bindings.add(list(declared.local().symbol(), declared.value()));
		}
		List<LispVal> let = new ArrayList<>();
		let.add(cl(bindings.size() == 1 ? "let" : "let*"));
		let.add(list(bindings));
		let.addAll(sequence(statements, rest, tail));
		return list(let);
	}

	private List<LispVal> scoped(Statement statement, boolean tail) {
		this.scopes.push(new Scope(null, false));
		try {
			return statement instanceof Block block ? sequence(block.statements(), 0, tail)
					: sequence(List.of(statement), 0, tail);
		}
		finally {
			this.scopes.pop();
		}
	}

	private LispVal expressionStatement(Expression expression) {
		boolean allowed = expression instanceof Assign || expression instanceof Call || expression instanceof New
				|| (expression instanceof Unary unary
						&& (unary.operator().equals("++") || unary.operator().equals("--")));
		if (!allowed) {
			throw error(expression.pos(), "not a statement");
		}
		return located(expression(expression, null, true).form(), expression.pos());
	}

	/**
	 * {@code this(..)} or {@code super(..)} where a constructor's body has it: the call,
	 * and after a {@code super(..)} what every instance starts with. The statements ahead
	 * of it were lowered with the instance out of reach.
	 */
	private List<LispVal> constructorCallStatement(ConstructorCall call) {
		if (call != this.explicitCall) {
			throw error(call.pos(), "explicit constructor invocation may only appear within a constructor body");
		}
		JavaClass owner = this.currentClass;
		List<Expression> arguments = new ArrayList<>();
		if (owner.isEnum) {
			if (call.onSuper()) {
				throw error(call.pos(), "call to super not allowed in enum constructor");
			}
			for (String name : ENUM_PARAMETERS) {
				arguments.add(new JavaSyntax.Name(call.pos(), name));
			}
		}
		arguments.addAll(call.arguments());
		List<LispVal> forms = new ArrayList<>();
		JavaClass target = call.onSuper() ? owner.superclass : owner;
		if (target == null) {
			if (!call.arguments().isEmpty()) {
				throw error(call.pos(), "constructor Object in class Object cannot be applied to given types");
			}
		}
		else {
			LispVal form = constructorCall(target, arguments, call.pos());
			if (form != NIL) {
				forms.add(form);
			}
		}
		this.early = false;
		if (call.onSuper()) {
			forms.addAll(instanceInitializers(owner));
		}
		return forms;
	}

	private Target loopTarget() {
		Target target = new Target(this.finallyDepth, this.pendingLabel, true, true, false);
		this.pendingLabel = null;
		return target;
	}

	/** What a {@code break} or a {@code continue} leaves. */
	private Target target(@Nullable String label, boolean continues, int pos) {
		String exit = continues ? "continue" : "break";
		for (Target target : this.targets) {
			if (target.isExpression) {
				throw error(pos, "attempt to " + exit + " out of a switch expression");
			}
			boolean found;
			if (label == null) {
				found = continues ? target.isLoop : target.takesBreak;
			}
			else {
				found = label.equals(target.label);
				if (found && continues && !target.isLoop) {
					throw error(pos, "not a loop label: " + label);
				}
			}
			if (found) {
				if (target.finallyDepth != this.finallyDepth) {
					throw unsupported(pos, "a " + exit + " leaving a finally block");
				}
				return target;
			}
		}
		throw error(pos, label != null ? "undefined label: " + label
				: continues ? "continue outside of loop" : "break outside switch or loop");
	}

	private List<LispVal> statement(Statement statement, boolean tail) {
		switch (statement) {
			case ExpressionStatement expression:
				if (expression.expression() instanceof ConstructorCall call) {
					return constructorCallStatement(call);
				}
				return List.of(expressionStatement(expression.expression()));
			case Block block:
				return scoped(block, tail);
			case Empty empty:
				return List.of();
			case JavaSyntax.LocalClass local: {
				JavaClass made = this.classes.local(local.declaration(), this.currentClass,
						this.staticContext || this.early, null);
				Objects.requireNonNull(this.scopes.peek()).classes.put(made.simpleName(), made);
				lowerLocal(made);
				return List.of();
			}
			case JavaSyntax.Labeled labeled: {
				Statement body = labeled.body();
				if (body instanceof While || body instanceof DoWhile || body instanceof For || body instanceof ForEach
						|| body instanceof JavaSyntax.Switch) {
					this.pendingLabel = labeled.label();
					return statement(body, false);
				}
				Target target = new Target(this.finallyDepth, labeled.label(), false, false, false);
				this.targets.push(target);
				List<LispVal> forms = statement(body, false);
				this.targets.pop();
				return target.breaks ? List.of(form(cl("block"), prepend(target.breakBlock, forms))) : forms;
			}
			case If branch: {
				Flow flow = test(branch.condition());
				Statement otherwise = branch.otherwise();
				Set<Local> before = new HashSet<>(this.assigned);
				LispVal then = progn(within(flow.whenTrue(), () -> scoped(branch.then(), tail)));
				Set<Local> afterThen = this.assigned;
				this.assigned = new HashSet<>(before);
				LispVal form = otherwise == null ? call("if", flow.value().form(), then) : call("if",
						flow.value().form(), then, progn(within(flow.whenFalse(), () -> scoped(otherwise, tail))));
				// assigned after it: by both branches, a branch that never completes
				// aside
				if (exits(branch.then())) {
					if (otherwise != null && exits(otherwise)) {
						this.assigned.addAll(afterThen);
					}
				}
				else if (otherwise != null && exits(otherwise)) {
					this.assigned = afterThen;
				}
				else {
					this.assigned.retainAll(afterThen);
				}
				// a branch that never completes leaves the other's bindings in scope
				if (exits(branch.then()) && (otherwise == null || !exits(otherwise))) {
					this.introduced = flow.whenFalse();
				}
				else if (otherwise != null && exits(otherwise) && !exits(branch.then())) {
					this.introduced = flow.whenTrue();
				}
				else {
					this.introduced = Map.of();
				}
				return List.of(located(form, branch.pos()));
			}
			case While loop: {
				Target target = loopTarget();
				Flow flow = test(loop.condition());
				Set<Local> before = new HashSet<>(this.assigned);
				LispVal form = loop(flow.value().form(),
						() -> within(flow.whenTrue(), () -> scoped(loop.body(), false)), List::of, target);
				if (!isTrue(loop.condition())) {
					// the body may not run
					this.assigned = before;
				}
				this.introduced = target.breaks ? Map.of() : flow.whenFalse();
				return List.of(located(form, loop.pos()));
			}
			case DoWhile loop: {
				// the test runs after the body, where a continue lands too
				Target target = loopTarget();
				LispSymbol again = temporary();
				this.targets.push(target);
				List<LispVal> body = loopBody(scoped(loop.body(), false), target);
				this.targets.pop();
				Flow flow = test(loop.condition());
				body.add(call("setq", again, flow.value().form()));
				LispVal form = JavaForms.let(again, T, List.of(form(cl("while"), prepend(again, body))));
				this.introduced = target.breaks ? Map.of() : flow.whenFalse();
				return List.of(located(target.breaks ? list(cl("block"), target.breakBlock, form) : form, loop.pos()));
			}
			case For loop: {
				Target target = loopTarget();
				this.scopes.push(new Scope(null, false));
				List<LispVal> before = new ArrayList<>();
				List<LispVal> bindings = new ArrayList<>();
				for (Statement initializer : loop.initializers()) {
					if (initializer instanceof LocalVariable variable) {
						for (Declarator declarator : variable.declarators()) {
							Declared declared = declare(variable, declarator);
							bindings.add(list(declared.local().symbol(), declared.value()));
						}
					}
					else {
						before.add(expressionStatement(((ExpressionStatement) initializer).expression()));
					}
				}
				Flow flow = loop.condition() == null ? new Flow(constant(true), Map.of(), Map.of())
						: test(loop.condition());
				Set<Local> ahead = new HashSet<>(this.assigned);
				before.add(loop(flow.value().form(), () -> within(flow.whenTrue(), () -> scoped(loop.body(), false)),
						() -> within(flow.whenTrue(), () -> {
							List<LispVal> forms = new ArrayList<>();
							for (Expression update : loop.updates()) {
								forms.add(expressionStatement(update));
							}
							return forms;
						}), target));
				if (!isTrue(loop.condition())) {
					// the body may not run
					this.assigned = ahead;
				}
				this.scopes.pop();
				LispVal form = bindings.isEmpty() ? progn(before) : form(cl("let*"), prepend(list(bindings), before));
				return List.of(located(form, loop.pos()));
			}
			case ForEach loop:
				return List.of(located(forEach(loop), loop.pos()));
			case Return exit: {
				for (Target target : this.targets) {
					if (target.isExpression) {
						throw error(exit.pos(), "attempt to return out of a switch expression");
					}
				}
				if (Objects.requireNonNull(this.frames.peek()).initializer) {
					throw error(exit.pos(), "return outside method");
				}
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
				Target target = target(exit.label(), false, exit.pos());
				target.breaks = true;
				return List.of(list(cl("return-from"), target.breakBlock, NIL));
			}
			case Continue exit: {
				Target target = target(exit.label(), true, exit.pos());
				target.continues = true;
				return List.of(list(cl("return-from"), target.continueBlock, NIL));
			}
			case JavaSyntax.Yield exit: {
				Target target = null;
				for (Target candidate : this.targets) {
					if (candidate.isExpression) {
						target = candidate;
						break;
					}
				}
				if (target == null) {
					throw error(exit.pos(), "yield outside of switch expression");
				}
				if (target.finallyDepth != this.finallyDepth) {
					throw unsupported(exit.pos(), "a yield leaving a finally block");
				}
				LispVal value = yielded(expression(exit.value(), this.yieldType), exit.value().pos());
				if (tail) {
					return List.of(value);
				}
				target.breaks = true;
				return List.of(located(list(cl("return-from"), YIELD_BLOCK, value), exit.pos()));
			}
			case Throw exit: {
				Typed thrown = expression(exit.value(), null);
				if (!isSubtype(thrown.type(), this.classes.libraryType("java.lang.Throwable"))) {
					throw error(exit.pos(),
							"incompatible types: " + thrown.type() + " cannot be converted to Throwable");
				}
				this.effects.visible = true;
				return List.of(located(raise(thrown.form()), exit.pos()));
			}
			case Try attempt:
				return List.of(located(attempt(attempt), attempt.pos()));
			case JavaSyntax.Switch block:
				return switchStatement(block, tail);
			case LocalVariable variable:
				throw error(variable.pos(), "a variable declaration is not allowed here");
		}
	}

	/** {@code throw}: the object, and the function the report of an uncaught one asks. */
	private LispVal raise(LispVal thrown) {
		JavaClass throwable = this.classes.library("java.lang.Throwable");
		Method describe = methods(throwable, "toString").get(0);
		LispSymbol target = Objects.requireNonNull(target(throwable, describe));
		this.raises = true;
		return runtimeCall("throw", thrown, function(target));
	}

	private List<LispVal> loopBody(List<LispVal> body, Target target) {
		List<LispVal> forms = new ArrayList<>();
		if (target.continues) {
			forms.add(form(cl("block"), prepend(target.continueBlock, body)));
		}
		else {
			forms.addAll(body);
		}
		return forms;
	}

	/** {@code (while test body.. after..)}, inside the blocks its exits need. */
	private LispVal loop(LispVal test, Supplier<List<LispVal>> body, Supplier<List<LispVal>> after, Target target) {
		this.targets.push(target);
		List<LispVal> forms = loopBody(body.get(), target);
		this.targets.pop();
		forms.addAll(after.get());
		LispVal form = form(cl("while"), prepend(test, forms));
		return target.breaks ? list(cl("block"), target.breakBlock, form) : form;
	}

	private LispVal forEach(ForEach loop) {
		Target target = loopTarget();
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
		this.scopes.push(new Scope(null, false));
		Local bound = bind(loop.name(), type, loop.pos());
		bound.initialized = true;
		LispSymbol variable = bound.symbol();
		LispVal value = assign(new Typed(call("aref", items, index), element), type, loop.pos());
		Set<Local> before = new HashSet<>(this.assigned);
		this.targets.push(target);
		List<LispVal> body = scoped(loop.body(), false);
		this.targets.pop();
		this.scopes.pop();
		this.assigned = before;
		List<LispVal> round = loopBody(List.of(JavaForms.let(variable, value, body)), target);
		round.add(call("setq", index, call("+", index, integer(1))));
		List<LispVal> bindings = new ArrayList<>();
		bindings.add(list(items, iterable.form()));
		if (array) {
			bindings.add(list(count, call("length", items)));
		}
		bindings.add(list(index, integer(0)));
		LispVal test = call("<", index, array ? count : call("length", items));
		LispVal form = list(cl("let*"), list(bindings), form(cl("while"), prepend(test, round)));
		return target.breaks ? list(cl("block"), target.breakBlock, form) : form;
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
				this.scopes.push(new Scope(null, false));
				Local bound = bind(clause.name(), type, clause.pos());
				bound.initialized = true;
				List<LispVal> handler = sequence(clause.body().statements(), 0, false);
				this.scopes.pop();
				arms.add(list(list(JavaNames.predicate(type.declaration()), thrown),
						JavaForms.let(bound.symbol(), thrown, handler)));
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

	// ---- switch

	/**
	 * One case of a switch, lowered.
	 *
	 * @param written the case
	 * @param test what holds when the selector is the case's: {@code t} for a default
	 * @param bindings what its patterns bind
	 * @param isDefault whether it is, or includes, {@code default}
	 */
	private record Arm(JavaSyntax.SwitchCase written, LispVal test, Map<String, Local> bindings, boolean isDefault) {
	}

	/**
	 * A switch's selector and cases, lowered.
	 *
	 * @param held the variable holding the selector, or {@code null} when its form is
	 * evaluated as often as wanted
	 * @param value the selector's form
	 * @param arms the cases, in the order written
	 * @param refusesNull the clause a {@code null} selector takes when no case names it,
	 * or {@code null}
	 * @param early whether that clause stands ahead of the cases: a pattern could take a
	 * {@code null}
	 * @param covered whether some case takes every value
	 */
	private record Selection(@Nullable LispSymbol held, LispVal value, List<Arm> arms, @Nullable LispVal refusesNull,
			boolean early, boolean covered) {

		/**
		 * The clauses of the {@code cond} choosing a case, each case's body made by a
		 * function.
		 */
		List<LispVal> clauses(Function<Arm, List<LispVal>> body, @Nullable LispVal otherwise) {
			List<LispVal> clauses = new ArrayList<>();
			clauses.add(cl("cond"));
			if (this.refusesNull != null && this.early) {
				clauses.add(this.refusesNull);
			}
			LispVal last = null;
			for (Arm arm : this.arms) {
				List<LispVal> forms = body.apply(arm);
				LispVal clause = form(arm.test(), forms.isEmpty() ? List.of(NIL) : forms);
				if (arm.isDefault()) {
					last = clause;
				}
				else {
					clauses.add(clause);
				}
			}
			if (this.refusesNull != null && !this.early) {
				clauses.add(this.refusesNull);
			}
			if (last != null) {
				clauses.add(last);
			}
			else if (otherwise != null) {
				clauses.add(list(T, otherwise));
			}
			return clauses;
		}

		LispVal bound(LispVal form) {
			return this.held == null ? form : JavaForms.let(this.held, this.value, List.of(form));
		}

	}

	/** The shape of a pattern, for what a set of them covers. */
	private JavaPatterns.Shape shape(JavaSyntax.Pattern pattern) {
		return switch (pattern) {
			case JavaSyntax.TypePattern written ->
				written.type().name().equals("var") && written.type().dimensions() == 0 ? new JavaPatterns.Any()
						: new JavaPatterns.OfType(type(written.type()));
			case JavaSyntax.RecordPattern written ->
				new JavaPatterns.OfRecord(((ClassType) type(written.type())).declaration(),
						written.components().stream().map(this::shape).toList());
		};
	}

	/**
	 * Lowers a switch's selector and the tests of its cases: an {@code int} or a
	 * {@code char} by {@code eql}, a string by {@code equal}, an enum constant by
	 * {@code eq} to its field, a pattern by its own test.
	 * @param mustCover whether the cases have to take every value: a switch expression, a
	 * statement with a pattern or a {@code null}
	 */
	private Selection selection(JavaSyntax.Switch block, boolean mustCover) {
		Typed selector = expression(block.selector(), null);
		JavaType type = selector.type();
		boolean patterns = block.cases().stream().anyMatch(written -> !written.patterns().isEmpty());
		Typed plain = patterns ? selector : unbox(selector);
		boolean integral = plain.type() == Primitive.INT || plain.type() == Primitive.CHAR;
		boolean strings = type.equals(this.stringType);
		JavaClass constants = type instanceof ClassType named && named.declaration().isEnum ? named.declaration()
				: null;
		if (!integral && !type.isReference()) {
			throw unsupported(block.pos(), "a switch over " + type);
		}
		LispVal value = integral ? plain.form() : selector.form();
		LispSymbol held = isSimple(value) ? null : temporary();
		LispVal subject = held != null ? held : value;
		List<Arm> arms = new ArrayList<>();
		Set<Object> seen = new HashSet<>();
		List<List<JavaPatterns.Shape>> rows = new ArrayList<>();
		boolean hasDefault = false;
		boolean hasNull = false;
		for (JavaSyntax.SwitchCase written : block.cases()) {
			List<LispVal> tests = new ArrayList<>();
			Map<String, Local> bindings = new LinkedHashMap<>();
			if (written.hasNull()) {
				if (!type.isReference()) {
					throw error(written.pos(), "incompatible types: <null> cannot be converted to " + type);
				}
				hasNull = true;
				tests.add(call("null", subject));
			}
			for (Expression constant : written.constants()) {
				Object key;
				if (constants != null) {
					Field named = constant instanceof Name name ? field(constants, name.identifier())
							: constant instanceof Select select && classNamed(select.target()) == constants
									? field(constants, select.name()) : null;
					if (named == null || !named.isConstant) {
						throw error(constant.pos(),
								"an enum switch constant case label must be an enumeration constant");
					}
					key = named;
					tests.add(call("eq", subject, JavaNames.global(named)));
				}
				else {
					Typed label = expression(constant, integral ? plain.type() : type);
					key = label.constant();
					if (key == null) {
						throw error(constant.pos(), "constant expression required");
					}
					if (strings && key instanceof String) {
						tests.add(call("equal", subject, label.form()));
					}
					else if (integral && conversion(label, plain.type(), false, true) != null) {
						key = ((Number) number(key)).longValue();
						tests.add(call("eql", subject, integer((Long) key)));
					}
					else {
						throw error(constant.pos(), "constant label of type " + label.type()
								+ " is not compatible with switch selector type " + type);
					}
				}
				if (!seen.add(key)) {
					throw error(constant.pos(), "duplicate case label");
				}
			}
			for (JavaSyntax.Pattern pattern : written.patterns()) {
				List<LispVal> matches = new ArrayList<>();
				List<LispVal> stores = new ArrayList<>();
				pattern(pattern, subject, type, false, matches, stores, bindings);
				tests.add(matched(matches, stores));
				if (written.guard() == null) {
					rows.add(List.of(shape(pattern)));
				}
			}
			LispVal test = tests.isEmpty() ? T : tests.size() == 1 ? tests.get(0) : form(cl("or"), tests);
			Expression guard = written.guard();
			if (guard != null) {
				Flow guarded = within(bindings, () -> test(guard));
				test = call("and", test, guarded.value().form());
				bindings = union(bindings, guarded.whenTrue());
			}
			hasDefault |= written.hasDefault();
			arms.add(new Arm(written, written.hasDefault() ? T : test, bindings, written.hasDefault()));
		}
		boolean covered = hasDefault;
		if (!covered && constants != null) {
			covered = constants.fields.stream().filter(field -> field.isConstant).allMatch(seen::contains);
		}
		if (!covered && !rows.isEmpty()) {
			covered = JavaPatterns.covers(rows, List.of(type), this::isSubtype);
		}
		if ((mustCover || patterns || hasNull) && !covered) {
			throw error(block.pos(), "the switch " + (mustCover ? "expression" : "statement")
					+ " does not cover all possible input values");
		}
		LispVal refusesNull = type.isReference() && !hasNull ? list(call("null", subject), runtimeCall("null-switch"))
				: null;
		return new Selection(held, value, arms, refusesNull, patterns, covered);
	}

	/**
	 * The statements of a case written with a colon, the {@code break} ending them left
	 * out.
	 */
	private static List<Statement> group(JavaSyntax.SwitchCase written) {
		List<Statement> body = written.body();
		if (!body.isEmpty() && body.get(body.size() - 1) instanceof Break exit && exit.label() == null) {
			return body.subList(0, body.size() - 1);
		}
		return body;
	}

	/**
	 * Whether no case written with a colon runs into the next: each is empty -- its
	 * labels are the next one's too -- or ends where it cannot go on.
	 */
	private static boolean separate(List<Arm> arms) {
		for (int i = 0; i < arms.size() - 1; i++) {
			List<Statement> body = arms.get(i).written().body();
			if (!body.isEmpty() && !exits(body.get(body.size() - 1))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The cases written with a colon as cases of their own: one whose body is empty gives
	 * its labels to the next.
	 */
	private static List<Arm> merged(List<Arm> arms) {
		List<Arm> merged = new ArrayList<>();
		List<LispVal> tests = new ArrayList<>();
		boolean isDefault = false;
		for (int i = 0; i < arms.size(); i++) {
			Arm arm = arms.get(i);
			tests.add(arm.test());
			isDefault |= arm.isDefault();
			if (arm.written().body().isEmpty() && i < arms.size() - 1) {
				continue;
			}
			LispVal test = isDefault ? T : tests.size() == 1 ? tests.get(0) : form(cl("or"), List.copyOf(tests));
			merged.add(new Arm(arm.written(), test, arm.bindings(), isDefault));
			tests.clear();
			isDefault = false;
		}
		return merged;
	}

	private List<LispVal> switchStatement(JavaSyntax.Switch block, boolean tail) {
		Target target = new Target(this.finallyDepth, this.pendingLabel, false, true, false);
		this.pendingLabel = null;
		Selection selection = selection(block, false);
		List<LispVal> hoisted = new ArrayList<>();
		this.targets.push(target);
		this.scopes.push(new Scope(null, false));
		LispVal form;
		if (block.arrow()) {
			form = list(selection.clauses(arm -> within(arm.bindings(), () -> {
				JavaSyntax.SwitchCase written = arm.written();
				return written.value() != null ? List.of(expressionStatement(written.value()))
						: scoped(written.body().get(0), tail);
			}), null));
		}
		else if (separate(selection.arms())) {
			Selection groups = new Selection(selection.held(), selection.value(), merged(selection.arms()),
					selection.refusesNull(), selection.early(), selection.covered());
			form = list(groups
				.clauses(arm -> within(arm.bindings(), () -> sequence(group(arm.written()), 0, tail, hoisted)), null));
		}
		else {
			// a case runs into the next: the cases are numbered, the selector picks the
			// first to run, and each runs when the one picked is no later than it
			LispSymbol first = temporary();
			List<LispVal> picks = new ArrayList<>();
			picks.add(cl("cond"));
			if (selection.refusesNull() != null) {
				picks.add(selection.refusesNull());
			}
			int fallback = selection.arms().size();
			List<LispVal> bodies = new ArrayList<>();
			for (int i = 0; i < selection.arms().size(); i++) {
				Arm arm = selection.arms().get(i);
				if (arm.isDefault()) {
					fallback = i;
				}
				else {
					picks.add(list(arm.test(), integer(i)));
				}
				List<LispVal> body = sequence(arm.written().body(), 0, false, hoisted);
				if (!body.isEmpty()) {
					bodies.add(call("if", call("<=", first, integer(i)), progn(body)));
				}
			}
			picks.add(list(T, integer(fallback)));
			form = JavaForms.let(first, list(picks), bodies);
		}
		this.scopes.pop();
		this.targets.pop();
		if (!hoisted.isEmpty()) {
			form = list(cl("let"), list(hoisted), form);
		}
		if (target.breaks) {
			form = list(cl("block"), target.breakBlock, form);
		}
		return List.of(located(selection.bound(form), block.pos()));
	}

	/** The value of a switch expression's arm, as the expression's type takes it. */
	private LispVal yielded(Typed value, int pos) {
		if (this.yields != null) {
			this.yields.add(value);
			return value.form();
		}
		return assign(value, Objects.requireNonNull(this.yieldType), pos);
	}

	/**
	 * A switch expression: a {@code cond} whose clauses' values are the arms'. Its type
	 * is the one its context gives it; standing alone, the one its arms share, which they
	 * are lowered once to find.
	 */
	private Typed switchExpression(JavaSyntax.Switch block, @Nullable JavaType expected) {
		JavaType type = expected == Primitive.VOID ? null : expected;
		if (type == null) {
			int mark = this.patternVariables.size();
			List<Typed> results = new ArrayList<>();
			switchArms(block, null, results);
			this.patternVariables.subList(mark, this.patternVariables.size()).clear();
			Typed shared = null;
			for (Typed result : results) {
				shared = shared == null ? result : new Typed(NIL, conditionalType(shared, result, null, block.pos()));
			}
			type = shared == null || shared.type() == JavaType.Null.NULL ? this.objectType : shared.type();
		}
		return new Typed(located(switchArms(block, type, null), block.pos()), type);
	}

	private LispVal switchArms(JavaSyntax.Switch block, @Nullable JavaType type, @Nullable List<Typed> results) {
		Target target = new Target(this.finallyDepth, null, false, false, true);
		Selection selection = selection(block, true);
		if (!block.arrow() && !separate(selection.arms())) {
			throw unsupported(block.pos(), "a switch expression whose cases run into the next");
		}
		JavaType savedType = this.yieldType;
		List<Typed> savedYields = this.yields;
		this.yieldType = type;
		this.yields = results;
		List<LispVal> hoisted = new ArrayList<>();
		this.targets.push(target);
		this.scopes.push(new Scope(null, false));
		Selection arms = block.arrow() ? selection : new Selection(selection.held(), selection.value(),
				merged(selection.arms()), selection.refusesNull(), selection.early(), selection.covered());
		LispVal form = list(arms.clauses(arm -> within(arm.bindings(), () -> {
			JavaSyntax.SwitchCase written = arm.written();
			if (written.value() != null) {
				Expression value = written.value();
				return List.of(yielded(expression(value, this.yieldType), value.pos()));
			}
			if (!block.arrow()) {
				return sequence(written.body(), 0, true, hoisted);
			}
			return scoped(written.body().get(0), true);
		}), runtimeCall("no-match")));
		this.scopes.pop();
		this.targets.pop();
		this.yieldType = savedType;
		this.yields = savedYields;
		if (!hoisted.isEmpty()) {
			form = list(cl("let"), list(hoisted), form);
		}
		if (target.breaks) {
			form = list(cl("block"), YIELD_BLOCK, form);
		}
		return selection.bound(form);
	}

	// ---- the order of static initialization

	/**
	 * Whether nothing can change what a value of a type holds: a primitive, a string, a
	 * box, and a class of the program every field of which -- its own, the ones above and
	 * the ones of every class under it -- is final and of such a type.
	 */
	private boolean immutable(JavaType type, Set<JavaClass> seen) {
		if (type.isPrimitive()) {
			return true;
		}
		if (!(type instanceof ClassType named)) {
			return false;
		}
		JavaClass declaration = named.declaration();
		if (declaration.intrinsic) {
			return type.equals(this.stringType) || JavaIntrinsics.unboxed(type) != null;
		}
		if (!seen.add(declaration)) {
			return true;
		}
		if (this.lambdaTargets.stream().anyMatch(target -> target.isSubclassOf(declaration))) {
			return false;
		}
		Set<JavaClass> holders = new LinkedHashSet<>();
		for (JavaClass above = declaration; above != null; above = above.superclass) {
			holders.add(above);
		}
		for (JavaClass candidate : this.classes.all()) {
			if (!candidate.intrinsic && candidate.isSubclassOf(declaration)) {
				for (JavaClass above = candidate; above != null; above = above.superclass) {
					holders.add(above);
				}
			}
		}
		for (JavaClass holder : holders) {
			if (!holder.captures.isEmpty() || holder.hasOuterInstance()) {
				return false;
			}
			for (Field field : holder.fields) {
				if (!field.isStatic && (!field.isFinal || !immutable(field.type, seen))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * When each class's static initialization runs ({@link JavaInitOrder}): the constant
	 * variables and the defaults first; then, in the order they need each other, the
	 * classes whose initialization nothing can tell from one made at first use; a class
	 * whose initialization shows is initialized at its first use, by a function its
	 * static methods, the functions making its instances and the reads of its fields from
	 * other classes call first.
	 */
	private List<LispVal> initialization() {
		if (this.order.decide(this.initEffects, this.methodEffects, type -> immutable(type, new HashSet<>()))) {
			this.classes.changed();
		}
		List<LispVal> forms = new ArrayList<>();
		for (JavaClass declaration : this.classes.all()) {
			if (declaration.intrinsic) {
				continue;
			}
			Effects effects = this.initEffects.get(declaration);
			// what the methods the initialization calls read: read ahead of an
			// initializer
			Effects called = new Effects();
			if (effects != null) {
				called.calls.addAll(effects.calls);
				called = JavaInitOrder.reached(called, this.methodEffects);
				called.reads.addAll(effects.forward);
			}
			for (Field field : declaration.fields) {
				if (!field.isStatic) {
					continue;
				}
				Object constant = constantOf(field);
				if (constant != null) {
					forms.add(call("setq", JavaNames.global(field),
							constant instanceof String text ? string(text) : constant(constant).form()));
				}
				else if (field.initializer == null || this.order.isLazy(declaration) || called.reads.contains(field)) {
					// a field read ahead of its initializer holds its type's default
					forms.add(call("setq", JavaNames.global(field), defaultValue(field.type)));
				}
			}
		}
		for (JavaClass declaration : this.order.eager()) {
			forms.addAll(Objects.requireNonNull(this.staticInits.get(declaration)));
		}
		for (JavaClass declaration : this.staticInits.keySet()) {
			if (!this.order.isLazy(declaration)) {
				continue;
			}
			LispSymbol done = JavaNames.initialized(declaration);
			this.variables.add(list(cl("defvar"), done));
			forms.add(call("setq", done, NIL));
			List<LispVal> body = new ArrayList<>();
			body.add(call("setq", done, T));
			LispVal above = declaration.superclass == null ? null : initializationOf(declaration.superclass);
			if (above != null) {
				body.add(above);
			}
			body.addAll(Objects.requireNonNull(this.staticInits.get(declaration)));
			this.functions
				.add(list(cl("defun"), JavaNames.initializer(declaration), NIL, call("if", done, NIL, progn(body))));
		}
		return forms;
	}

}
