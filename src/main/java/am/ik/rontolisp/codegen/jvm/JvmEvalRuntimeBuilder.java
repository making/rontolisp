package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispNames;
import am.ik.jvm.ConstantPool;
import org.jspecify.annotations.Nullable;

/**
 * Builds the JVM bytecode for the runtime {@code eval} interpreter and its supporting
 * helpers ({@code _lookup}, {@code _envLookup}, {@code _apply}, {@code _store}).
 *
 * <p>
 * The interpreter is a small tree walker that runs at runtime inside the generated class.
 * It implements a lexical environment plus a persistent global environment. An
 * environment is an association list of bindings, each binding a
 * {@code Object[2]{nameSymbol, value}} cell, with the empty environment being
 * {@code null}. Variable references walk the environment comparing the symbol's string
 * content via {@link String#equals}; if no lexical binding is found the global
 * environment (the static {@code _genv} field) is consulted, then the function registry.
 *
 * <p>
 * Runtime value representation (shared with the compiled output): {@code null} is nil,
 * {@code Long} an integer, {@code Double} a float, a {@code String} a symbol (or a string
 * literal when it starts with {@code "}), an {@code Object[2]} a cons cell, and an
 * {@code Object[]} whose first element is an {@code Integer} a function value. Compiled
 * functions use {@code Object[]{Integer funcId, captures...}}; interpreted closures
 * created by {@code lambda} use the sentinel {@code Object[]{Integer(-1), lambdaTail,
 * capturedEnv}} where {@code lambdaTail = ((params) body...)}.
 */
final class JvmEvalRuntimeBuilder {

	/** A char constant for the {@code "} byte that marks a string literal. */
	private static final int QUOTE_CHAR = '"';

	/**
	 * Holds the constant-pool entries and function table needed to emit the eval runtime.
	 * Built with {@link #builder()} so that each entry is named at the call site rather
	 * than positional. Accessors mirror the names of the fields they expose.
	 */
	static final class EvalConstants {

		private final ConstantPool cp;

		private final ClassEntry objectClass;

		private final ClassEntry objectArrayClass;

		private final ClassEntry integerClass;

		private final ClassEntry longClass;

		private final ClassEntry doubleClass;

		private final ClassEntry stringClass;

		private final MethodRefEntry integerValueOf;

		private final MethodRefEntry integerValue;

		private final MethodRefEntry longValueOf;

		private final MethodRefEntry longValue;

		private final MethodRefEntry stringCharAt;

		private final MethodRefEntry stringLength;

		private final MethodRefEntry objectEquals;

		private final MethodRefEntry evalRef;

		private final MethodRefEntry applyRef;

		private final MethodRefEntry storeRef;

		private final MethodRefEntry envLookupRef;

		private final MethodRefEntry lookupRef;

		private final MethodRefEntry notFnRef;

		private final FieldRefEntry genvField;

		private final FieldRefEntry fenvField;

		private final MethodRefEntry[] invoke;

		private final MethodRefEntry invokeSpread;

		private final Map<String, JvmLispCompiler.FunctionInfo> functions;

		private final boolean complexValues;

		private final @Nullable FieldRefEntry hasComplexField;

		private final @Nullable MethodRefEntry arityChkRef;

		private final JvmArityOperators arityOperators;

		private final ClassEntry thisClass;

		private final boolean hasTrampoline;

		private EvalConstants(Builder b) {
			this.cp = Objects.requireNonNull(b.cp);
			this.objectClass = Objects.requireNonNull(b.objectClass);
			this.objectArrayClass = Objects.requireNonNull(b.objectArrayClass);
			this.integerClass = Objects.requireNonNull(b.integerClass);
			this.longClass = Objects.requireNonNull(b.longClass);
			this.doubleClass = Objects.requireNonNull(b.doubleClass);
			this.stringClass = Objects.requireNonNull(b.stringClass);
			this.integerValueOf = Objects.requireNonNull(b.integerValueOf);
			this.integerValue = Objects.requireNonNull(b.integerValue);
			this.longValueOf = Objects.requireNonNull(b.longValueOf);
			this.longValue = Objects.requireNonNull(b.longValue);
			this.stringCharAt = Objects.requireNonNull(b.stringCharAt);
			this.stringLength = Objects.requireNonNull(b.stringLength);
			this.objectEquals = Objects.requireNonNull(b.objectEquals);
			this.evalRef = Objects.requireNonNull(b.evalRef);
			this.applyRef = Objects.requireNonNull(b.applyRef);
			this.storeRef = Objects.requireNonNull(b.storeRef);
			this.envLookupRef = Objects.requireNonNull(b.envLookupRef);
			this.lookupRef = Objects.requireNonNull(b.lookupRef);
			this.notFnRef = Objects.requireNonNull(b.notFnRef);
			this.genvField = Objects.requireNonNull(b.genvField);
			this.fenvField = Objects.requireNonNull(b.fenvField);
			this.invoke = Objects.requireNonNull(b.invoke);
			this.invokeSpread = Objects.requireNonNull(b.invokeSpread);
			this.functions = Objects.requireNonNull(b.functions);
			this.complexValues = b.complexValues;
			this.hasComplexField = b.hasComplexField;
			this.arityChkRef = b.arityChkRef;
			this.arityOperators = Objects.requireNonNull(b.arityOperators);
			this.thisClass = Objects.requireNonNull(b.thisClass);
			this.hasTrampoline = b.hasTrampoline;
		}

		ConstantPool cp() {
			return this.cp;
		}

		ClassEntry objectClass() {
			return this.objectClass;
		}

		ClassEntry objectArrayClass() {
			return this.objectArrayClass;
		}

		ClassEntry integerClass() {
			return this.integerClass;
		}

		ClassEntry longClass() {
			return this.longClass;
		}

		ClassEntry doubleClass() {
			return this.doubleClass;
		}

		ClassEntry stringClass() {
			return this.stringClass;
		}

		MethodRefEntry integerValueOf() {
			return this.integerValueOf;
		}

		MethodRefEntry integerValue() {
			return this.integerValue;
		}

		MethodRefEntry longValueOf() {
			return this.longValueOf;
		}

		MethodRefEntry longValue() {
			return this.longValue;
		}

		MethodRefEntry stringCharAt() {
			return this.stringCharAt;
		}

		MethodRefEntry stringLength() {
			return this.stringLength;
		}

		MethodRefEntry objectEquals() {
			return this.objectEquals;
		}

		MethodRefEntry evalRef() {
			return this.evalRef;
		}

		MethodRefEntry applyRef() {
			return this.applyRef;
		}

		MethodRefEntry storeRef() {
			return this.storeRef;
		}

		MethodRefEntry envLookupRef() {
			return this.envLookupRef;
		}

		MethodRefEntry lookupRef() {
			return this.lookupRef;
		}

		/** {@code _notFn(Object)}: the exception applying a non-function raises. */
		MethodRefEntry notFnRef() {
			return this.notFnRef;
		}

		FieldRefEntry genvField() {
			return this.genvField;
		}

		FieldRefEntry fenvField() {
			return this.fenvField;
		}

		MethodRefEntry[] invoke() {
			return this.invoke;
		}

		MethodRefEntry invokeSpread() {
			return this.invokeSpread;
		}

		ClassEntry thisClass() {
			return this.thisClass;
		}

		boolean hasTrampoline() {
			return this.hasTrampoline;
		}

		Map<String, JvmLispCompiler.FunctionInfo> functions() {
			return this.functions;
		}

		boolean complexValues() {
			return this.complexValues;
		}

		@Nullable FieldRefEntry hasComplexField() {
			return this.hasComplexField;
		}

		/**
		 * {@code _arityChk(argList, shape)}: the count guard the spread cases carry,
		 * which the runtime's own count checks throw through too. Present wherever
		 * {@code _apply} is built.
		 */
		MethodRefEntry arityChkRef() {
			return Objects.requireNonNull(this.arityChkRef, "_arityChk is built only with _apply");
		}

