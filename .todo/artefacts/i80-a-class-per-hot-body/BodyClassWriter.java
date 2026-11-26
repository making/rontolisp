package am.ik.rontolisp.eval;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.LocatedCons;
import org.jspecify.annotations.Nullable;

/**
 * Writes a compiled body's forms as one class ({@link BodyClass}) on a JVM that defines
 * classes at run time ({@code .kb/interpreter-compiled-bodies.md}): each form's code is
 * what its {@link CompiledForm} node does, laid out where the node would be called, so a
 * run of the body makes no call from one node to the next; the nodes, the layouts and the
 * built-in a call site found bound are the class's constants, so a site that applies that
 * built-in calls its body directly. A node with no code here, and every node past the
 * class's budget, is called as the tree calls it. The native image, which defines no
 * class at run time, runs the tree.
 */
final class BodyClassWriter {

	/** Whether this JVM defines classes at run time: not a native image. */
	static final boolean ENABLED = System.getProperty("org.graalvm.nativeimage.imagecode") == null;

	/** The most code bytes a method may hold and still be compiled by the JIT. */
	private static final int MAX_CODE = Integer.getInteger("rontolisp.interpreter.class-max-code", 7900);

	/** How many forms the first attempt lays out as code; each retry halves it. */
	private static final int FIRST_BUDGET = 1600;

	private static final int LAST_BUDGET = 25;

	private static final ClassDesc SELF = desc("am.ik.rontolisp.eval.BodyClass$Written");

	private static final ClassDesc BODY_CLASS = desc("am.ik.rontolisp.eval.BodyClass");

	private static final ClassDesc ENV = desc("am.ik.rontolisp.eval.Environment");

	private static final ClassDesc VAL = desc("am.ik.rontolisp.LispVal");

	private static final ClassDesc NIL = desc("am.ik.rontolisp.LispNil");

	private static final ClassDesc LOCATED = desc("am.ik.rontolisp.LocatedCons");

	private static final ClassDesc LISP_FUNCTION = desc("am.ik.rontolisp.LispFunction");

	private static final ClassDesc EVALUATOR = desc("am.ik.rontolisp.eval.LispEvaluator");

	private static final ClassDesc EVAL_EXCEPTION = desc("am.ik.rontolisp.eval.LispEvalException");

	private static final ClassDesc LABELS = desc("am.ik.rontolisp.eval.LispEvaluator$TagbodyLabels");

	private static final ClassDesc FORMS = desc("am.ik.rontolisp.eval.CompiledForms");

	private static final ClassDesc FRAMES = desc("am.ik.rontolisp.eval.CompiledFrames");

	private static final ClassDesc ARG_LIST = desc("am.ik.rontolisp.eval.ArgList");

	private static final ClassDesc BINDING = desc("am.ik.rontolisp.eval.FunctionCell$Binding");

	private static final ClassDesc TAIL_CALL = desc("am.ik.rontolisp.eval.CompiledForm$TailCall");

	private static final ClassDesc LEXICAL = desc("am.ik.rontolisp.eval.CompiledForms$Lexical");

	private static final ClassDesc LET = desc("am.ik.rontolisp.eval.CompiledForms$Let");

	private static final ClassDesc SETQ = desc("am.ik.rontolisp.eval.CompiledForms$Setq");

	private static final ClassDesc CALL = desc("am.ik.rontolisp.eval.CompiledForms$Call");

	private static final ClassDesc EXPANSION = desc("am.ik.rontolisp.eval.CompiledForms$Expansion");

	private static final ClassDesc PENDING = desc("am.ik.rontolisp.eval.CompiledForms$Pending");

	private static final ClassDesc FORM = desc("am.ik.rontolisp.eval.CompiledForm");

	private static final ClassDesc STRING_ARRAY = ConstantDescs.CD_String.arrayType();

	private static final ClassDesc VAL_ARRAY = VAL.arrayType();