		/**
		 * The compile's arity-operator registry, still open while the runtime is built.
		 */
		JvmArityOperators arityOperators() {
			return this.arityOperators;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private @Nullable ConstantPool cp;

			private @Nullable ClassEntry objectClass;

			private @Nullable ClassEntry objectArrayClass;

			private @Nullable ClassEntry integerClass;

			private @Nullable ClassEntry longClass;

			private @Nullable ClassEntry doubleClass;

			private @Nullable ClassEntry stringClass;

			private @Nullable MethodRefEntry integerValueOf;

			private @Nullable MethodRefEntry integerValue;

			private @Nullable MethodRefEntry longValueOf;

			private @Nullable MethodRefEntry longValue;

			private @Nullable MethodRefEntry stringCharAt;

			private @Nullable MethodRefEntry stringLength;

			private @Nullable MethodRefEntry objectEquals;

			private @Nullable MethodRefEntry evalRef;

			private @Nullable MethodRefEntry applyRef;

			private @Nullable MethodRefEntry storeRef;

			private @Nullable MethodRefEntry envLookupRef;

			private @Nullable MethodRefEntry lookupRef;

			private @Nullable MethodRefEntry notFnRef;

			private @Nullable FieldRefEntry genvField;

			private @Nullable FieldRefEntry fenvField;

			private MethodRefEntry @Nullable [] invoke;

			private @Nullable MethodRefEntry invokeSpread;

			private @Nullable Map<String, JvmLispCompiler.FunctionInfo> functions;

			private boolean complexValues;

			private @Nullable ClassEntry thisClass;

			private boolean hasTrampoline = false;

			Builder thisClass(ClassEntry thisClass) {
				this.thisClass = thisClass;
				return this;
			}

			Builder hasTrampoline(boolean hasTrampoline) {
				this.hasTrampoline = hasTrampoline;
				return this;
			}

			private @Nullable FieldRefEntry hasComplexField;

			private @Nullable MethodRefEntry arityChkRef;

			private @Nullable JvmArityOperators arityOperators;

			Builder cp(ConstantPool cp) {
				this.cp = cp;
				return this;
			}

			Builder objectClass(ClassEntry c) {
				this.objectClass = c;
				return this;
			}

			Builder objectArrayClass(ClassEntry c) {
				this.objectArrayClass = c;
				return this;
			}

			Builder integerClass(ClassEntry c) {
				this.integerClass = c;
				return this;
			}

			Builder longClass(ClassEntry c) {
				this.longClass = c;
				return this;
			}

			Builder doubleClass(ClassEntry c) {
				this.doubleClass = c;
				return this;
			}

			Builder stringClass(ClassEntry c) {
				this.stringClass = c;
				return this;
			}

			Builder integerValueOf(MethodRefEntry m) {
				this.integerValueOf = m;
				return this;
			}

			Builder integerValue(MethodRefEntry m) {
				this.integerValue = m;
				return this;
			}

			Builder longValueOf(MethodRefEntry m) {
				this.longValueOf = m;
				return this;
			}

			Builder longValue(MethodRefEntry m) {
				this.longValue = m;
				return this;
			}

			Builder stringCharAt(MethodRefEntry m) {
				this.stringCharAt = m;
				return this;
			}

			Builder stringLength(MethodRefEntry m) {
				this.stringLength = m;
				return this;
			}

			Builder objectEquals(MethodRefEntry m) {
				this.objectEquals = m;
				return this;
			}

			Builder evalRef(MethodRefEntry m) {
				this.evalRef = m;
				return this;
			}

			Builder applyRef(MethodRefEntry m) {
				this.applyRef = m;
				return this;
			}

			Builder storeRef(MethodRefEntry m) {
				this.storeRef = m;
				return this;
			}

			Builder envLookupRef(MethodRefEntry m) {
				this.envLookupRef = m;
				return this;
			}

			Builder lookupRef(MethodRefEntry m) {
				this.lookupRef = m;
				return this;
			}

			Builder notFnRef(MethodRefEntry m) {
				this.notFnRef = m;
				return this;
			}

			Builder genvField(FieldRefEntry f) {
				this.genvField = f;
				return this;
			}

			Builder fenvField(FieldRefEntry f) {
				this.fenvField = f;
				return this;
			}

			Builder invoke(MethodRefEntry[] invoke) {
				this.invoke = invoke;
				return this;
			}

			Builder invokeSpread(MethodRefEntry invokeSpread) {
				this.invokeSpread = invokeSpread;
				return this;
			}

			Builder functions(Map<String, JvmLispCompiler.FunctionInfo> functions) {
				this.functions = functions;
				return this;
			}

			Builder complexValues(boolean complexValues) {
				this.complexValues = complexValues;
				return this;
			}

			Builder hasComplexField(@Nullable FieldRefEntry hasComplexField) {
				this.hasComplexField = hasComplexField;
				return this;
			}

			Builder arityChkRef(@Nullable MethodRefEntry arityChkRef) {
				this.arityChkRef = arityChkRef;
				return this;
			}

			Builder arityOperators(JvmArityOperators arityOperators) {
				this.arityOperators = arityOperators;
				return this;
			}

			EvalConstants build() {
				return new EvalConstants(this);
			}

		}

	}

	/** Maximum callable arity, matching the WASM backend. */
	static final int MAX_CALLABLE_ARITY = 7;

	private final Map<String, StringEntry> stringCache = new HashMap<>();

	private final EvalConstants k;

	private JvmEvalRuntimeBuilder(EvalConstants constants) {
		this.k = constants;
	}

	private void ldcStr(MethodCode a, String value) {
		a.ldc(this.stringCache.computeIfAbsent(value, this.k.cp()::stringEntry));
	}