	private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);

	private static final MethodTypeDesc BOOLEAN = MethodTypeDesc.of(ConstantDescs.CD_boolean);

	private static final MethodTypeDesc RUN = MethodTypeDesc.of(ConstantDescs.CD_Object, ENV, ENV);

	private static final MethodTypeDesc EVAL = MethodTypeDesc.of(VAL, ENV);

	private static final MethodTypeDesc STATEMENT = MethodTypeDesc.of(ConstantDescs.CD_int, ENV, ENV, ENV, LABELS);

	private static final MethodTypeDesc STEP = MethodTypeDesc.of(ConstantDescs.CD_Object, ENV, ENV);

	private static final MethodTypeDesc WALK_TAIL = MethodTypeDesc.of(ConstantDescs.CD_Object, ENV);

	private static final MethodTypeDesc PARENT = MethodTypeDesc.of(ENV);

	private static final MethodTypeDesc SLOT = MethodTypeDesc.of(VAL, ConstantDescs.CD_int);

	private static final MethodTypeDesc SET_SLOT = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, VAL);

	private static final MethodTypeDesc INSTALL_BLOCK = MethodTypeDesc.of(ConstantDescs.CD_void,
			ConstantDescs.CD_String, ENV);

	private static final MethodTypeDesc SCOPE_OF = MethodTypeDesc.of(ConstantDescs.CD_void, ENV);

	private static final MethodTypeDesc SCOPE_OF_LAYOUT = MethodTypeDesc.of(ConstantDescs.CD_void, ENV, STRING_ARRAY,
			VAL_ARRAY);

	private static final MethodTypeDesc NIL_OF = MethodTypeDesc.of(VAL, ENV);

	private static final MethodTypeDesc SCOPED = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object,
			ENV);

	private static final MethodTypeDesc PASSING = MethodTypeDesc.of(EVAL_EXCEPTION, EVAL_EXCEPTION, LOCATED, ENV);

	private static final MethodTypeDesc SETQ_ASSIGN = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
			VAL, ENV);

	private static final MethodTypeDesc CALL_BINDING = MethodTypeDesc.of(BINDING);

	private static final MethodTypeDesc BINDING_VALUE = MethodTypeDesc.of(VAL);

	private static final MethodTypeDesc FALLBACK = MethodTypeDesc.of(PENDING);

	private static final MethodTypeDesc COMPILED_NOW = MethodTypeDesc.of(FORM);

	private static final MethodTypeDesc SLOT_READ = MethodTypeDesc.of(VAL, BINDING, VAL, ConstantDescs.CD_List, ENV);

	private static final MethodTypeDesc APPLIED_IN_PLACE = MethodTypeDesc.of(ConstantDescs.CD_boolean, VAL);

	private static final MethodTypeDesc APPLY = MethodTypeDesc.of(VAL, VAL, ConstantDescs.CD_List, ENV);

	private static final MethodTypeDesc APPLY_BUILTIN = MethodTypeDesc.of(VAL, LISP_FUNCTION,
			desc("java.util.function.Function"), ConstantDescs.CD_List, ENV);

	private static final MethodTypeDesc FRAME_CALL = MethodTypeDesc.of(VAL, EVALUATOR, VAL, ConstantDescs.CD_List, ENV);

	private static final MethodTypeDesc ARG_LIST_OF = MethodTypeDesc.of(ConstantDescs.CD_void, VAL_ARRAY);

	private static final MethodTypeDesc TAIL_CALL_OF = MethodTypeDesc.of(ConstantDescs.CD_void, VAL,
			ConstantDescs.CD_List, ENV, LOCATED);

	private static final MethodTypeDesc FIND_BLOCK = MethodTypeDesc.of(ENV, ConstantDescs.CD_String);

	private static final MethodTypeDesc EXITS_OWNED = MethodTypeDesc.of(ConstantDescs.CD_boolean, VAL, ENV, ENV);

	private static final MethodTypeDesc BLOCK_EXIT = MethodTypeDesc.of(desc("java.lang.RuntimeException"),
			ConstantDescs.CD_String, VAL, ENV);

	private static final ClassHierarchyResolver RESOLVER = ClassHierarchyResolver.defaultResolver()
		.orElse(ClassHierarchyResolver.ofClassLoading(BodyClassWriter.class.getClassLoader()))
		.cached();

	/** The run method's scope parameter. */
	private static final int SCOPE = 1;

	/** The run method's owner parameter. */
	private static final int OWNER = 2;

	/** No local: the slot an absent scope argument would be in. */
	private static final int NONE = -1;

	private final LispEvaluator ev;

	private final List<Object> constants = new ArrayList<>();

	private final IdentityHashMap<Object, Integer> constantIndexes = new IdentityHashMap<>();

	private final List<Handler> handlers = new ArrayList<>();

	private int budget;

	/** The slot a value is stored in on its way into an array. */
	private int temp;

	/**
	 * A handler a region of code gives an error leaving it: the place of the form the
	 * region is the code of ({@link CompiledForms#passing}).
	 *
	 * @param label where the handler's code goes
	 * @param located the form's place
	 * @param env the slot of the scope the form runs in
	 */
	private record Handler(Label label, LocatedCons located, int env) {
	}

	private BodyClassWriter(LispEvaluator ev, int budget) {
		this.ev = ev;
		this.budget = budget;
	}

	private static ClassDesc desc(String name) {
		return ClassDesc.of(name);
	}

	/**
	 * Writes and defines the class of a compiled body, or answers null when it cannot: no
	 * method small enough for the JIT to compile holds its code, or the definition
	 * failed.
	 * @param body the body
	 * @return the class's instance, or null
	 */
	static @Nullable BodyClass write(CompiledBody body) {
		if (!ENABLED) {
			return null;
		}
		try {
			for (int budget = FIRST_BUDGET; budget >= LAST_BUDGET; budget /= 2) {
				BodyClassWriter writer = new BodyClassWriter(body.evaluator(), budget);
				byte[] bytes = writer.build(body);
				int length = codeLength(bytes);
				if (Boolean.getBoolean("rontolisp.interpreter.class-debug")) {
					System.err.println("[body class] budget " + budget + " code " + length + " constants "
							+ writer.constants.size() + " left " + writer.budget);
				}
				if (length <= MAX_CODE) {
					return writer.define(bytes);
				}
			}
			return null;
		}
		catch (RuntimeException | LinkageError failed) {
			if (Boolean.getBoolean("rontolisp.interpreter.class-debug")) {
				failed.printStackTrace();
			}
			return null;
		}
	}

	private byte[] build(CompiledBody body) {
		return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(RESOLVER)).build(SELF, clb -> {
			clb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
			clb.withSuperclass(BODY_CLASS);
			clb.withMethodBody(ConstantDescs.INIT_NAME, VOID, 0, cb -> {
				cb.aload(0);
				cb.invokespecial(BODY_CLASS, ConstantDescs.INIT_NAME, VOID);
				cb.return_();
			});
			clb.withMethodBody("run", RUN, 0, cb -> run(cb, body));
		});
	}

	private BodyClass define(byte[] bytes) {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup()
				.defineHiddenClassWithClassData(bytes, List.copyOf(this.constants), true);
			return (BodyClass) lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class)).invoke();
		}
		catch (RuntimeException | Error e) {
			throw e;
		}
		catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	private static int codeLength(byte[] bytes) {
		ClassModel model = ClassFile.of().parse(bytes);
		int longest = 0;
		for (MethodModel method : model.methods()) {
			longest = Math.max(longest,
					method.findAttribute(Attributes.code()).map(code -> code.codeLength()).orElse(0));
		}
		return longest;
	}

	/** {@link CompiledBody#run}: the statements, then the tail stepped. */
	private void run(CodeBuilder cb, CompiledBody body) {
		this.temp = cb.allocateLocal(TypeKind.REFERENCE);
		CompiledForm last = body.last();
		String blockName = body.blockName();
		if (blockName != null) {
			// a defun's body is one block, established in the call's own scope
			cb.aload(SCOPE);
			constant(cb, blockName, ConstantDescs.CD_String);
			cb.aload(OWNER);
			cb.invokevirtual(ENV, "installBlock", INSTALL_BLOCK);
			for (CompiledForm statement : body.statements()) {
				statement(cb, statement, SCOPE, OWNER);
			}
			if (last == null) {
				clearSpill(cb, SCOPE);
				constant(cb, LispNil.INSTANCE, VAL);
				cb.areturn();
			}
		}
		else {
			for (CompiledForm statement : body.statements()) {
				statement(cb, statement, SCOPE, NONE);
			}
			if (last == null) {
				constant(cb, LispNil.INSTANCE, VAL);
				cb.areturn();
			}
		}
		if (last != null) {
			step(cb, last, SCOPE, OWNER);
		}
		for (Handler handler : this.handlers) {
			cb.labelBinding(handler.label());
			constant(cb, handler.located(), LOCATED);
			cb.aload(handler.env());
			cb.invokestatic(FORMS, "passing", PASSING);
			cb.athrow();
		}
	}

	/** Whether a form's own code fits the budget: one past it is called as the tree. */
	private boolean spend() {
		return --this.budget >= 0;
	}

	// ------------------------------------------------------------------------------------
	// The three positions a form runs in (CompiledForm): each leaves the operand stack as
	// it found it on entry but for what the position answers, and enters every form's
	// code with an empty stack, so a loop's backedge carries none.

	/** {@link CompiledForm#eval}: leaves the value. */
	private void value(CodeBuilder cb, CompiledForm node, int env) {
		if (!spend()) {
			delegateValue(cb, node, env);
			return;
		}
		valueCode(cb, node, env);
	}

	private void valueCode(CodeBuilder cb, CompiledForm node, int env) {
		if (node instanceof CompiledForms.Constant constant) {
			clearSpill(cb, env);
			constant(cb, constant.value, VAL);
		}
		else if (node instanceof CompiledForms.Lexical lexical) {
			lexical(cb, lexical, env);
		}
		else if (node instanceof CompiledForms.If form) {
			region(cb, form.located, env, () -> ifValue(cb, form, env));
		}
		else if (node instanceof CompiledForms.Progn progn) {
			region(cb, progn.located, env, () -> bodyValue(cb, progn.body, env));
		}
		else if (node instanceof CompiledForms.Let let && let.distinct) {
			letValue(cb, let, env);
		}
		else if (node instanceof CompiledForms.Setq setq && !setq.anyFree) {
			region(cb, setq.located, env, () -> setq(cb, setq, env));
		}
		else if (node instanceof CompiledForms.Call call) {
			region(cb, call.located, env, () -> callValue(cb, call, env));
		}
		else if (node instanceof CompiledForms.Expansion expansion) {
			expansionValue(cb, expansion, env);
		}
		else if (node instanceof CompiledForms.While loop) {
			whileLoop(cb, loop, env, NONE);
		}
		else if (node instanceof CompiledForms.Exit exit) {
			exitValue(cb, exit, env);
		}
		else if (node instanceof CompiledForms.Pending pending && pending.compiledNow() instanceof CompiledForm done) {
			Label slow = cb.newLabel();
			Label end = cb.newLabel();
			stillCompiled(cb, pending, done, slow);
			value(cb, done, env);
			cb.goto_(end);
			cb.labelBinding(slow);
			delegateValue(cb, pending, env);
			cb.labelBinding(end);
		}
		else {
			delegateValue(cb, node, env);
		}
	}

	/**
	 * Jumps to {@code slow} unless a pending form's compile is still the one its code was
	 * written from.
	 */
	private void stillCompiled(CodeBuilder cb, CompiledForms.Pending pending, CompiledForm done, Label slow) {
		constant(cb, pending, PENDING);
		cb.invokevirtual(PENDING, "compiledNow", COMPILED_NOW);
		constant(cb, done, FORM);
		cb.if_acmpne(slow);
	}

	/**
	 * {@link CompiledForm#statement} with no labels and no owner: leaves nothing.
	 * @param deferTo the slot of the owner an exit's value form may be handed to, or
	 * {@link #NONE}
	 */
	private void statement(CodeBuilder cb, CompiledForm node, int env, int deferTo) {
		if (!spend()) {
			delegateStatement(cb, node, env, deferTo);
			return;
		}
		if (node instanceof CompiledForms.Constant) {
			clearSpill(cb, env);
		}
		else if (node instanceof CompiledForms.If form) {
			region(cb, form.located, env, () -> ifStatement(cb, form, env, deferTo));
		}
		else if (node instanceof CompiledForms.Progn progn) {
			region(cb, progn.located, env, () -> bodyStatement(cb, progn.body, env, deferTo));
		}
		else if (node instanceof CompiledForms.Let let && let.distinct) {
			letStatement(cb, let, env, deferTo);
		}
		else if (node instanceof CompiledForms.Expansion expansion) {
			expansionStatement(cb, expansion, env, deferTo);
		}
		else if (node instanceof CompiledForms.While loop) {
			whileLoop(cb, loop, env, deferTo);
			cb.pop();
		}
		else if (node instanceof CompiledForms.Lexical || node instanceof CompiledForms.Call
				|| node instanceof CompiledForms.Setq setq && !setq.anyFree) {
			// a form whose statement is its value dropped
			valueCode(cb, node, env);
			cb.pop();
		}
		else if (node instanceof CompiledForms.Exit exit) {
			exitStatement(cb, exit, env, deferTo);
		}
		else if (node instanceof CompiledForms.Pending pending && pending.compiledNow() instanceof CompiledForm done) {
			Label slow = cb.newLabel();
			Label end = cb.newLabel();
			stillCompiled(cb, pending, done, slow);
			statement(cb, done, env, deferTo);
			cb.goto_(end);
			cb.labelBinding(slow);
			delegateStatement(cb, pending, env, deferTo);
			cb.labelBinding(end);
		}
		else {
			delegateStatement(cb, node, env, deferTo);
		}
	}

	/**
	 * {@link CompiledForm#step} and the steps after it: returns what ends the frame's
	 * steps, or the form to step onto next.
	 * @param owner the slot of the exit target of the frame's blocks
	 */
	private void step(CodeBuilder cb, CompiledForm node, int env, int owner) {
		if (!spend()) {
			delegateStep(cb, node, env, owner);
			return;
		}
		if (node instanceof CompiledForms.If form) {
			ifStep(cb, form, env, owner);
		}
		else if (node instanceof CompiledForms.Progn progn) {
			// the statements run in the step of the progn, the last is the next step
			if (progn.body.statements.length > 0) {
				region(cb, progn.located, env, () -> statements(cb, progn.body, env, owner));
			}
			bodyTail(cb, progn.body, env, owner);
		}
		else if (node instanceof CompiledForms.Let let && let.distinct) {
			letStep(cb, let, env, owner);
		}
		else if (node instanceof CompiledForms.Call call) {
			region(cb, call.located, env, () -> callStep(cb, call, env, owner));
		}
		else if (node instanceof CompiledForms.Expansion expansion) {
			expansionStep(cb, expansion, env, owner);
		}
		else if (node instanceof CompiledForms.While loop) {
			whileLoop(cb, loop, env, owner);
			cb.areturn();
		}
		else if (node instanceof CompiledForms.Exit exit) {
			exitStep(cb, exit, env, owner);
		}
		else if (node instanceof CompiledForms.Block block) {
			blockStep(cb, block, env, owner);
		}
		else if (node instanceof CompiledForms.Constant || node instanceof CompiledForms.Lexical
				|| node instanceof CompiledForms.Setq setq && !setq.anyFree) {
			// a form whose step is its value
			valueCode(cb, node, env);
			cb.areturn();
		}
		else if (node instanceof CompiledForms.Pending pending && pending.compiledNow() instanceof CompiledForm done) {
			Label slow = cb.newLabel();
			stillCompiled(cb, pending, done, slow);
			step(cb, done, env, owner);
			cb.labelBinding(slow);
			delegateStep(cb, pending, env, owner);
		}
		else {
			delegateStep(cb, node, env, owner);
		}
	}

	// ------------------------------------------------------------------------------------
	// A node with no code here: called as the tree calls it.

	static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> DELEGATED = new java.util.concurrent.ConcurrentHashMap<>();

	static {
		if (Boolean.getBoolean("rontolisp.interpreter.class-debug")) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				DELEGATED.entrySet()
					.stream()
					.sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
					.forEach(e -> System.err.println("[delegated] " + e.getValue().sum() + " " + e.getKey()));
			}));
		}
	}

	private static final boolean COUNT = Boolean.getBoolean("rontolisp.interpreter.class-debug");

	static void ran(String kind) {
		DELEGATED.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.LongAdder()).increment();
	}

	private void noteDelegated(CodeBuilder cb, CompiledForm node, String mode) {
		if (COUNT) {
			String kind = node.getClass().getSimpleName();
			if (node instanceof CompiledForms.Pending pending) {
				kind += pending.compiledNow() == null ? "(none)"
						: "(" + pending.compiledNow().getClass().getSimpleName() + ")";
			}
			if (this.budget < 0) {
				kind += "[budget]";
			}
			cb.ldc(mode + " " + kind);
			cb.invokestatic(desc("am.ik.rontolisp.eval.BodyClassWriter"), "ran",
					MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
		}
	}

	private void delegateValue(CodeBuilder cb, CompiledForm node, int env) {
		noteDelegated(cb, node, "value");
		ClassDesc type = nodeClass(node);
		constant(cb, node, type);
		cb.aload(env);
		cb.invokevirtual(type, "eval", EVAL);
	}

	private void delegateStatement(CodeBuilder cb, CompiledForm node, int env, int deferTo) {
		noteDelegated(cb, node, "statement");
		ClassDesc type = nodeClass(node);
		constant(cb, node, type);
		cb.aload(env);
		cb.aconst_null();
		loadOrNull(cb, deferTo);
		cb.aconst_null();
		cb.invokevirtual(type, "statement", STATEMENT);
		cb.pop();
	}

	private void delegateStep(CodeBuilder cb, CompiledForm node, int env, int owner) {
		noteDelegated(cb, node, "step");
		ClassDesc type = nodeClass(node);
		region(cb, node.located, env, () -> {
			constant(cb, node, type);
			cb.aload(env);
			cb.aload(owner);
			cb.invokevirtual(type, "step", STEP);
			returnStep(cb, env);
		});
	}

	/**
	 * Returns a step's answer: a form to step onto next in a scope the class made goes
	 * back with its scope.
	 */
	private static void returnStep(CodeBuilder cb, int env) {
		if (env != SCOPE) {
			cb.aload(env);
			cb.invokestatic(FORMS, "scoped", SCOPED);
		}
		cb.areturn();
	}

	/** The class a node's methods are called on: its own, or the nearest one visible. */
	private static ClassDesc nodeClass(CompiledForm node) {
		Class<?> type = node.getClass();
		while (Modifier.isPrivate(type.getModifiers()) || type.isAnonymousClass() || type.isHidden()) {
			type = type.getSuperclass();
		}
		return desc(type.getName());
	}

	// ------------------------------------------------------------------------------------
	// The forms' code.

	/**
	 * Gives an error leaving the code {@code code} emits the place of the form it is the
	 * code of, as the form's node does.
	 */
	private void region(CodeBuilder cb, @Nullable LocatedCons located, int env, Runnable code) {
		if (located == null) {
			code.run();
			return;
		}
		Label start = cb.newBoundLabel();
		code.run();
		Label end = cb.newBoundLabel();
		Label handler = cb.newLabel();
		cb.exceptionCatch(start, end, handler, EVAL_EXCEPTION);
		this.handlers.add(new Handler(handler, located, env));
	}

	private static void clearSpill(CodeBuilder cb, int env) {
		cb.aload(env);
		cb.invokevirtual(ENV, "clearSpill", VOID);
	}

	private static void nil(CodeBuilder cb, int env) {
		cb.aload(env);
		cb.invokestatic(FORMS, "nil", NIL_OF);
	}

	private static void loadOrNull(CodeBuilder cb, int slot) {
		if (slot == NONE) {
			cb.aconst_null();
		}
		else {
			cb.aload(slot);
		}
	}

	/** Loads a constant of the class: an entry of its class data. */
	private void constant(CodeBuilder cb, Object value, ClassDesc type) {
		Integer index = this.constantIndexes.get(value);
		if (index == null) {
			index = this.constants.size();
			this.constants.add(value);
			this.constantIndexes.put(value, index);
		}
		cb.ldc(DynamicConstantDesc.ofNamed(ConstantDescs.BSM_CLASS_DATA_AT, ConstantDescs.DEFAULT_NAME, type, index));
	}

	/** {@code CompiledForms.Lexical#eval}: the slot while the name is lexical. */
	private void lexical(CodeBuilder cb, CompiledForms.Lexical node, int env) {
		Label slow = cb.newLabel();
		Label done = cb.newLabel();
		constant(cb, node, LEXICAL);
		cb.invokevirtual(LEXICAL, "lexicalNow", BOOLEAN);
		cb.ifeq(slow);
		cb.aload(env);
		for (int i = 0; i < node.depth; i++) {
			cb.invokevirtual(ENV, "parent", PARENT);
		}
		cb.loadConstant(node.index);
		cb.invokevirtual(ENV, "slot", SLOT);
		clearSpill(cb, env);
		cb.goto_(done);
		cb.labelBinding(slow);
		delegateValue(cb, node, env);
		cb.labelBinding(done);
	}

	private void ifValue(CodeBuilder cb, CompiledForms.If node, int env) {
		Label otherwise = cb.newLabel();
		Label end = cb.newLabel();
		value(cb, node.test, env);
		cb.instanceOf(NIL);
		cb.ifne(otherwise);
		value(cb, node.then, env);
		cb.goto_(end);
		cb.labelBinding(otherwise);
		if (node.otherwise != null) {
			value(cb, node.otherwise, env);
		}
		else {
			nil(cb, env);
		}
		cb.labelBinding(end);
	}

	private void ifStatement(CodeBuilder cb, CompiledForms.If node, int env, int deferTo) {
		Label otherwise = cb.newLabel();
		Label end = cb.newLabel();
		value(cb, node.test, env);
		cb.instanceOf(NIL);
		cb.ifne(otherwise);
		statement(cb, node.then, env, deferTo);
		cb.goto_(end);
		cb.labelBinding(otherwise);
		if (node.otherwise != null) {
			statement(cb, node.otherwise, env, deferTo);
		}
		else {
			clearSpill(cb, env);
		}
		cb.labelBinding(end);
	}

	private void ifStep(CodeBuilder cb, CompiledForms.If node, int env, int owner) {
		Label otherwise = cb.newLabel();
		// the test is the step of the if, an arm the next step
		region(cb, node.located, env, () -> {
			value(cb, node.test, env);
			cb.instanceOf(NIL);
		});
		cb.ifne(otherwise);
		step(cb, node.then, env, owner);
		cb.labelBinding(otherwise);
		if (node.otherwise != null) {
			step(cb, node.otherwise, env, owner);
		}
		else {
			nil(cb, env);
			cb.areturn();
		}
	}

	/** A body's statements ({@code CompiledForms.Body#statements}). */
	private void statements(CodeBuilder cb, CompiledForms.Body body, int env, int exitTo) {
		for (CompiledForm statement : body.statements) {
			statement(cb, statement, env, exitTo);
		}
	}

	private void bodyValue(CodeBuilder cb, CompiledForms.Body body, int env) {
		statements(cb, body, env, NONE);
		if (body.last == null) {
			nil(cb, env);
		}
		else {
			value(cb, body.last, env);
		}
	}

	private void bodyStatement(CodeBuilder cb, CompiledForms.Body body, int env, int deferTo) {
		statements(cb, body, env, deferTo);
		if (body.last == null) {
			clearSpill(cb, env);
		}
		else {
			statement(cb, body.last, env, deferTo);
		}
	}

	/** A body's last form as the next step, nil for none. */
	private void bodyTail(CodeBuilder cb, CompiledForms.Body body, int env, int owner) {
		if (body.last == null) {
			nil(cb, env);
			cb.areturn();
		}
		else {
			step(cb, body.last, env, owner);
		}
	}

	/**
	 * A {@code let}'s scope ({@code CompiledForms.Let#bind}), in a new slot; a jump to
	 * {@code walk} when a name it binds is special now.
	 */
	private int letScope(CodeBuilder cb, CompiledForms.Let node, int env, Label walk) {
		constant(cb, node, LET);
		cb.invokevirtual(LET, "lexicalNow", BOOLEAN);
		cb.ifeq(walk);
		int values = cb.allocateLocal(TypeKind.REFERENCE);
		cb.loadConstant(node.slots.length);
		cb.anewarray(VAL);
		cb.astore(values);
		for (int i = 0; i < node.inits.length; i++) {
			value(cb, node.inits[i], env);
			store(cb, values, i);
		}
		for (int i = node.inits.length; i < node.slots.length; i++) {
			cb.aload(values);
			cb.loadConstant(i);
			cb.getstatic(ENV, "SPECIAL", VAL);
			cb.aastore();
		}
		int scope = cb.allocateLocal(TypeKind.REFERENCE);
		cb.new_(ENV);
		cb.dup();
		cb.aload(env);
		constant(cb, node.slots, STRING_ARRAY);
		cb.aload(values);
		cb.invokespecial(ENV, ConstantDescs.INIT_NAME, SCOPE_OF_LAYOUT);
		cb.astore(scope);
		return scope;
	}

	/** Stores the value on the stack at a position of the array in a slot. */
	private void store(CodeBuilder cb, int array, int index) {
		cb.astore(this.temp);
		cb.aload(array);
		cb.loadConstant(index);
		cb.aload(this.temp);
		cb.aastore();
	}

	private void letValue(CodeBuilder cb, CompiledForms.Let node, int env) {
		region(cb, node.located, env, () -> {
			Label walk = cb.newLabel();
			Label end = cb.newLabel();
			int scope = letScope(cb, node, env, walk);
			bodyValue(cb, node.body, scope);
			cb.goto_(end);
			cb.labelBinding(walk);
			constant(cb, node, LET);
			cb.aload(env);
			cb.invokevirtual(LET, "walk", EVAL);
			cb.labelBinding(end);
		});
	}

	private void letStatement(CodeBuilder cb, CompiledForms.Let node, int env, int deferTo) {
		region(cb, node.located, env, () -> {
			Label walk = cb.newLabel();
			Label end = cb.newLabel();
			int scope = letScope(cb, node, env, walk);
			bodyStatement(cb, node.body, scope, deferTo);
			cb.goto_(end);
			cb.labelBinding(walk);
			constant(cb, node, LET);
			cb.aload(env);
			cb.aconst_null();
			loadOrNull(cb, deferTo);
			cb.aconst_null();
			cb.invokevirtual(LET, "walkStatement", STATEMENT);
			cb.pop();
			cb.labelBinding(end);
		});
	}

	private void letStep(CodeBuilder cb, CompiledForms.Let node, int env, int owner) {
		Label walk = cb.newLabel();
		int[] scope = new int[1];
		// the binding and the statements are the step of the let, its last form the next
		region(cb, node.located, env, () -> {
			scope[0] = letScope(cb, node, env, walk);
			statements(cb, node.body, scope[0], owner);
		});
		bodyTail(cb, node.body, scope[0], owner);
		cb.labelBinding(walk);
		constant(cb, node, LET);
		cb.aload(env);
		cb.invokevirtual(LET, "walkTail", WALK_TAIL);
		cb.areturn();
	}

	/** {@code CompiledForms.Setq#eval} of a setq whose every target a scope binds. */
	private void setq(CodeBuilder cb, CompiledForms.Setq node, int env) {
		int value = cb.allocateLocal(TypeKind.REFERENCE);
		constant(cb, LispNil.INSTANCE, VAL);
		cb.astore(value);
		for (int i = 0; i < node.values.length; i++) {
			value(cb, node.values[i], env);
			cb.astore(value);
			Label assign = cb.newLabel();
			Label next = cb.newLabel();
			constant(cb, node, SETQ);
			cb.invokevirtual(SETQ, "lexicalNow", BOOLEAN);
			cb.ifeq(assign);
			cb.aload(env);
			for (int d = 0; d < node.depths[i]; d++) {
				cb.invokevirtual(ENV, "parent", PARENT);
			}
			cb.loadConstant(node.indexes[i]);
			cb.aload(value);
			cb.invokevirtual(ENV, "setSlot", SET_SLOT);
			cb.goto_(next);
			cb.labelBinding(assign);
			constant(cb, node, SETQ);
			cb.loadConstant(i);
			cb.aload(value);
			cb.aload(env);
			cb.invokevirtual(SETQ, "assign", SETQ_ASSIGN);
			cb.labelBinding(next);
		}
		clearSpill(cb, env);
		cb.aload(value);
	}

	/**
	 * A call's arguments ({@code CompiledForms.arguments}), as an {@link ArgList} in a
	 * new slot.
	 */
	private int arguments(CodeBuilder cb, CompiledForm[] args, int env) {
		int values = cb.allocateLocal(TypeKind.REFERENCE);
		cb.loadConstant(args.length);
		cb.anewarray(VAL);
		cb.astore(values);
		for (int i = 0; i < args.length; i++) {
			value(cb, args[i], env);
			store(cb, values, i);
		}
		clearSpill(cb, env);
		int list = cb.allocateLocal(TypeKind.REFERENCE);
		cb.new_(ARG_LIST);
		cb.dup();
		cb.aload(values);
		cb.invokespecial(ARG_LIST, ConstantDescs.INIT_NAME, ARG_LIST_OF);
		cb.astore(list);
		return list;
	}

	/**
	 * The built-in a call site's name is bound to now, which the site applies in place:
	 * the class calls its body directly while the name is still bound to it.
	 */
	private static @Nullable LispFunction expectedBuiltin(CompiledForms.Call node) {
		LispVal value = node.cell.binding().value();
		return value instanceof LispFunction builtIn && node.appliedInPlace(builtIn) ? builtIn : null;
	}

	/**
	 * The binding a call reads in a new slot, the fallback's code when there is none:
	 * {@code fallback}'s code leaves what the position answers.
	 */
	private int binding(CodeBuilder cb, CompiledForms.Call node, Runnable fallback) {
		Label bound = cb.newLabel();
		int binding = cb.allocateLocal(TypeKind.REFERENCE);
		constant(cb, node, CALL);
		cb.invokevirtual(CALL, "binding", CALL_BINDING);
		cb.astore(binding);
		cb.aload(binding);
		cb.ifnonnull(bound);
		fallback.run();
		cb.labelBinding(bound);
		return binding;
	}

	/** {@code CompiledForms.Call#eval}. */
	private void callValue(CodeBuilder cb, CompiledForms.Call node, int env) {
		Label end = cb.newLabel();
		int binding = binding(cb, node, () -> {
			constant(cb, node, CALL);
			cb.invokevirtual(CALL, "fallback", FALLBACK);
			cb.aload(env);
			cb.invokevirtual(PENDING, "eval", EVAL);
			cb.goto_(end);
		});
		int function = cb.allocateLocal(TypeKind.REFERENCE);
		cb.aload(binding);
		cb.invokevirtual(BINDING, "value", BINDING_VALUE);
		cb.astore(function);
		int list = arguments(cb, node.args, env);
		LispFunction expected = expectedBuiltin(node);
		if (expected != null) {
			Label general = cb.newLabel();
			cb.aload(function);
			constant(cb, expected, VAL);
			cb.if_acmpne(general);
			applyBuiltin(cb, expected, list, env);
			cb.goto_(end);
			cb.labelBinding(general);
		}
		slotRead(cb, node, binding, function, list, env);
		cb.dup();
		cb.ifnonnull(end);
		cb.pop();
		Label frame = cb.newLabel();
		constant(cb, node, CALL);
		cb.aload(function);
		cb.invokevirtual(CALL, "appliedInPlace", APPLIED_IN_PLACE);
		cb.ifeq(frame);
		applyGeneral(cb, function, list, env);
		cb.goto_(end);
		cb.labelBinding(frame);
		constant(cb, this.ev, EVALUATOR);
		cb.aload(function);
		cb.aload(list);
		cb.aload(env);
		cb.invokestatic(FRAMES, "call", FRAME_CALL);
		cb.labelBinding(end);
	}

	/** {@code CompiledForms.Call#step}. */
	private void callStep(CodeBuilder cb, CompiledForms.Call node, int env, int owner) {
		int binding = binding(cb, node, () -> {
			constant(cb, node, CALL);
			cb.invokevirtual(CALL, "fallback", FALLBACK);
			returnStep(cb, env);
		});
		int function = cb.allocateLocal(TypeKind.REFERENCE);
		cb.aload(binding);
		cb.invokevirtual(BINDING, "value", BINDING_VALUE);
		cb.astore(function);
		int list = arguments(cb, node.args, env);
		LispFunction expected = expectedBuiltin(node);
		if (expected != null) {
			Label general = cb.newLabel();
			cb.aload(function);
			constant(cb, expected, VAL);
			cb.if_acmpne(general);
			applyBuiltin(cb, expected, list, env);
			cb.areturn();
			cb.labelBinding(general);
		}
		Label noSlot = cb.newLabel();
		slotRead(cb, node, binding, function, list, env);
		cb.dup();
		cb.ifnull(noSlot);
		cb.areturn();
		cb.labelBinding(noSlot);
		cb.pop();
		Label tail = cb.newLabel();
		constant(cb, node, CALL);
		cb.aload(function);
		cb.invokevirtual(CALL, "appliedInPlace", APPLIED_IN_PLACE);
		cb.ifeq(tail);
		applyGeneral(cb, function, list, env);
		cb.areturn();
		cb.labelBinding(tail);
		cb.new_(TAIL_CALL);
		cb.dup();
		cb.aload(function);
		cb.aload(list);
		cb.aload(env);
		if (node.located != null) {
			constant(cb, node.located, LOCATED);
		}
		else {
			cb.aconst_null();
		}
		cb.invokespecial(TAIL_CALL, ConstantDescs.INIT_NAME, TAIL_CALL_OF);
		cb.areturn();
	}

	private void applyBuiltin(CodeBuilder cb, LispFunction builtIn, int list, int env) {
		constant(cb, this.ev, EVALUATOR);
		constant(cb, builtIn, LISP_FUNCTION);
		constant(cb, builtIn.body(), desc("java.util.function.Function"));
		cb.aload(list);
		cb.aload(env);
		cb.invokevirtual(EVALUATOR, "applyBuiltin", APPLY_BUILTIN);
	}

	private void applyGeneral(CodeBuilder cb, int function, int list, int env) {
		constant(cb, this.ev, EVALUATOR);
		cb.aload(function);
		cb.aload(list);
		cb.aload(env);
		cb.invokevirtual(EVALUATOR, "apply", APPLY);
	}

	private void slotRead(CodeBuilder cb, CompiledForms.Call node, int binding, int function, int list, int env) {
		constant(cb, node, CALL);
		cb.aload(binding);
		cb.aload(function);
		cb.aload(list);
		cb.aload(env);
		cb.invokevirtual(CALL, "slotRead", SLOT_READ);
	}

	private void expansionValue(CodeBuilder cb, CompiledForms.Expansion node, int env) {
		Label holds = cb.newLabel();
		Label end = cb.newLabel();
		constant(cb, node, EXPANSION);
		cb.invokevirtual(EXPANSION, "holds", BOOLEAN);
		cb.ifne(holds);
		constant(cb, node, EXPANSION);
		cb.invokevirtual(EXPANSION, "fallback", FALLBACK);
		cb.aload(env);
		cb.invokevirtual(PENDING, "eval", EVAL);
		cb.goto_(end);
		cb.labelBinding(holds);
		region(cb, node.located, env, () -> value(cb, node.expansion, env));
		cb.labelBinding(end);
	}

	private void expansionStatement(CodeBuilder cb, CompiledForms.Expansion node, int env, int deferTo) {
		Label holds = cb.newLabel();
		Label end = cb.newLabel();
		constant(cb, node, EXPANSION);
		cb.invokevirtual(EXPANSION, "holds", BOOLEAN);
		cb.ifne(holds);
		constant(cb, node, EXPANSION);
		cb.invokevirtual(EXPANSION, "fallback", FALLBACK);
		cb.aload(env);
		cb.aconst_null();
		loadOrNull(cb, deferTo);
		cb.aconst_null();
		cb.invokevirtual(PENDING, "statement", STATEMENT);
		cb.pop();
		cb.goto_(end);
		cb.labelBinding(holds);
		region(cb, node.located, env, () -> statement(cb, node.expansion, env, deferTo));
		cb.labelBinding(end);
	}

	private void expansionStep(CodeBuilder cb, CompiledForms.Expansion node, int env, int owner) {
		Label fallback = cb.newLabel();
		constant(cb, node, EXPANSION);
		cb.invokevirtual(EXPANSION, "holds", BOOLEAN);
		cb.ifeq(fallback);
		step(cb, node.expansion, env, owner);
		cb.labelBinding(fallback);
		constant(cb, node, EXPANSION);
		cb.invokevirtual(EXPANSION, "fallback", FALLBACK);
		returnStep(cb, env);
	}

	/** {@code CompiledForms.While#loop}: leaves nil. */
	private void whileLoop(CodeBuilder cb, CompiledForms.While node, int env, int exitTo) {
		region(cb, node.located, env, () -> {
			Label top = cb.newBoundLabel();
			Label done = cb.newLabel();
			value(cb, node.test, env);
			cb.instanceOf(NIL);
			cb.ifne(done);
			constant(cb, this.ev, EVALUATOR);
			cb.invokevirtual(EVALUATOR, "pollInterrupt", VOID);
			for (CompiledForm statement : node.body) {
				statement(cb, statement, env, exitTo);
			}
			cb.goto_(top);
			cb.labelBinding(done);
			nil(cb, env);
		});
	}

	/** {@code CompiledForms.Exit#eval}: throws the exit. */
	private void exitValue(CodeBuilder cb, CompiledForms.Exit node, int env) {
		region(cb, node.located, env, () -> {
			if (node.value != null) {
				value(cb, node.value, env);
			}
			else {
				nil(cb, env);
			}
			cb.astore(this.temp);
			// an exit to a block of this frame: the frame's value, which is what its
			// catch
			// of the exit answers
			Label thrown = cb.newLabel();
			cb.aload(env);
			constant(cb, node.name, ConstantDescs.CD_String);
			cb.invokevirtual(ENV, "findBlock", FIND_BLOCK);
			cb.aload(OWNER);
			cb.if_acmpne(thrown);
			cb.aload(this.temp);
			cb.areturn();
			cb.labelBinding(thrown);
			constant(cb, node.name, ConstantDescs.CD_String);
			cb.aload(this.temp);
			cb.aload(env);
			cb.invokestatic(EVALUATOR, "blockExit", BLOCK_EXIT);
			cb.athrow();
		});
	}

	/**
	 * {@code CompiledForms.Exit#statement} with no owner: an exit to a block of this
	 * frame from a statement of its tail continues the frame with its value form, which
	 * the frame's catch of the exit does.
	 */
	private void exitStatement(CodeBuilder cb, CompiledForms.Exit node, int env, int deferTo) {
		CompiledForm value = node.value;
		if (node.valueForm != null && value != null && deferTo != NONE) {
			if (deferTo != OWNER) {
				throw new IllegalStateException("an exit deferred to a frame other than the class's");
			}
			Label exit = cb.newLabel();
			constant(cb, node.designator, VAL);
			cb.aload(env);
			cb.aload(deferTo);
			cb.invokestatic(EVALUATOR, "exitsOwnedBlock", EXITS_OWNED);
			cb.ifeq(exit);
			step(cb, value, env, OWNER);
			cb.labelBinding(exit);
		}
		exitValue(cb, node, env);
	}

	private void exitStep(CodeBuilder cb, CompiledForms.Exit node, int env, int owner) {
		CompiledForm value = node.value;
		if (node.valueForm != null && value != null) {
			// an exit to a block of the frame: its value form is the frame's next step
			Label exit = cb.newLabel();
			constant(cb, node.designator, VAL);
			cb.aload(env);
			cb.aload(owner);
			cb.invokestatic(EVALUATOR, "exitsOwnedBlock", EXITS_OWNED);
			cb.ifeq(exit);
			step(cb, value, env, owner);
			cb.labelBinding(exit);
		}
		exitValue(cb, node, env);
	}

	/** {@code CompiledForms.Block#step}: the block's scope, its body the next steps. */
	private void blockStep(CodeBuilder cb, CompiledForms.Block node, int env, int owner) {
		int scope = cb.allocateLocal(TypeKind.REFERENCE);
		region(cb, node.located, env, () -> {
			cb.new_(ENV);
			cb.dup();
			cb.aload(env);
			cb.invokespecial(ENV, ConstantDescs.INIT_NAME, SCOPE_OF);
			cb.astore(scope);
			cb.aload(scope);
			constant(cb, node.name, ConstantDescs.CD_String);
			cb.aload(owner);
			cb.invokevirtual(ENV, "installBlock", INSTALL_BLOCK);
			statements(cb, node.body, scope, owner);
		});
		bodyTail(cb, node.body, scope, owner);
	}

}