	/** Pushes an int constant, pooled when it is past {@code sipush} range. */
	private void ldcInt(MethodCode a, int value) {
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			a.loadConstant(value);
			return;
		}
		a.ldc(this.k.cp().entries().intEntry(value));
	}

	// === shared high-level emit helpers ===

	/**
	 * Pushes the slot value cast to {@code String}. The version-50 verifier does not
	 * narrow a slot's type across an {@code instanceof} check, so a {@code checkcast} is
	 * required before invoking {@code String} methods on a value held in an
	 * {@code Object} slot.
	 */
	private void aloadStr(MethodCode a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.stringClass());
	}

	/** Pushes {@code ((Object[]) slot)[index]}. */
	private void idx(MethodCode a, int slot, int index) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(index);
		a.aaload();
	}

	/** Pushes {@code ((Object[]) slot).length}. */
	private void arrLen(MethodCode a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.arraylength();
	}

	/** Pushes {@code car} of the cons in {@code slot}. */
	private void car(MethodCode a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
	}

	/** Pushes {@code cdr} of the cons in {@code slot}. */
	private void cdr(MethodCode a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
	}

	/** Pushes {@code _eval(car(slot), env)}. */
	private void evalCar(MethodCode a, int slot, int envSlot) {
		car(a, slot);
		a.aload(envSlot);
		a.invokestatic(this.k.evalRef());
	}

	/** Pushes a fresh {@code Object[2]{ aload(carSlot), aload(cdrSlot) }}. */
	private void consFromSlots(MethodCode a, int carSlot, int cdrSlot) {
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(carSlot);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(cdrSlot);
		a.aastore();
	}

	/**
	 * Emits code that installs a function binding into the {@code _fenv} function
	 * namespace: an existing binding's value cell is mutated, otherwise a new binding is
	 * prepended. Reads the name from {@code nameSlot} and the value from
	 * {@code valueSlot}; clobbers {@code tmpSlot}. Leaves nothing on the stack.
	 */
	private void storeFunctionBinding(MethodCode a, int nameSlot, int valueSlot, int tmpSlot) {
		MethodCode.Label create = a.newLabel();
		MethodCode.Label done = a.newLabel();
		a.aload(nameSlot);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.ifnull(create);
		// existing binding: binding[1] = value
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aload(valueSlot);
		a.aastore();
		a.goto_(done);
		a.labelBinding(create);
		// binding = new Object[]{name, value}
		consFromSlots(a, nameSlot, valueSlot);
		a.astore(tmpSlot);
		// _fenv = new Object[]{binding, _fenv}
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(tmpSlot);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.getstatic(this.k.fenvField());
		a.aastore();
		a.putstatic(this.k.fenvField());
		a.labelBinding(done);
	}

	/**
	 * Emits code that resolves the symbol in {@code nameSlot} against the function
	 * namespace and returns the function value: a runtime {@code defun} binding in
	 * {@code _fenv} first, then the compiled function registry (wrapped as a closure
	 * {@code Object[]{Integer funcId}}), and nil when undefined. Every path ends in
	 * {@code areturn}; clobbers {@code tmpSlot}.
	 */
	private void emitFunctionLookupReturn(MethodCode a, int nameSlot, int tmpSlot) {
		MethodRefEntry toLowerCase = stringCaseRef("toLowerCase");
		MethodRefEntry toUpperCase = stringCaseRef("toUpperCase");
		// Probe the exact spelling first.
		emitFunctionProbeReturn(a, nameSlot, tmpSlot);
		// One case-flip retry: compiled references read upcased (the reader premise)
		// while runtime-read definitions are case-preserved -- and vice versa for a
		// runtime-read reference to a compiled definition. Flip to the lowercase
		// spelling (or, when already lowercase, the uppercase one) and probe again.
		MethodCode.Label realMiss = a.newLabel();
		MethodCode.Label flipped = a.newLabel();
		a.aload(nameSlot);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(toLowerCase);
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.aload(nameSlot);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(flipped);
		a.aload(nameSlot);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(toUpperCase);
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.aload(nameSlot);
		a.invokevirtual(this.k.objectEquals());
		a.ifne(realMiss);
		a.labelBinding(flipped);
		a.aload(tmpSlot);
		a.astore(nameSlot);
		emitFunctionProbeReturn(a, nameSlot, tmpSlot);
		a.labelBinding(realMiss);
		a.aconst_null();
		a.areturn();
	}

	// One probe pass of the function namespace: a runtime defun binding in _fenv, then
	// the compiled function registry; every hit returns, a miss falls through.
	private void emitFunctionProbeReturn(MethodCode a, int nameSlot, int tmpSlot) {
		MethodCode.Label reg = a.newLabel();
		a.aload(nameSlot);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.ifnull(reg);
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(reg);
		MethodCode.Label miss = a.newLabel();
		a.aload(nameSlot);
		a.invokestatic(this.k.lookupRef());
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.ifnull(miss);
		a.loadConstant(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.areturn();
		a.labelBinding(miss);
	}

	// A java/lang/String zero-argument case-conversion methodref.
	private MethodRefEntry stringCaseRef(String method) {
		return this.k.cp().methodRef(this.k.stringClass(), method, "()Ljava/lang/String;");
	}

	/**
	 * Emits {@code aload(opSlot); ldc name; equals; ifeq next} and returns the
	 * {@code next} label. The caller emits the special-form body (which must end in a
	 * return) and then binds {@code next}.
	 */
	private MethodCode.Label special(MethodCode a, int opSlot, String name) {
		MethodCode.Label next = a.newLabel();
		aloadStr(a, opSlot);
		ldcStr(a, name);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(next);
		return next;
	}

	/**
	 * Emits a {@code progn} loop over the list at {@code restSlot}, leaving the last
	 * value (nil for an empty list) in {@code accSlot}. Consumes {@code restSlot}.
	 */
	private void prognInto(MethodCode a, int restSlot, int envSlot, int accSlot) {
		a.aconst_null();
		a.astore(accSlot);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label end = a.newLabel();
		a.labelBinding(loop);
		a.aload(restSlot);
		a.ifnull(end);
		evalCar(a, restSlot, envSlot);
		a.astore(accSlot);
		cdr(a, restSlot);
		a.astore(restSlot);
		a.goto_(loop);
		a.labelBinding(end);
	}

	/**
	 * Emits a loop evaluating each form in the list at {@code restSlot} and linking the
	 * results into a fresh proper list whose head is left in {@code headSlot}. Consumes
	 * {@code restSlot} and clobbers {@code tailSlot}, {@code cellSlot}, {@code tmpSlot}.
	 */
	private void buildArgList(MethodCode a, int restSlot, int envSlot, int headSlot, int tailSlot, int cellSlot,
			int tmpSlot) {
		a.aconst_null();
		a.astore(headSlot);
		a.aconst_null();
		a.astore(tailSlot);
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label end = a.newLabel();
		a.labelBinding(loop);
		a.aload(restSlot);
		a.ifnull(end);
		evalCar(a, restSlot, envSlot);
		a.astore(tmpSlot);
		// cell = cons(tmp, null)
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(tmpSlot);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aconst_null();
		a.aastore();
		a.astore(cellSlot);
		appendCell(a, cellSlot, headSlot, tailSlot);
		cdr(a, restSlot);
		a.astore(restSlot);
		a.goto_(loop);
		a.labelBinding(end);
	}

	/**
	 * Appends the cons in {@code cellSlot} to the list tracked by {@code headSlot}/
	 * {@code tailSlot}.
	 */
	private void appendCell(MethodCode a, int cellSlot, int headSlot, int tailSlot) {
		MethodCode.Label app = a.newLabel();
		MethodCode.Label after = a.newLabel();
		a.aload(headSlot);
		a.ifnonnull(app);
		a.aload(cellSlot);
		a.astore(headSlot);
		a.aload(cellSlot);
		a.astore(tailSlot);
		a.goto_(after);
		a.labelBinding(app);
		a.aload(tailSlot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aload(cellSlot);
		a.aastore();
		a.aload(cellSlot);
		a.astore(tailSlot);
		a.labelBinding(after);
	}

	// === entry points ===

	/**
	 * Builds the {@code _lookup} method body, split into chained segments
	 * ({@code _lookup}, {@code _lookup$1}, ...) so the name-equals chain -- linear in the
	 * number of named functions -- never grows one method past the JVM's 64 KB
	 * method-code limit (60 KB at cl-postgres scale). Each segment falls through to the
	 * next; the last answers null.
	 * @param k the shared constants
	 * @param thisClass the class being emitted
	 * @param dispatchable the funcIds the dispatchers kept a case for, or null for all
	 * @param aliasReachable whether the single-colon alias SPELLING can reach the run
	 * time at all (see the alias loop below)
	 * @param spelledLiterals the literal spellings Pass 2 emitted as runtime values
	 * (JvmLispCompiler.Ctx.spelledLiterals) -- the alias probe reads it
	 * @return the segment bodies
	 */
	static List<MethodCode> buildLookupSegments(EvalConstants k, ClassEntry thisClass,
			java.util.@org.jspecify.annotations.Nullable Set<Integer> dispatchable, boolean aliasReachable,
			Set<String> spelledLiterals) {
		return new JvmEvalRuntimeBuilder(k).lookupSegments(thisClass, dispatchable, aliasReachable, spelledLiterals);
	}

	/** Builds the {@code _envLookup} method body. */
	static MethodCode buildEnvLookup(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).envLookupBody();
	}

	/**
	 * Builds the {@code _apply} method body.
	 * @param k the constants
	 * @param withEval whether the eval runtime is emitted beside it. Without it -- the
	 * APPLY TIER a program with a runtime apply but no eval gets -- no interpreted
	 * closure and no {@code _fenv} binding can exist, so the body has neither arm and
	 * references neither {@code _eval} nor {@code _envLookup}.
	 * @return the body
	 */
	static MethodCode buildApply(EvalConstants k, boolean withEval) {
		return new JvmEvalRuntimeBuilder(k).applyBody(withEval);
	}

	/** Builds the {@code _store} method body. */
	static MethodCode buildStore(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).storeBody();
	}

	/** Builds the {@code _eval} method body. */
	static MethodCode buildEval(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).evalBody();
	}

	// === _lookup(String name) -> Object[]{Integer funcId, Integer arity} or null ===

	/**
	 * Segment budget in code bytes; see {@link #buildLookupSegments}. Below HotSpot's
	 * 8000-bytecode {@code HugeMethodLimit}, the same margin the {@code _invoke_<arity>}
	 * dispatch segments keep -- this registry answers every late-bound function
	 * designator, so a segment over the cliff runs interpreted for the life of the
	 * process ({@code .kb/hot-path-method-size.md}). It used to be 24000, chosen only
	 * against the 64 KB method-code limit, which at clack/ningle scale left four segments
	 * of 24 KB each.
	 */
	private static final int LOOKUP_SEGMENT_BUDGET = 6_000;

	private List<MethodCode> lookupSegments(ClassEntry thisClass,
			java.util.@org.jspecify.annotations.Nullable Set<Integer> dispatchable, boolean aliasReachable,
			Set<String> spelledLiterals) {
		// Only the rows the dispatchers kept a case for: a name whose funcId has no case
		// would resolve here and then fall through the dispatcher's search tree
		// (JvmLispCompiler.dispatchableFuncIds decides both together). Every parameter
		// count answers: a designator's call reaches the SPREAD dispatcher when it is
		// wider than the per-arity ones, and a function of eight or more parameters left
		// out here answered (eval '(f a1 ... a8)) and (funcall 'f ...) with nil or an
		// undefined function.
		List<Map.Entry<String, JvmLispCompiler.FunctionInfo>> entries = new ArrayList<>(this.k.functions()
			.entrySet()
			.stream()
			.filter(e -> dispatchable == null || dispatchable.contains(e.getValue().funcId()))
			.toList());
		// Alias rows for INTERNAL names: a runtime-interned symbol carries
		// the single-colon external spelling (the 2-arg intern/find-symbol lowerings
		// build it -- exportedness is registry knowledge the run time does not have),
		// so an unexported PKG::NAME defun also answers to PKG:NAME. Collision-free:
		// one package cannot house two distinct symbols with one member name.
		// Appended after the base rows so a genuine key always wins.
		//
		// The alias SPELLING has to reach the run time for the row to be worth a name
		// compare and a pool string: a symbol BUILDER assembles it, the reader can read
		// it, or this compile already spells it. With none of those it is a row nothing
		// can match -- the WASM twin's gate, same reasoning.
		for (Map.Entry<String, JvmLispCompiler.FunctionInfo> e : List.copyOf(entries)) {
			int q = e.getKey().indexOf("::");
			if (q > 0) {
				String alias = e.getKey().substring(0, q) + e.getKey().substring(q + 1);
				if (!this.k.functions().containsKey(alias) && (aliasReachable || spelledLiterals.contains(alias))) {
					entries.add(Map.entry(alias, e.getValue()));
				}
			}
		}
		List<MethodCode> segments = new ArrayList<>();
		int index = 0;
		while (true) {
			MethodCode a = new MethodCode();
			while (index < entries.size() && a.size() < LOOKUP_SEGMENT_BUDGET) {
				Map.Entry<String, JvmLispCompiler.FunctionInfo> e = entries.get(index++);
				JvmLispCompiler.FunctionInfo fi = e.getValue();
				MethodCode.Label next = a.newLabel();
				a.aload(0);
				ldcStr(a, e.getKey());
				a.invokevirtual(this.k.objectEquals());
				a.ifeq(next);
				// return new Object[]{ Integer.valueOf(funcId), Integer.valueOf(arity)
				// }; a variadic function is encoded as a negative arity
				// (-physicalParamCount) so the eval call path evaluates every argument
				// instead of exactly arity
				a.loadConstant(2);
				a.anewarray(this.k.objectClass());
				a.dup();
				a.loadConstant(0);
				a.loadConstant(fi.funcId());
				a.invokestatic(this.k.integerValueOf());
				a.aastore();
				a.dup();
				a.loadConstant(1);
				a.loadConstant(fi.variadic() ? -fi.paramCount() : fi.paramCount());
				a.invokestatic(this.k.integerValueOf());
				a.aastore();
				a.areturn();
				a.labelBinding(next);
			}
			if (index < entries.size()) {
				// Continue the chain in the next segment.
				ConstantPool cp = this.k.cp();
				MethodRefEntry nextRef = cp.methodRef(thisClass, "_lookup$" + (segments.size() + 1),
						"(Ljava/lang/Object;)[Ljava/lang/Object;");
				a.aload(0);
				a.invokestatic(nextRef);
				a.areturn();
				segments.add(a);
				continue;
			}
			a.aconst_null();
			a.areturn();
			segments.add(a);
			return segments;
		}
	}

	// === _envLookup(String name, Object env) -> binding cons or null ===

	private MethodCode envLookupBody() {
		MethodCode a = new MethodCode();
		final int NAME = 0, ENV = 1, PAIR = 2, NAMEFIELD = 3;
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label retNull = a.newLabel();
		a.labelBinding(loop);
		a.aload(ENV);
		a.ifnull(retNull);
		car(a, ENV);
		a.astore(PAIR);
		car(a, PAIR);
		a.astore(NAMEFIELD);
		MethodCode.Label skip = a.newLabel();
		a.aload(NAMEFIELD);
		a.instanceOf(this.k.stringClass());
		a.ifeq(skip);
		a.aload(NAMEFIELD);
		a.aload(NAME);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(skip);
		a.aload(PAIR);
		a.areturn();
		a.labelBinding(skip);
		cdr(a, ENV);
		a.astore(ENV);
		a.goto_(loop);
		a.labelBinding(retNull);
		a.aconst_null();
		a.areturn();
		return a;
	}

	// === _apply(Object fn, Object argList) -> value ===

	private MethodCode applyBody(boolean withEval) {
		MethodCode a = new MethodCode();
		final int FN = 0, ARGLIST = 1, ARR = 2, PARAMS = 3, NEWENV = 4, BODY = 5, PAIR = 6, TMP = 7, ARGCUR = 8,
				ARG0 = 9;
		final int FUNCID = 17, LEN = 18;

		// fn == null: NIL names no function -- an undefined-function, never a silent nil
		MethodCode.Label notNull = a.newLabel();
		a.aload(FN);
		a.ifnonnull(notNull);
		emitNotFunctionThrow(a, FN);
		a.labelBinding(notNull);

		// symbol designator (CL-style): a String resolves in the function namespace
		// (_fenv then the compiled registry) and the result replaces fn
		MethodCode.Label notSym = a.newLabel();
		MethodCode.Label resolved = a.newLabel();
		a.aload(FN);
		a.instanceOf(this.k.stringClass());
		a.ifeq(notSym);
		MethodCode.Label desReg = a.newLabel();
		if (withEval) {
			a.aload(FN);
			a.getstatic(this.k.fenvField());
			a.invokestatic(this.k.envLookupRef());
			a.astore(TMP);
			a.aload(TMP);
			a.ifnull(desReg);
			a.aload(TMP);
			a.checkcast(this.k.objectArrayClass());
			a.loadConstant(1);
			a.aaload();
			a.astore(FN);
			a.goto_(resolved);
		}
		a.labelBinding(desReg);
		MethodCode.Label desMiss = a.newLabel();
		a.aload(FN);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.ifnull(desMiss);
		a.loadConstant(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.astore(FN);
		a.goto_(resolved);
		a.labelBinding(desMiss);
		// A symbol that resolves in neither _fenv nor the registry is an undefined
		// function: fail LOUDLY like the funcall dispatcher (returning nil here
		// silently swallowed (apply (intern "NOSUCH") ...)). A quote-framed STRING
		// lands here too, and _notFn reports it as the non-function it is.
		emitNotFunctionThrow(a, FN);
		a.labelBinding(resolved);
		a.labelBinding(notSym);

		// fn instanceof Object[] ?
		MethodCode.Label notArr = a.newLabel();
		a.aload(FN);
		a.instanceOf(this.k.objectArrayClass());
		a.ifeq(notArr);
		a.aload(FN);
		a.checkcast(this.k.objectArrayClass());
		a.astore(ARR);
		// A cons, an instance, an empty vector: an Object[] whose slot 0 is no funcId
		MethodCode.Label isFunction = a.newLabel();
		MethodCode.Label notFunction = a.newLabel();
		a.aload(ARR);
		a.arraylength();
		a.ifeq(notFunction);
		idx(a, ARR, 0);
		a.instanceOf(this.k.integerClass());
		a.ifne(isFunction);
		a.labelBinding(notFunction);
		emitNotFunctionThrow(a, FN);
		a.labelBinding(isFunction);
		idx(a, ARR, 0);
		a.checkcast(this.k.integerClass());
		a.invokevirtual(this.k.integerValue());
		a.istore(FUNCID);

		// interpreted closure? funcId == -1
		MethodCode.Label compiled = a.newLabel();
		// Only the eval runtime builds one.
		if (withEval) {
			a.iload(FUNCID);
			a.loadConstant(-1);
			a.if_icmpne(compiled);
			// arr = {Integer(-1), lambdaTail, capturedEnv}
			idx(a, ARR, 1);
			a.astore(PAIR); // lambdaTail = ((params) body...)
			idx(a, ARR, 2);
			a.astore(NEWENV); // capturedEnv
			car(a, PAIR);
			a.astore(PARAMS);
			cdr(a, PAIR);
			a.astore(BODY);
			emitClosureArityCheck(a, PARAMS, ARGLIST, TMP, LEN);
			// bind params to args
			a.aload(ARGLIST);
			a.astore(ARGCUR);
			MethodCode.Label bloop = a.newLabel();
			MethodCode.Label bend = a.newLabel();
			a.labelBinding(bloop);
			a.aload(PARAMS);
			a.ifnull(bend);
			// pval = argcur == null ? null : car(argcur)
			MethodCode.Label pnull = a.newLabel();
			MethodCode.Label pset = a.newLabel();
			a.aload(ARGCUR);
			a.ifnull(pnull);
			car(a, ARGCUR);
			a.goto_(pset);
			a.labelBinding(pnull);
			a.aconst_null();
			a.labelBinding(pset);
			a.astore(TMP);
			// binding = cons(car(params), pval)
			a.loadConstant(2);
			a.anewarray(this.k.objectClass());
			a.dup();
			a.loadConstant(0);
			car(a, PARAMS);
			a.aastore();
			a.dup();
			a.loadConstant(1);
			a.aload(TMP);
			a.aastore();
			a.astore(PAIR);
			// newenv = cons(binding, newenv)
			consFromSlots(a, PAIR, NEWENV);
			a.astore(NEWENV);
			cdr(a, PARAMS);
			a.astore(PARAMS);
			// argcur = argcur == null ? null : cdr(argcur)
			MethodCode.Label anull = a.newLabel();
			MethodCode.Label aset = a.newLabel();
			a.aload(ARGCUR);
			a.ifnull(anull);
			cdr(a, ARGCUR);
			a.goto_(aset);
			a.labelBinding(anull);
			a.aconst_null();
			a.labelBinding(aset);
			a.astore(ARGCUR);
			a.goto_(bloop);
			a.labelBinding(bend);
			prognInto(a, BODY, NEWENV, TMP);
			a.aload(TMP);
			a.areturn();
		}

		// compiled closure: one call, any argument count. The SPREAD dispatcher takes
		// the list whole and each case reads its target's required parameters out of it,
		// handing a variadic target the remaining tail; its _arityChk judges the count
		// and the list's properness. The per-arity dispatchers cannot serve apply -- they
		// take one JVM parameter per Lisp argument, so they stop at MAX_CALLABLE_ARITY,
		// and an apply past it used to fall off the ladder and answer nil. (A length walk
		// here, left over from that ladder, cast every cell and raised a type-error on
		// an improper list before the dispatcher could report it.)
		a.labelBinding(compiled);
		a.aload(FN);
		a.aload(ARGLIST);
		a.invokestatic(this.k.invokeSpread());
		if (this.k.hasTrampoline()) {
			// The case answered the target's result, a trampoline bounce when the
			// target's own tail was through a value: _apply's answer is the loop's
			// (JvmTailBounce), so every caller of _apply sees a real value.
			JvmTailBounce.unwrapRaw(a, this.k.cp(), this.k.thisClass(), this.k.objectArrayClass(), true);
		}
		a.areturn();

		a.labelBinding(notArr);
		emitNotFunctionThrow(a, FN);
		return a;
	}

	/**
	 * Emits the wrong-count check of an interpreted closure: {@code _arityChk(argList,
	 * 2 * params)}, reported as {@code Function expects N argument(s), got M} like any
	 * anonymous callee. A lambda list with a {@code &}-marker has no count checked: the
	 * runtime {@code lambda} binds such a list positionally (a documented limitation), so
	 * its parameter count is no count the call has to match. Its list is still walked
	 * with the shape {@code (0, variadic)}, which only an {@code apply}'s improper last
	 * argument can fail.
	 */
	private void emitClosureArityCheck(MethodCode a, int paramsSlot, int argListSlot, int cursorSlot, int countSlot) {
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label counted = a.newLabel();
		MethodCode.Label nextParam = a.newLabel();
		MethodCode.Label unchecked = a.newLabel();
		a.loadConstant(0);
		a.istore(countSlot);
		a.aload(paramsSlot);
		a.astore(cursorSlot);
		a.labelBinding(loop);
		a.aload(cursorSlot);
		a.ifnull(counted);
		car(a, cursorSlot);
		a.instanceOf(this.k.stringClass());
		a.ifeq(nextParam);
		car(a, cursorSlot);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(this.k.stringLength());
		a.ifeq(nextParam);
		car(a, cursorSlot);
		a.checkcast(this.k.stringClass());
		a.loadConstant(0);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('&');
		a.if_icmpeq(unchecked);
		a.labelBinding(nextParam);
		a.iinc(countSlot, 1);
		cdr(a, cursorSlot);
		a.astore(cursorSlot);
		a.goto_(loop);
		a.labelBinding(counted);
		MethodCode.Label check = a.newLabel();
		a.iload(countSlot);
		a.loadConstant(1);
		a.ishl();
		a.istore(countSlot);
		a.goto_(check);
		a.labelBinding(unchecked);
		a.loadConstant(1);
		a.istore(countSlot);
		a.labelBinding(check);
		a.aload(argListSlot);
		a.iload(countSlot);
		a.invokestatic(this.k.arityChkRef());
	}

	/**
	 * Emits {@code throw _notFn(value)} for the value in {@code slot}: the same text (and
	 * catchability) as the funcall dispatchers' non-function arm
	 * ({@link JvmRuntimeBuilder#buildNotFnBody}).
	 */
	private void emitNotFunctionThrow(MethodCode a, int slot) {
		a.aload(slot);
		a.invokestatic(this.k.notFnRef());
		a.athrow();
	}

	// === _store(place, value, env) -> value ===

	private MethodCode storeBody() {
		MethodCode a = new MethodCode();
		final int PLACE = 0, VALUE = 1, ENV = 2, OP = 3, TMP = 4, TARGET = 5, ARGS = 6;
		final int IDX = 8, FIELD = 9, CH = 10, LEN = 11, VALID = 12;

		// Initialize TARGET so the slot has a reference type on every path (the inference
		// verifier is flow-insensitive about the FIELD guard before the store).
		a.aconst_null();
		a.astore(TARGET);

		// --- symbol place: variable assignment ---
		MethodCode.Label notSym = a.newLabel();
		a.aload(PLACE);
		a.instanceOf(this.k.stringClass());
		a.ifeq(notSym);
		storeSymbolBinding(a, PLACE, ENV, VALUE, TMP, false);
		storeSymbolBinding(a, PLACE, ENV, VALUE, TMP, true);
		// not bound anywhere: prepend a new binding to the global environment
		consFromSlots(a, PLACE, VALUE);
		a.astore(TMP);
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(TMP);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.getstatic(this.k.genvField());
		a.aastore();
		a.putstatic(this.k.genvField());
		a.aload(VALUE);
		a.areturn();
		a.labelBinding(notSym);

		// --- accessor place: (op arg...) ---
		MethodCode.Label isArr = a.newLabel();
		a.aload(PLACE);
		a.instanceOf(this.k.objectArrayClass());
		a.ifne(isArr);
		a.aload(VALUE);
		a.areturn();
		a.labelBinding(isArr);
		car(a, PLACE);
		a.astore(OP);
		MethodCode.Label opStr = a.newLabel();
		a.aload(OP);
		a.instanceOf(this.k.stringClass());
		a.ifne(opStr);
		a.aload(VALUE);
		a.areturn();
		a.labelBinding(opStr);
		cdr(a, PLACE);
		a.astore(ARGS);
		// FIELD = -1 (no target resolved)
		a.loadConstant(-1);
		a.istore(FIELD);

		// nth: walk n cdrs, set car
		MethodCode.Label afterNth = special(a, OP, LispNames.NTH);
		evalCar(a, ARGS, ENV);
		a.checkcast(this.k.longClass());
		a.invokevirtual(this.k.longValue());
		a.l2i();
		a.istore(IDX);
		cdr(a, ARGS);
		a.astore(ARGS);
		evalCar(a, ARGS, ENV);
		a.astore(TARGET);
		walkCdrs(a, TARGET, IDX);
		a.loadConstant(0);
		a.istore(FIELD);
		a.labelBinding(afterNth);

		// first/second/third/fourth/fifth/sixth/seventh/eighth/ninth/tenth: k cdrs, set
		// car
		fixedAccessorTarget(a, OP, LispNames.FIRST, ARGS, ENV, TARGET, FIELD, 0);
		fixedAccessorTarget(a, OP, LispNames.SECOND, ARGS, ENV, TARGET, FIELD, 1);
		fixedAccessorTarget(a, OP, LispNames.THIRD, ARGS, ENV, TARGET, FIELD, 2);
		fixedAccessorTarget(a, OP, LispNames.FOURTH, ARGS, ENV, TARGET, FIELD, 3);
		fixedAccessorTarget(a, OP, LispNames.FIFTH, ARGS, ENV, TARGET, FIELD, 4);
		fixedAccessorTarget(a, OP, LispNames.SIXTH, ARGS, ENV, TARGET, FIELD, 5);
		fixedAccessorTarget(a, OP, LispNames.SEVENTH, ARGS, ENV, TARGET, FIELD, 6);
		fixedAccessorTarget(a, OP, LispNames.EIGHTH, ARGS, ENV, TARGET, FIELD, 7);
		fixedAccessorTarget(a, OP, LispNames.NINTH, ARGS, ENV, TARGET, FIELD, 8);
		fixedAccessorTarget(a, OP, LispNames.TENTH, ARGS, ENV, TARGET, FIELD, 9);

		// car/cdr and c[ad]+r compositions (only if no named accessor matched)
		MethodCode.Label skipCarCdr = a.newLabel();
		a.iload(FIELD);
		a.loadConstant(-1);
		a.if_icmpne(skipCarCdr);
		carCdrStoreTarget(a, OP, ARGS, ENV, TARGET, FIELD, IDX, CH, LEN, VALID);
		a.labelBinding(skipCarCdr);

		// store: if a target was resolved and is a cons, set car (FIELD 0) or cdr (FIELD
		// 1)
		MethodCode.Label doneStore = a.newLabel();
		a.iload(FIELD);
		a.loadConstant(-1);
		a.if_icmpeq(doneStore);
		a.aload(TARGET);
		a.instanceOf(this.k.objectArrayClass());
		a.ifeq(doneStore);
		MethodCode.Label setCdr = a.newLabel();
		MethodCode.Label afterSet = a.newLabel();
		a.iload(FIELD);
		a.ifne(setCdr);
		a.aload(TARGET);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(0);
		a.aload(VALUE);
		a.aastore();
		a.goto_(afterSet);
		a.labelBinding(setCdr);
		a.aload(TARGET);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aload(VALUE);
		a.aastore();
		a.labelBinding(afterSet);
		a.labelBinding(doneStore);
		a.aload(VALUE);
		a.areturn();
		return a;
	}

	/**
	 * Emits {@code binding = _envLookup(name, env-or-global); if (binding != null) {
	 * binding.cdr = value; return value; }}, for the lexical ({@code global == false}) or
	 * global ({@code global == true}) environment.
	 */
	private void storeSymbolBinding(MethodCode a, int nameSlot, int envSlot, int valueSlot, int tmpSlot,
			boolean global) {
		a.aload(nameSlot);
		if (global) {
			a.getstatic(this.k.genvField());
		}
		else {
			a.aload(envSlot);
		}
		a.invokestatic(this.k.envLookupRef());
		a.astore(tmpSlot);
		MethodCode.Label skip = a.newLabel();
		a.aload(tmpSlot);
		a.ifnull(skip);
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aload(valueSlot);
		a.aastore();
		a.aload(valueSlot);
		a.areturn();
		a.labelBinding(skip);
	}

	/**
	 * Emits {@code _store} handling of a fixed numbered accessor (e.g. {@code second}):
	 * if the operator equals {@code name}, evaluates the single argument, walks
	 * {@code cdrCount} cdrs into {@code targetSlot} and sets {@code fieldSlot} to 0
	 * (car).
	 */
	private void fixedAccessorTarget(MethodCode a, int opSlot, String name, int argsSlot, int envSlot, int targetSlot,
			int fieldSlot, int cdrCount) {
		MethodCode.Label next = special(a, opSlot, name);
		evalCar(a, argsSlot, envSlot);
		a.astore(targetSlot);
		for (int i = 0; i < cdrCount; i++) {
			cdr(a, targetSlot);
			a.astore(targetSlot);
		}
		a.loadConstant(0);
		a.istore(fieldSlot);
		a.labelBinding(next);
	}

	/** Emits a loop replacing {@code targetSlot} with its cdr {@code idxSlot} times. */
	private void walkCdrs(MethodCode a, int targetSlot, int idxSlot) {
		MethodCode.Label loop = a.newLabel();
		MethodCode.Label end = a.newLabel();
		a.labelBinding(loop);
		a.iload(idxSlot);
		a.ifle(end);
		a.aload(targetSlot);
		a.ifnull(end);
		cdr(a, targetSlot);
		a.astore(targetSlot);
		a.iinc(idxSlot, -1);
		a.goto_(loop);
		a.labelBinding(end);
	}

	/**
	 * Emits {@code _store} handling of {@code car}/{@code cdr} and the {@code c[ad]+r}
	 * compositions. When the operator matches the pattern, evaluates the single argument,
	 * applies the inner operations into {@code targetSlot}, and sets {@code fieldSlot} to
	 * 0 (outer op {@code a}) or 1 (outer op {@code d}); otherwise leaves
	 * {@code fieldSlot} unchanged.
	 */
	private void carCdrStoreTarget(MethodCode a, int opSlot, int argsSlot, int envSlot, int targetSlot, int fieldSlot,
			int idxSlot, int chSlot, int lenSlot, int validSlot) {
		aloadStr(a, opSlot);
		a.invokevirtual(this.k.stringLength());
		a.istore(lenSlot);
		MethodCode.Label noMatch = a.newLabel();
		a.iload(lenSlot);
		a.loadConstant(3);
		a.if_icmplt(noMatch);
		aloadStr(a, opSlot);
		a.loadConstant(0);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('C');
		a.if_icmpne(noMatch);
		aloadStr(a, opSlot);
		a.iload(lenSlot);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('R');
		a.if_icmpne(noMatch);
		// scan middle bytes: valid = all in {'a','d'}
		a.loadConstant(1);
		a.istore(validSlot);
		a.loadConstant(1);
		a.istore(idxSlot);
		MethodCode.Label sloop = a.newLabel();
		MethodCode.Label send = a.newLabel();
		a.labelBinding(sloop);
		a.iload(idxSlot);
		a.iload(lenSlot);
		a.loadConstant(1);
		a.isub();
		a.if_icmpge(send);
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.istore(chSlot);
		MethodCode.Label okch = a.newLabel();
		a.iload(chSlot);
		a.loadConstant('A');
		a.if_icmpeq(okch);
		a.iload(chSlot);
		a.loadConstant('D');
		a.if_icmpeq(okch);
		a.loadConstant(0);
		a.istore(validSlot);
		a.goto_(send);
		a.labelBinding(okch);
		a.iinc(idxSlot, 1);
		a.goto_(sloop);
		a.labelBinding(send);
		MethodCode.Label notValid = a.newLabel();
		a.iload(validSlot);
		a.ifeq(notValid);
		evalCar(a, argsSlot, envSlot);
		a.astore(targetSlot);
		// apply inner ops (indices len-2 down to 2)
		a.iload(lenSlot);
		a.loadConstant(2);
		a.isub();
		a.istore(idxSlot);
		MethodCode.Label iloop = a.newLabel();
		MethodCode.Label iend = a.newLabel();
		a.labelBinding(iloop);
		a.iload(idxSlot);
		a.loadConstant(2);
		a.if_icmplt(iend);
		MethodCode.Label isCdr = a.newLabel();
		MethodCode.Label afterc = a.newLabel();
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('A');
		a.if_icmpne(isCdr);
		car(a, targetSlot);
		a.astore(targetSlot);
		a.goto_(afterc);
		a.labelBinding(isCdr);
		cdr(a, targetSlot);
		a.astore(targetSlot);
		a.labelBinding(afterc);
		a.iinc(idxSlot, -1);
		a.goto_(iloop);
		a.labelBinding(iend);
		// field = (op.charAt(1) == 'd') ? 1 : 0
		a.loadConstant(0);
		a.istore(fieldSlot);
		MethodCode.Label notD = a.newLabel();
		aloadStr(a, opSlot);
		a.loadConstant(1);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('D');
		a.if_icmpne(notD);
		a.loadConstant(1);
		a.istore(fieldSlot);
		a.labelBinding(notD);
		a.labelBinding(notValid);
		a.labelBinding(noMatch);
	}

	// === _eval(form, env) -> value ===

	private MethodCode evalBody() {
		MethodCode a = new MethodCode();
		final int VAL = 0, ENV = 1, REST = 2, TMP = 3, OP = 4, ARGHEAD = 5, ARGTAIL = 6, NEWCELL = 7, ACC = 8, FN = 9,
				BODY = 10, BINDCUR = 11, ELEM = 12;
		final int IDX = 15, LEN = 16, CH = 17, ARITY = 18, VALID = 19;

		// --- self-evaluating: nil, Long, Double ---
		MethodCode.Label notNil = a.newLabel();
		a.aload(VAL);
		a.ifnonnull(notNil);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(notNil);
		MethodCode.Label notLong = a.newLabel();
		a.aload(VAL);
		a.instanceOf(this.k.longClass());
		a.ifeq(notLong);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(notLong);
		MethodCode.Label notDouble = a.newLabel();
		a.aload(VAL);
		a.instanceOf(this.k.doubleClass());
		a.ifeq(notDouble);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(notDouble);
		// a BigInteger (an exact integer past the long range) is self-evaluating too
		ClassEntry bigIntegerClass = this.k.cp().classEntry("java/math/BigInteger");
		MethodCode.Label notBigInteger = a.newLabel();
		a.aload(VAL);
		a.instanceOf(bigIntegerClass);
		a.ifeq(notBigInteger);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(notBigInteger);

		// --- ratios (BigInteger[]) are self-evaluating; checked before the generic
		// Object[] form handling because a ratio is also an Object[] ---
		ClassEntry ratioArrayClass = this.k.cp().classEntry("[Ljava/math/BigInteger;");
		MethodCode.Label notRatio = a.newLabel();
		a.aload(VAL);
		a.instanceOf(ratioArrayClass);
		a.ifeq(notRatio);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(notRatio);

		// --- complex values (RontoComplex) are self-evaluating, like ratios --
		// emitted only for a complex-capable program, so the travelling class
		// stays out of every other constant pool. The presence probe first: a
		// lone class run without the file beside it skips the test without
		// resolving the holder class (.todo/757) -- exact, since no holder can
		// exist then.
		if (this.k.complexValues()) {
			ClassEntry complexClass = this.k.cp().classEntry("am/ik/rontolisp/runtime/RontoComplex");
			MethodCode.Label noHolder = a.newLabel();
			a.getstatic(Objects.requireNonNull(this.k.hasComplexField()));
			a.ifeq(noHolder);
			MethodCode.Label notComplex = a.newLabel();
			a.aload(VAL);
			a.instanceOf(complexClass);
			a.ifeq(notComplex);
			a.aload(VAL);
			a.areturn();
			a.labelBinding(notComplex);
			a.labelBinding(noHolder);
		}

		// --- strings: string literal (self-eval) or symbol (variable reference) ---
		MethodCode.Label notStr = a.newLabel();
		a.aload(VAL);
		a.instanceOf(this.k.stringClass());
		a.ifeq(notStr);
		MethodCode.Label sym = a.newLabel();
		aloadStr(a, VAL);
		a.loadConstant(0);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant(QUOTE_CHAR);
		a.if_icmpne(sym);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(sym);
		// lexical lookup
		MethodCode.Label global = a.newLabel();
		a.aload(VAL);
		a.aload(ENV);
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.ifnull(global);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(global);
		// global lookup; Lisp-2: a bare symbol resolves the variable namespace only,
		// never the function registry. An unbound symbol retries the case-flipped
		// spelling once (compiled references read upcased while runtime-read
		// definitions are case-preserved, and vice versa), then evaluates to ITSELF
		// under its original spelling.
		MethodRefEntry varToLowerCase = stringCaseRef("toLowerCase");
		MethodRefEntry varToUpperCase = stringCaseRef("toUpperCase");
		MethodCode.Label self = a.newLabel();
		MethodCode.Label varFlipped = a.newLabel();
		MethodCode.Label retry = a.newLabel();
		a.aload(VAL);
		a.getstatic(this.k.genvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.ifnull(retry);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(retry);
		a.aload(VAL);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(varToLowerCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(VAL);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(varFlipped);
		a.aload(VAL);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(varToUpperCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(VAL);
		a.invokevirtual(this.k.objectEquals());
		a.ifne(self);
		a.labelBinding(varFlipped);
		a.aload(TMP);
		a.getstatic(this.k.genvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.ifnull(self);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.areturn();
		a.labelBinding(self);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(notStr);

		// --- Object[]: function value (self-eval) or cons (special form / application)
		// ---
		MethodCode.Label isArr = a.newLabel();
		a.aload(VAL);
		a.instanceOf(this.k.objectArrayClass());
		a.ifne(isArr);
		a.aconst_null();
		a.areturn();
		a.labelBinding(isArr);
		MethodCode.Label cons = a.newLabel();
		arrLen(a, VAL);
		a.ifeq(cons);
		car(a, VAL);
		a.instanceOf(this.k.integerClass());
		a.ifeq(cons);
		a.aload(VAL);
		a.areturn();
		a.labelBinding(cons);
		car(a, VAL);
		a.astore(OP);
		cdr(a, VAL);
		a.astore(REST);

		// non-symbol operator (inline lambda): evaluate it, then apply
		MethodCode.Label symOp = a.newLabel();
		a.aload(OP);
		a.instanceOf(this.k.stringClass());
		a.ifne(symOp);
		a.aload(OP);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.astore(FN);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.labelBinding(symOp);

		// The inline arms below take only the call shape they are written for; any other
		// argument count branches here, to the generic application, whose registered
		// wrapper judges the count and names the operator (FUNCALL expects at least 1
		// argument, got 0) or serves the shape the arm does not ((+) is 0).
		MethodCode.Label registryApply = a.newLabel();

		// ---- quote ----
		MethodCode.Label n = special(a, OP, LispNames.QUOTE);
		car(a, REST);
		a.areturn();
		a.labelBinding(n);

		// ---- if ----
		n = special(a, OP, LispNames.IF);
		evalCar(a, REST, ENV);
		MethodCode.Label testTrue = a.newLabel();
		a.ifnonnull(testTrue);
		// false branch: rest = cddr; if null nil else eval(car)
		cdr(a, REST);
		a.astore(REST);
		cdr(a, REST);
		a.astore(REST);
		MethodCode.Label hasElse = a.newLabel();
		a.aload(REST);
		a.ifnonnull(hasElse);
		a.aconst_null();
		a.areturn();
		a.labelBinding(hasElse);
		evalCar(a, REST, ENV);
		a.areturn();
		a.labelBinding(testTrue);
		cdr(a, REST);
		a.astore(REST);
		evalCar(a, REST, ENV);
		a.areturn();
		a.labelBinding(n);

		// ---- progn ----
		n = special(a, OP, LispNames.PROGN);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.labelBinding(n);

		// ---- let ----
		n = special(a, OP, LispNames.LET);
		cdr(a, REST);
		a.astore(BODY);
		car(a, REST);
		a.astore(BINDCUR);
		a.aload(ENV);
		a.astore(ELEM); // newEnv accumulator
		MethodCode.Label letLoop = a.newLabel();
		MethodCode.Label letEnd = a.newLabel();
		a.labelBinding(letLoop);
		a.aload(BINDCUR);
		a.ifnull(letEnd);
		car(a, BINDCUR);
		a.astore(TMP); // (name value)
		cdr(a, TMP);
		a.astore(NEWCELL); // (value)
		evalCar(a, NEWCELL, ENV);
		a.astore(NEWCELL); // value evaluated in the outer env
		// binding = cons(car(TMP), value)
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		car(a, TMP);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(NEWCELL);
		a.aastore();
		a.astore(TMP);
		consFromSlots(a, TMP, ELEM);
		a.astore(ELEM);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.goto_(letLoop);
		a.labelBinding(letEnd);
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ELEM, TMP);
		a.aload(TMP);
		a.areturn();
		a.labelBinding(n);

		// ---- lambda ----
		n = special(a, OP, LispNames.LAMBDA);
		a.loadConstant(3);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.loadConstant(-1);
		a.invokestatic(this.k.integerValueOf());
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.aload(REST);
		a.aastore();
		a.dup();
		a.loadConstant(2);
		a.aload(ENV);
		a.aastore();
		a.areturn();
		a.labelBinding(n);

		// ---- defun: (defun name (params) body...) builds a closure and installs it
		// into the _fenv function namespace so loaded files can define functions ----
		n = special(a, OP, LispNames.DEFUN);
		car(a, REST);
		a.astore(ACC); // name symbol
		// lambdaForm = cons("lambda", cdr(REST))
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		ldcStr(a, LispNames.LAMBDA);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		cdr(a, REST);
		a.aastore();
		a.astore(TMP); // lambdaForm
		// value = _eval(lambdaForm, ENV)
		a.aload(TMP);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.astore(NEWCELL); // closure value
		// install into the function namespace (Lisp-2): _fenv, not _genv
		storeFunctionBinding(a, ACC, NEWCELL, TMP);
		a.aload(ACC);
		a.areturn();
		a.labelBinding(n);

		// ---- function: (function name) / #'name resolves the function namespace;
		// (function (lambda ...)) evaluates to a closure ----
		n = special(a, OP, LispNames.FUNCTION);
		car(a, REST);
		a.astore(ACC); // designator (unevaluated)
		MethodCode.Label fnSym = a.newLabel();
		a.aload(ACC);
		a.instanceOf(this.k.stringClass());
		a.ifne(fnSym);
		// non-symbol designator (a lambda form): evaluate it
		a.aload(ACC);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.areturn();
		a.labelBinding(fnSym);
		emitFunctionLookupReturn(a, ACC, TMP);
		a.labelBinding(n);

		// ---- symbol-function: like function but the argument is evaluated ----
		n = special(a, OP, LispNames.SYMBOL_FUNCTION);
		evalCar(a, REST, ENV);
		a.astore(ACC);
		MethodCode.Label sfSym = a.newLabel();
		a.aload(ACC);
		a.instanceOf(this.k.stringClass());
		a.ifne(sfSym);
		a.aconst_null();
		a.areturn();
		a.labelBinding(sfSym);
		emitFunctionLookupReturn(a, ACC, TMP);
		a.labelBinding(n);

		// ---- cond ----
		n = special(a, OP, LispNames.COND);
		a.aload(REST);
		a.astore(BINDCUR);
		MethodCode.Label condLoop = a.newLabel();
		MethodCode.Label condEnd = a.newLabel();
		a.labelBinding(condLoop);
		a.aload(BINDCUR);
		a.ifnull(condEnd);
		car(a, BINDCUR);
		a.astore(TMP); // clause
		evalCar(a, TMP, ENV);
		a.astore(ACC); // test value
		MethodCode.Label nextClause = a.newLabel();
		a.aload(ACC);
		a.ifnull(nextClause);
		cdr(a, TMP);
		a.astore(BODY);
		MethodCode.Label hasBody = a.newLabel();
		a.aload(BODY);
		a.ifnonnull(hasBody);
		a.aload(ACC);
		a.areturn();
		a.labelBinding(hasBody);
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.labelBinding(nextClause);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.goto_(condLoop);
		a.labelBinding(condEnd);
		a.aconst_null();
		a.areturn();
		a.labelBinding(n);

		// ---- and ----
		n = special(a, OP, LispNames.AND);
		MethodCode.Label andNotEmpty = a.newLabel();
		a.aload(REST);
		a.ifnonnull(andNotEmpty);
		ldcStr(a, "T");
		a.areturn();
		a.labelBinding(andNotEmpty);
		a.aload(REST);
		a.astore(BINDCUR);
		a.aconst_null();
		a.astore(ACC);
		MethodCode.Label andLoop = a.newLabel();
		MethodCode.Label andEnd = a.newLabel();
		a.labelBinding(andLoop);
		a.aload(BINDCUR);
		a.ifnull(andEnd);
		evalCar(a, BINDCUR, ENV);
		a.astore(ACC);
		MethodCode.Label andOk = a.newLabel();
		a.aload(ACC);
		a.ifnonnull(andOk);
		a.aconst_null();
		a.areturn();
		a.labelBinding(andOk);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.goto_(andLoop);
		a.labelBinding(andEnd);
		a.aload(ACC);
		a.areturn();
		a.labelBinding(n);

		// ---- or ----
		n = special(a, OP, LispNames.OR);
		a.aload(REST);
		a.astore(BINDCUR);
		MethodCode.Label orLoop = a.newLabel();
		MethodCode.Label orEnd = a.newLabel();
		a.labelBinding(orLoop);
		a.aload(BINDCUR);
		a.ifnull(orEnd);
		evalCar(a, BINDCUR, ENV);
		a.astore(ACC);
		MethodCode.Label orNot = a.newLabel();
		a.aload(ACC);
		a.ifnull(orNot);
		a.aload(ACC);
		a.areturn();
		a.labelBinding(orNot);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.goto_(orLoop);
		a.labelBinding(orEnd);
		a.aconst_null();
		a.areturn();
		a.labelBinding(n);

		// ---- when ----
		n = special(a, OP, LispNames.WHEN);
		evalCar(a, REST, ENV);
		MethodCode.Label whenProceed = a.newLabel();
		a.ifnonnull(whenProceed);
		a.aconst_null();
		a.areturn();
		a.labelBinding(whenProceed);
		cdr(a, REST);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.labelBinding(n);

		// ---- unless ----
		n = special(a, OP, LispNames.UNLESS);
		evalCar(a, REST, ENV);
		MethodCode.Label unlessProceed = a.newLabel();
		a.ifnull(unlessProceed);
		a.aconst_null();
		a.areturn();
		a.labelBinding(unlessProceed);
		cdr(a, REST);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.labelBinding(n);

		// ---- while: (while test body...) -> evaluate body while test is non-nil ----
		n = special(a, OP, LispNames.WHILE);
		car(a, REST);
		a.astore(FN); // test form
		cdr(a, REST);
		a.astore(BODY); // body list
		MethodCode.Label whileLoop = a.newLabel();
		MethodCode.Label whileEnd = a.newLabel();
		a.labelBinding(whileLoop);
		a.aload(FN);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.ifnull(whileEnd);
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.goto_(whileLoop);
		a.labelBinding(whileEnd);
		a.aconst_null();
		a.areturn();
		a.labelBinding(n);

		// ---- dotimes: (dotimes (var count result?) body...) ----
		n = special(a, OP, LispNames.DOTIMES);
		car(a, REST);
		a.astore(TMP); // (var count result?)
		car(a, TMP);
		a.astore(BINDCUR); // loop variable symbol
		cdr(a, TMP);
		a.astore(ACC); // (count result?)
		evalCar(a, ACC, ENV); // evaluate the count form once
		a.checkcast(this.k.longClass());
		a.invokevirtual(this.k.longValue());
		a.l2i();
		a.istore(ARITY); // count limit
		cdr(a, ACC);
		a.astore(ACC); // (result?) or nil
		MethodCode.Label dtHasResult = a.newLabel();
		MethodCode.Label dtResultDone = a.newLabel();
		a.aload(ACC);
		a.ifnonnull(dtHasResult);
		a.aconst_null();
		a.astore(FN);
		a.goto_(dtResultDone);
		a.labelBinding(dtHasResult);
		car(a, ACC);
		a.astore(FN); // result form
		a.labelBinding(dtResultDone);
		cdr(a, REST);
		a.astore(BODY); // body list
		// bindCell = cons(var, Long(0)); newEnv = cons(bindCell, env)
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(BINDCUR);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		a.loadConstant(0);
		a.i2l();
		a.invokestatic(this.k.longValueOf());
		a.aastore();
		a.astore(NEWCELL); // mutable binding cell
		consFromSlots(a, NEWCELL, ENV);
		a.astore(ELEM); // extended environment
		a.loadConstant(0);
		a.istore(IDX); // loop counter
		MethodCode.Label dtLoop = a.newLabel();
		MethodCode.Label dtEnd = a.newLabel();
		a.labelBinding(dtLoop);
		a.iload(IDX);
		a.iload(ARITY);
		a.if_icmpge(dtEnd);
		// bindCell[1] = Long(i)
		a.aload(NEWCELL);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.iload(IDX);
		a.i2l();
		a.invokestatic(this.k.longValueOf());
		a.aastore();
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ELEM, TMP);
		a.iinc(IDX, 1);
		a.goto_(dtLoop);
		a.labelBinding(dtEnd);
		// var = count for the result form
		a.aload(NEWCELL);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.iload(ARITY);
		a.i2l();
		a.invokestatic(this.k.longValueOf());
		a.aastore();
		MethodCode.Label dtResult = a.newLabel();
		a.aload(FN);
		a.ifnonnull(dtResult);
		a.aconst_null();
		a.areturn();
		a.labelBinding(dtResult);
		a.aload(FN);
		a.aload(ELEM);
		a.invokestatic(this.k.evalRef());
		a.areturn();
		a.labelBinding(n);

		// ---- setq ----
		n = special(a, OP, LispNames.SETQ);
		a.aload(REST);
		a.astore(BINDCUR);
		a.aconst_null();
		a.astore(ACC);
		MethodCode.Label setqLoop = a.newLabel();
		MethodCode.Label setqEnd = a.newLabel();
		a.labelBinding(setqLoop);
		a.aload(BINDCUR);
		a.ifnull(setqEnd);
		car(a, BINDCUR); // place
		cdr(a, BINDCUR);
		a.astore(NEWCELL); // (value ...)
		evalCar(a, NEWCELL, ENV); // value
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.astore(ACC);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.goto_(setqLoop);
		a.labelBinding(setqEnd);
		a.aload(ACC);
		a.areturn();
		a.labelBinding(n);

		// ---- setf ----
		n = special(a, OP, LispNames.SETF);
		car(a, REST); // place
		cdr(a, REST);
		a.astore(NEWCELL);
		evalCar(a, NEWCELL, ENV); // value
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.areturn();
		a.labelBinding(n);

		// ---- push: (push item place) ----
		n = special(a, OP, LispNames.PUSH);
		evalCar(a, REST, ENV);
		a.astore(ACC); // item
		cdr(a, REST);
		a.astore(REST); // (place)
		car(a, REST);
		a.astore(BODY); // place form
		// newval = cons(item, eval(place))
		a.loadConstant(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(ACC);
		a.aastore();
		a.dup();
		a.loadConstant(1);
		evalCar(a, REST, ENV);
		a.aastore();
		a.astore(ACC);
		a.aload(BODY);
		a.aload(ACC);
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.areturn();
		a.labelBinding(n);

		// ---- pop: (pop place) ----
		n = special(a, OP, LispNames.POP);
		car(a, REST);
		a.astore(BODY); // place form
		evalCar(a, REST, ENV);
		a.astore(ELEM); // current list value
		MethodCode.Label popNotCons = a.newLabel();
		MethodCode.Label popAfter = a.newLabel();
		a.aload(ELEM);
		a.instanceOf(this.k.objectArrayClass());
		a.ifeq(popNotCons);
		car(a, ELEM);
		a.astore(ACC);
		cdr(a, ELEM);
		a.astore(TMP);
		a.goto_(popAfter);
		a.labelBinding(popNotCons);
		a.aconst_null();
		a.astore(ACC);
		a.aconst_null();
		a.astore(TMP);
		a.labelBinding(popAfter);
		a.aload(BODY);
		a.aload(TMP);
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.pop();
		a.aload(ACC);
		a.areturn();
		a.labelBinding(n);

		// ---- eval (nested) ----
		// No wrapper backs eval, so the arm reports a wrong count itself.
		n = special(a, OP, LispNames.EVAL);
		a.aload(REST);
		ldcInt(a, this.k.arityOperators().namedShape(1, false, LispNames.EVAL));
		a.invokestatic(this.k.arityChkRef());
		evalCar(a, REST, ENV);
		a.aconst_null();
		a.invokestatic(this.k.evalRef());
		a.areturn();
		a.labelBinding(n);

		// ---- funcall ----
		n = special(a, OP, LispNames.FUNCALL);
		a.aload(REST);
		a.ifnull(registryApply);
		evalCar(a, REST, ENV);
		a.astore(FN);
		cdr(a, REST);
		a.astore(REST);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.labelBinding(n);

		// mapcar, mapc, reduce, first ... tenth, rest and nth have no arm: their
		// registered wrappers take every shape (mapcar over several lists, reduce with
		// :from-end), report a wrong count naming the operator and signal the
		// interpreter's type-error on a non-list, where the arms this replaced answered
		// one list only, took a :from-end for the initial value, dropped a surplus
		// argument and threw a NullPointerException on (first nil).

		// ---- list (variadic) ----
		n = special(a, OP, LispNames.LIST);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(ARGHEAD);
		a.areturn();
		a.labelBinding(n);

		// ---- variadic + - * / : left-fold through the wrapper's two-argument call ----
		MethodCode.Label arith = a.newLabel();
		MethodCode.Label notArith = a.newLabel();
		for (String opName : new String[] { LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV }) {
			a.aload(OP);
			ldcStr(a, opName);
			a.invokevirtual(this.k.objectEquals());
			a.ifne(arith);
		}
		a.goto_(notArith);
		a.labelBinding(arith);
		// no argument: the wrapper answers the identity ((+) is 0) or reports the count
		// ((-) expects at least 1 argument)
		a.aload(REST);
		a.ifnull(registryApply);
		a.aload(OP);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		a.loadConstant(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.astore(FN);
		evalCar(a, REST, ENV);
		a.astore(ACC);
		cdr(a, REST);
		a.astore(REST);
		// single argument: (- x) negates and (/ x) takes the reciprocal, by seeding
		// the fold with the identity element (0 - x, 1 / x)
		MethodCode.Label notUnary = a.newLabel();
		MethodCode.Label unaryDiv = a.newLabel();
		a.aload(REST);
		a.ifnonnull(notUnary);
		a.aload(OP);
		ldcStr(a, LispNames.SUB);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(unaryDiv);
		a.aload(FN);
		a.lconst_0();
		a.invokestatic(this.k.longValueOf());
		a.aload(ACC);
		a.invokestatic(this.k.invoke()[2]);
		a.astore(ACC);
		a.goto_(notUnary);
		a.labelBinding(unaryDiv);
		a.aload(OP);
		ldcStr(a, LispNames.DIV);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(notUnary);
		a.aload(FN);
		a.lconst_1();
		a.invokestatic(this.k.longValueOf());
		a.aload(ACC);
		a.invokestatic(this.k.invoke()[2]);
		a.astore(ACC);
		a.labelBinding(notUnary);
		MethodCode.Label foldLoop = a.newLabel();
		MethodCode.Label foldEnd = a.newLabel();
		a.labelBinding(foldLoop);
		a.aload(REST);
		a.ifnull(foldEnd);
		a.aload(FN);
		a.aload(ACC);
		evalCar(a, REST, ENV);
		a.invokestatic(this.k.invoke()[2]);
		a.astore(ACC);
		cdr(a, REST);
		a.astore(REST);
		a.goto_(foldLoop);
		a.labelBinding(foldEnd);
		a.aload(ACC);
		a.areturn();
		a.labelBinding(notArith);

		// `= < > <= >= /=` have no arm of their own: the wrappers take any count
		// ((a &optional b &rest r)), so the registry path below reports their count
		// naming the operator, the same as any other registered function.

		// ---- generic named application ----
		// Lisp-2: the operator resolves in the function namespace only. Variable
		// bindings (lexical or global) never shadow a function. The ARITY slot is the
		// one-shot case-flip guard here: an unknown operator retries once with the
		// case-flipped spelling (compiled
		// definitions are upcased, runtime-read references case-preserved, and vice
		// versa) after the carcdr check falls through.
		MethodRefEntry applyToLowerCase = stringCaseRef("toLowerCase");
		MethodRefEntry applyToUpperCase = stringCaseRef("toUpperCase");
		a.labelBinding(registryApply);
		a.loadConstant(0);
		a.istore(ARITY);
		MethodCode.Label genericApply = a.newLabel();
		a.labelBinding(genericApply);
		// (a) operator defined at runtime via defun (the _fenv function namespace)
		a.aload(OP);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		MethodCode.Label notFenv = a.newLabel();
		a.aload(TMP);
		a.ifnull(notFenv);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(1);
		a.aaload();
		a.astore(FN);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.labelBinding(notFenv);
		MethodCode.Label notReg = a.newLabel();
		// (b) registered function: evaluate every argument form, then apply. The count is
		// the spread dispatcher's to judge -- its case measures the list against the
		// callee's lambda list and reports a wrong count naming the operator -- so no
		// argument is dropped or padded here (evaluating exactly the registered arity
		// answered (car 1 2) with a type-error on 1 and (cons 1) with (1)).
		a.aload(OP);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.ifnull(notReg);
		a.loadConstant(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.loadConstant(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.loadConstant(0);
		a.aaload();
		a.aastore();
		a.astore(FN);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.labelBinding(notReg);
		// (c) car/cdr composition such as cadr -- BEFORE the case-flip retry, so the
		// composition sees the original spelling (a returning match ends the eval).
		carCdrComposition(a, OP, REST, ENV, ACC, IDX, CH, LEN, VALID);
		// One case-flip retry of (a)+(b), guarded by ARITY's sign (a hit in either
		// returns; a second pass runs the composition again with the flipped spelling,
		// harmlessly).
		MethodCode.Label noRetry = a.newLabel();
		MethodCode.Label applyFlipped = a.newLabel();
		a.iload(ARITY);
		a.iflt(noRetry);
		a.loadConstant(-1);
		a.istore(ARITY);
		a.aload(OP);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(applyToLowerCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(OP);
		a.invokevirtual(this.k.objectEquals());
		a.ifeq(applyFlipped);
		a.aload(OP);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(applyToUpperCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(OP);
		a.invokevirtual(this.k.objectEquals());
		a.ifne(noRetry);
		a.labelBinding(applyFlipped);
		a.aload(TMP);
		a.astore(OP);
		a.goto_(genericApply);
		a.labelBinding(noRetry);
		// (d) unknown operator
		a.aconst_null();
		a.areturn();
		return a;
	}

	/**
	 * Emits {@code _eval} handling for {@code car}/{@code cdr} composition operators
	 * ({@code c[ad]+r}, e.g. {@code cadr}). When the operator name matches, evaluates the
	 * single argument and applies the car/cdr operations from right to left, then
	 * returns; otherwise falls through.
	 */
	private void carCdrComposition(MethodCode a, int opSlot, int restSlot, int envSlot, int accSlot, int idxSlot,
			int chSlot, int lenSlot, int validSlot) {
		aloadStr(a, opSlot);
		a.invokevirtual(this.k.stringLength());
		a.istore(lenSlot);
		MethodCode.Label noMatch = a.newLabel();
		a.iload(lenSlot);
		a.loadConstant(3);
		a.if_icmplt(noMatch);
		aloadStr(a, opSlot);
		a.loadConstant(0);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('C');
		a.if_icmpne(noMatch);
		aloadStr(a, opSlot);
		a.iload(lenSlot);
		a.loadConstant(1);
		a.isub();
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('R');
		a.if_icmpne(noMatch);
		// scan middle bytes: valid = all in {'a','d'}
		a.loadConstant(1);
		a.istore(validSlot);
		a.loadConstant(1);
		a.istore(idxSlot);
		MethodCode.Label sloop = a.newLabel();
		MethodCode.Label send = a.newLabel();
		a.labelBinding(sloop);
		a.iload(idxSlot);
		a.iload(lenSlot);
		a.loadConstant(1);
		a.isub();
		a.if_icmpge(send);
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.istore(chSlot);
		MethodCode.Label okch = a.newLabel();
		a.iload(chSlot);
		a.loadConstant('A');
		a.if_icmpeq(okch);
		a.iload(chSlot);
		a.loadConstant('D');
		a.if_icmpeq(okch);
		a.loadConstant(0);
		a.istore(validSlot);
		a.goto_(send);
		a.labelBinding(okch);
		a.iinc(idxSlot, 1);
		a.goto_(sloop);
		a.labelBinding(send);
		MethodCode.Label notValid = a.newLabel();
		a.iload(validSlot);
		a.ifeq(notValid);
		evalCar(a, restSlot, envSlot);
		a.astore(accSlot);
		a.iload(lenSlot);
		a.loadConstant(2);
		a.isub();
		a.istore(idxSlot);
		MethodCode.Label iloop = a.newLabel();
		MethodCode.Label iend = a.newLabel();
		a.labelBinding(iloop);
		a.iload(idxSlot);
		a.loadConstant(1);
		a.if_icmplt(iend);
		MethodCode.Label isCdr = a.newLabel();
		MethodCode.Label afterc = a.newLabel();
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.loadConstant('A');
		a.if_icmpne(isCdr);
		car(a, accSlot);
		a.astore(accSlot);
		a.goto_(afterc);
		a.labelBinding(isCdr);
		cdr(a, accSlot);
		a.astore(accSlot);
		a.labelBinding(afterc);
		a.iinc(idxSlot, -1);
		a.goto_(iloop);
		a.labelBinding(iend);
		a.aload(accSlot);
		a.areturn();
		a.labelBinding(notValid);
		a.labelBinding(noMatch);
	}

}
