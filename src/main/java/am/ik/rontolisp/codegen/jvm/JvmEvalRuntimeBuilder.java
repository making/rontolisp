package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
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

		private final ClassConstant objectClass;

		private final ClassConstant objectArrayClass;

		private final ClassConstant integerClass;

		private final ClassConstant longClass;

		private final ClassConstant doubleClass;

		private final ClassConstant stringClass;

		private final MethodrefConstant integerValueOf;

		private final MethodrefConstant integerValue;

		private final MethodrefConstant longValueOf;

		private final MethodrefConstant longValue;

		private final MethodrefConstant stringCharAt;

		private final MethodrefConstant stringLength;

		private final MethodrefConstant objectEquals;

		private final MethodrefConstant evalRef;

		private final MethodrefConstant applyRef;

		private final MethodrefConstant storeRef;

		private final MethodrefConstant envLookupRef;

		private final MethodrefConstant lookupRef;

		private final FieldrefConstant genvField;

		private final FieldrefConstant fenvField;

		private final MethodrefConstant[] invoke;

		private final MethodrefConstant invokeSpread;

		private final Map<String, JvmLispCompiler.FunctionInfo> functions;

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
			this.genvField = Objects.requireNonNull(b.genvField);
			this.fenvField = Objects.requireNonNull(b.fenvField);
			this.invoke = Objects.requireNonNull(b.invoke);
			this.invokeSpread = Objects.requireNonNull(b.invokeSpread);
			this.functions = Objects.requireNonNull(b.functions);
		}

		ConstantPool cp() {
			return this.cp;
		}

		ClassConstant objectClass() {
			return this.objectClass;
		}

		ClassConstant objectArrayClass() {
			return this.objectArrayClass;
		}

		ClassConstant integerClass() {
			return this.integerClass;
		}

		ClassConstant longClass() {
			return this.longClass;
		}

		ClassConstant doubleClass() {
			return this.doubleClass;
		}

		ClassConstant stringClass() {
			return this.stringClass;
		}

		MethodrefConstant integerValueOf() {
			return this.integerValueOf;
		}

		MethodrefConstant integerValue() {
			return this.integerValue;
		}

		MethodrefConstant longValueOf() {
			return this.longValueOf;
		}

		MethodrefConstant longValue() {
			return this.longValue;
		}

		MethodrefConstant stringCharAt() {
			return this.stringCharAt;
		}

		MethodrefConstant stringLength() {
			return this.stringLength;
		}

		MethodrefConstant objectEquals() {
			return this.objectEquals;
		}

		MethodrefConstant evalRef() {
			return this.evalRef;
		}

		MethodrefConstant applyRef() {
			return this.applyRef;
		}

		MethodrefConstant storeRef() {
			return this.storeRef;
		}

		MethodrefConstant envLookupRef() {
			return this.envLookupRef;
		}

		MethodrefConstant lookupRef() {
			return this.lookupRef;
		}

		FieldrefConstant genvField() {
			return this.genvField;
		}

		FieldrefConstant fenvField() {
			return this.fenvField;
		}

		MethodrefConstant[] invoke() {
			return this.invoke;
		}

		MethodrefConstant invokeSpread() {
			return this.invokeSpread;
		}

		Map<String, JvmLispCompiler.FunctionInfo> functions() {
			return this.functions;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private @Nullable ConstantPool cp;

			private @Nullable ClassConstant objectClass;

			private @Nullable ClassConstant objectArrayClass;

			private @Nullable ClassConstant integerClass;

			private @Nullable ClassConstant longClass;

			private @Nullable ClassConstant doubleClass;

			private @Nullable ClassConstant stringClass;

			private @Nullable MethodrefConstant integerValueOf;

			private @Nullable MethodrefConstant integerValue;

			private @Nullable MethodrefConstant longValueOf;

			private @Nullable MethodrefConstant longValue;

			private @Nullable MethodrefConstant stringCharAt;

			private @Nullable MethodrefConstant stringLength;

			private @Nullable MethodrefConstant objectEquals;

			private @Nullable MethodrefConstant evalRef;

			private @Nullable MethodrefConstant applyRef;

			private @Nullable MethodrefConstant storeRef;

			private @Nullable MethodrefConstant envLookupRef;

			private @Nullable MethodrefConstant lookupRef;

			private @Nullable FieldrefConstant genvField;

			private @Nullable FieldrefConstant fenvField;

			private MethodrefConstant @Nullable [] invoke;

			private @Nullable MethodrefConstant invokeSpread;

			private @Nullable Map<String, JvmLispCompiler.FunctionInfo> functions;

			Builder cp(ConstantPool cp) {
				this.cp = cp;
				return this;
			}

			Builder objectClass(ClassConstant c) {
				this.objectClass = c;
				return this;
			}

			Builder objectArrayClass(ClassConstant c) {
				this.objectArrayClass = c;
				return this;
			}

			Builder integerClass(ClassConstant c) {
				this.integerClass = c;
				return this;
			}

			Builder longClass(ClassConstant c) {
				this.longClass = c;
				return this;
			}

			Builder doubleClass(ClassConstant c) {
				this.doubleClass = c;
				return this;
			}

			Builder stringClass(ClassConstant c) {
				this.stringClass = c;
				return this;
			}

			Builder integerValueOf(MethodrefConstant m) {
				this.integerValueOf = m;
				return this;
			}

			Builder integerValue(MethodrefConstant m) {
				this.integerValue = m;
				return this;
			}

			Builder longValueOf(MethodrefConstant m) {
				this.longValueOf = m;
				return this;
			}

			Builder longValue(MethodrefConstant m) {
				this.longValue = m;
				return this;
			}

			Builder stringCharAt(MethodrefConstant m) {
				this.stringCharAt = m;
				return this;
			}

			Builder stringLength(MethodrefConstant m) {
				this.stringLength = m;
				return this;
			}

			Builder objectEquals(MethodrefConstant m) {
				this.objectEquals = m;
				return this;
			}

			Builder evalRef(MethodrefConstant m) {
				this.evalRef = m;
				return this;
			}

			Builder applyRef(MethodrefConstant m) {
				this.applyRef = m;
				return this;
			}

			Builder storeRef(MethodrefConstant m) {
				this.storeRef = m;
				return this;
			}

			Builder envLookupRef(MethodrefConstant m) {
				this.envLookupRef = m;
				return this;
			}

			Builder lookupRef(MethodrefConstant m) {
				this.lookupRef = m;
				return this;
			}

			Builder genvField(FieldrefConstant f) {
				this.genvField = f;
				return this;
			}

			Builder fenvField(FieldrefConstant f) {
				this.fenvField = f;
				return this;
			}

			Builder invoke(MethodrefConstant[] invoke) {
				this.invoke = invoke;
				return this;
			}

			Builder invokeSpread(MethodrefConstant invokeSpread) {
				this.invokeSpread = invokeSpread;
				return this;
			}

			Builder functions(Map<String, JvmLispCompiler.FunctionInfo> functions) {
				this.functions = functions;
				return this;
			}

			EvalConstants build() {
				return new EvalConstants(this);
			}

		}

	}

	/** Maximum callable arity, matching the WASM backend. */
	static final int MAX_CALLABLE_ARITY = 7;

	// === label-based assembler ===

	/**
	 * A minimal one-pass assembler with symbolic labels. Branch instructions reference
	 * labels; forward references are back-patched when the label is bound. All branch
	 * offsets are 16-bit signed, which is sufficient because each generated method body
	 * is far smaller than 32 KB.
	 */
	private static final class Asm {

		final List<Integer> code = new ArrayList<>();

		private final Map<Integer, Integer> labelPos = new HashMap<>();

		private final Map<Integer, List<Integer>> pending = new HashMap<>();

		private int nextLabel = 0;

		int label() {
			return this.nextLabel++;
		}

		void bind(int label) {
			int pos = this.code.size();
			this.labelPos.put(label, pos);
			List<Integer> ps = this.pending.remove(label);
			if (ps != null) {
				for (int bp : ps) {
					JvmRuntimeBuilder.patchBranch(this.code, bp, pos);
				}
			}
		}

		void branch(int opcode, int label) {
			int bp = this.code.size();
			this.code.add(opcode);
			JvmRuntimeBuilder.emitU2(this.code, 0);
			Integer tgt = this.labelPos.get(label);
			if (tgt != null) {
				JvmRuntimeBuilder.patchBranch(this.code, bp, tgt);
			}
			else {
				this.pending.computeIfAbsent(label, k -> new ArrayList<>()).add(bp);
			}
		}

		void op(int opcode) {
			this.code.add(opcode);
		}

		void u2(int value) {
			JvmRuntimeBuilder.emitU2(this.code, value);
		}

		void aload(int slot) {
			this.code.add(Opcode.ALOAD);
			this.code.add(slot);
		}

		void astore(int slot) {
			this.code.add(Opcode.ASTORE);
			this.code.add(slot);
		}

		void iload(int slot) {
			this.code.add(Opcode.ILOAD);
			this.code.add(slot);
		}

		void istore(int slot) {
			this.code.add(Opcode.ISTORE);
			this.code.add(slot);
		}

		void iinc(int slot, int delta) {
			this.code.add(Opcode.IINC);
			this.code.add(slot);
			this.code.add(delta & 0xFF);
		}

		void aconstNull() {
			this.code.add(Opcode.ACONST_NULL);
		}

		void iconst(int n) {
			if (n == -1) {
				this.code.add(Opcode.ICONST_M1);
			}
			else if (n >= 0 && n <= 5) {
				this.code.add(Opcode.ICONST_0 + n);
			}
			else if (n >= -128 && n <= 127) {
				this.code.add(Opcode.BIPUSH);
				this.code.add(n & 0xFF);
			}
			else {
				this.code.add(Opcode.SIPUSH);
				JvmRuntimeBuilder.emitU2(this.code, n);
			}
		}

		void dup() {
			this.code.add(Opcode.DUP);
		}

		void pop() {
			this.code.add(Opcode.POP);
		}

		void areturn() {
			this.code.add(Opcode.ARETURN);
		}

		void ireturn() {
			this.code.add(Opcode.IRETURN);
		}

		void aaload() {
			this.code.add(Opcode.AALOAD);
		}

		void aastore() {
			this.code.add(Opcode.AASTORE);
		}

		void arraylength() {
			this.code.add(Opcode.ARRAYLENGTH);
		}

		void checkcast(ClassConstant c) {
			this.code.add(Opcode.CHECKCAST);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void instanceOf(ClassConstant c) {
			this.code.add(Opcode.INSTANCEOF);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void anewarray(ClassConstant c) {
			this.code.add(Opcode.ANEWARRAY);
			JvmRuntimeBuilder.emitU2(this.code, c.index());
		}

		void invokestatic(MethodrefConstant m) {
			this.code.add(Opcode.INVOKESTATIC);
			JvmRuntimeBuilder.emitU2(this.code, m.index());
		}

		void invokevirtual(MethodrefConstant m) {
			this.code.add(Opcode.INVOKEVIRTUAL);
			JvmRuntimeBuilder.emitU2(this.code, m.index());
		}

		void getstatic(FieldrefConstant f) {
			this.code.add(Opcode.GETSTATIC);
			JvmRuntimeBuilder.emitU2(this.code, f.index());
		}

		void putstatic(FieldrefConstant f) {
			this.code.add(Opcode.PUTSTATIC);
			JvmRuntimeBuilder.emitU2(this.code, f.index());
		}

		List<Integer> finish() {
			if (!this.pending.isEmpty()) {
				throw new IllegalStateException("Unbound labels in eval runtime assembly: " + this.pending.keySet());
			}
			return this.code;
		}

	}

	private final Map<String, ConstantPool.StringConstant> stringCache = new HashMap<>();

	private final EvalConstants k;

	private JvmEvalRuntimeBuilder(EvalConstants constants) {
		this.k = constants;
	}

	private void ldcStr(Asm a, String value) {
		ConstantPool.StringConstant sc = this.stringCache.computeIfAbsent(value, this.k.cp()::addString);
		if (sc.index() <= 255) {
			a.op(Opcode.LDC);
			a.code.add(sc.index());
		}
		else {
			a.op(Opcode.LDC_W);
			a.u2(sc.index());
		}
	}

	// === shared high-level emit helpers ===

	/**
	 * Pushes the slot value cast to {@code String}. The version-50 verifier does not
	 * narrow a slot's type across an {@code instanceof} check, so a {@code checkcast} is
	 * required before invoking {@code String} methods on a value held in an
	 * {@code Object} slot.
	 */
	private void aloadStr(Asm a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.stringClass());
	}

	/** Pushes {@code ((Object[]) slot)[index]}. */
	private void idx(Asm a, int slot, int index) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(index);
		a.aaload();
	}

	/** Pushes {@code ((Object[]) slot).length}. */
	private void arrLen(Asm a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.arraylength();
	}

	/** Pushes {@code car} of the cons in {@code slot}. */
	private void car(Asm a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aaload();
	}

	/** Pushes {@code cdr} of the cons in {@code slot}. */
	private void cdr(Asm a, int slot) {
		a.aload(slot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
	}

	/** Pushes {@code _eval(car(slot), env)}. */
	private void evalCar(Asm a, int slot, int envSlot) {
		car(a, slot);
		a.aload(envSlot);
		a.invokestatic(this.k.evalRef());
	}

	/** Pushes a fresh {@code Object[2]{ aload(carSlot), aload(cdrSlot) }}. */
	private void consFromSlots(Asm a, int carSlot, int cdrSlot) {
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(carSlot);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(cdrSlot);
		a.aastore();
	}

	/**
	 * Emits code that installs a function binding into the {@code _fenv} function
	 * namespace: an existing binding's value cell is mutated, otherwise a new binding is
	 * prepended. Reads the name from {@code nameSlot} and the value from
	 * {@code valueSlot}; clobbers {@code tmpSlot}. Leaves nothing on the stack.
	 */
	private void storeFunctionBinding(Asm a, int nameSlot, int valueSlot, int tmpSlot) {
		int create = a.label();
		int done = a.label();
		a.aload(nameSlot);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.branch(Opcode.IFNULL, create);
		// existing binding: binding[1] = value
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aload(valueSlot);
		a.aastore();
		a.branch(Opcode.GOTO, done);
		a.bind(create);
		// binding = new Object[]{name, value}
		consFromSlots(a, nameSlot, valueSlot);
		a.astore(tmpSlot);
		// _fenv = new Object[]{binding, _fenv}
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(tmpSlot);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.getstatic(this.k.fenvField());
		a.aastore();
		a.putstatic(this.k.fenvField());
		a.bind(done);
	}

	/**
	 * Emits code that resolves the symbol in {@code nameSlot} against the function
	 * namespace and returns the function value: a runtime {@code defun} binding in
	 * {@code _fenv} first, then the compiled function registry (wrapped as a closure
	 * {@code Object[]{Integer funcId}}), and nil when undefined. Every path ends in
	 * {@code areturn}; clobbers {@code tmpSlot}.
	 */
	private void emitFunctionLookupReturn(Asm a, int nameSlot, int tmpSlot) {
		ConstantPool.MethodrefConstant toLowerCase = stringCaseRef("toLowerCase");
		ConstantPool.MethodrefConstant toUpperCase = stringCaseRef("toUpperCase");
		// Probe the exact spelling first.
		emitFunctionProbeReturn(a, nameSlot, tmpSlot);
		// One case-flip retry: compiled references read upcased (the reader premise)
		// while runtime-read definitions are case-preserved -- and vice versa for a
		// runtime-read reference to a compiled definition. Flip to the lowercase
		// spelling (or, when already lowercase, the uppercase one) and probe again.
		int realMiss = a.label();
		int flipped = a.label();
		a.aload(nameSlot);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(toLowerCase);
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.aload(nameSlot);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, flipped);
		a.aload(nameSlot);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(toUpperCase);
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.aload(nameSlot);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFNE, realMiss);
		a.bind(flipped);
		a.aload(tmpSlot);
		a.astore(nameSlot);
		emitFunctionProbeReturn(a, nameSlot, tmpSlot);
		a.bind(realMiss);
		a.aconstNull();
		a.areturn();
	}

	// One probe pass of the function namespace: a runtime defun binding in _fenv, then
	// the compiled function registry; every hit returns, a miss falls through.
	private void emitFunctionProbeReturn(Asm a, int nameSlot, int tmpSlot) {
		int reg = a.label();
		a.aload(nameSlot);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.branch(Opcode.IFNULL, reg);
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(reg);
		int miss = a.label();
		a.aload(nameSlot);
		a.invokestatic(this.k.lookupRef());
		a.astore(tmpSlot);
		a.aload(tmpSlot);
		a.branch(Opcode.IFNULL, miss);
		a.iconst(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.aastore();
		a.areturn();
		a.bind(miss);
	}

	// A java/lang/String zero-argument case-conversion methodref.
	private ConstantPool.MethodrefConstant stringCaseRef(String method) {
		return this.k.cp()
			.addMethodref(this.k.stringClass(), this.k.cp()
				.addNameAndType(this.k.cp().addUtf8(method), this.k.cp().addUtf8("()Ljava/lang/String;")));
	}

	/**
	 * Emits {@code aload(opSlot); ldc name; equals; ifeq next} and returns the
	 * {@code next} label. The caller emits the special-form body (which must end in a
	 * return) and then binds {@code next}.
	 */
	private int special(Asm a, int opSlot, String name) {
		int next = a.label();
		aloadStr(a, opSlot);
		ldcStr(a, name);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, next);
		return next;
	}

	/**
	 * Emits a {@code progn} loop over the list at {@code restSlot}, leaving the last
	 * value (nil for an empty list) in {@code accSlot}. Consumes {@code restSlot}.
	 */
	private void prognInto(Asm a, int restSlot, int envSlot, int accSlot) {
		a.aconstNull();
		a.astore(accSlot);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.aload(restSlot);
		a.branch(Opcode.IFNULL, end);
		evalCar(a, restSlot, envSlot);
		a.astore(accSlot);
		cdr(a, restSlot);
		a.astore(restSlot);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
	}

	/**
	 * Emits a loop evaluating each form in the list at {@code restSlot} and linking the
	 * results into a fresh proper list whose head is left in {@code headSlot}. Consumes
	 * {@code restSlot} and clobbers {@code tailSlot}, {@code cellSlot}, {@code tmpSlot}.
	 */
	private void buildArgList(Asm a, int restSlot, int envSlot, int headSlot, int tailSlot, int cellSlot, int tmpSlot) {
		a.aconstNull();
		a.astore(headSlot);
		a.aconstNull();
		a.astore(tailSlot);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.aload(restSlot);
		a.branch(Opcode.IFNULL, end);
		evalCar(a, restSlot, envSlot);
		a.astore(tmpSlot);
		// cell = cons(tmp, null)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(tmpSlot);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.astore(cellSlot);
		appendCell(a, cellSlot, headSlot, tailSlot);
		cdr(a, restSlot);
		a.astore(restSlot);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
	}

	/**
	 * Emits a loop evaluating exactly {@code arity} arguments from the list at
	 * {@code restSlot} (missing arguments default to nil) and linking them into a fresh
	 * list left in {@code headSlot}. Consumes {@code restSlot} and {@code arityIntSlot}.
	 */
	private void buildNArgs(Asm a, int restSlot, int envSlot, int arityIntSlot, int headSlot, int tailSlot,
			int cellSlot, int tmpSlot) {
		a.aconstNull();
		a.astore(headSlot);
		a.aconstNull();
		a.astore(tailSlot);
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.iload(arityIntSlot);
		a.branch(Opcode.IFLE, end);
		// val = (rest == null) ? nil : eval(car rest)
		int rnull = a.label();
		int vset = a.label();
		a.aload(restSlot);
		a.branch(Opcode.IFNULL, rnull);
		evalCar(a, restSlot, envSlot);
		a.branch(Opcode.GOTO, vset);
		a.bind(rnull);
		a.aconstNull();
		a.bind(vset);
		a.astore(tmpSlot);
		// advance rest if not null
		int skipAdv = a.label();
		a.aload(restSlot);
		a.branch(Opcode.IFNULL, skipAdv);
		cdr(a, restSlot);
		a.astore(restSlot);
		a.bind(skipAdv);
		// cell = cons(tmp, null); append
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(tmpSlot);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.astore(cellSlot);
		appendCell(a, cellSlot, headSlot, tailSlot);
		a.iinc(arityIntSlot, -1);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
	}

	/**
	 * Appends the cons in {@code cellSlot} to the list tracked by {@code headSlot}/
	 * {@code tailSlot}.
	 */
	private void appendCell(Asm a, int cellSlot, int headSlot, int tailSlot) {
		int app = a.label();
		int after = a.label();
		a.aload(headSlot);
		a.branch(Opcode.IFNONNULL, app);
		a.aload(cellSlot);
		a.astore(headSlot);
		a.aload(cellSlot);
		a.astore(tailSlot);
		a.branch(Opcode.GOTO, after);
		a.bind(app);
		a.aload(tailSlot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aload(cellSlot);
		a.aastore();
		a.aload(cellSlot);
		a.astore(tailSlot);
		a.bind(after);
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
	static List<List<Integer>> buildLookupSegments(EvalConstants k, ConstantPool.ClassConstant thisClass,
			java.util.@org.jspecify.annotations.Nullable Set<Integer> dispatchable, boolean aliasReachable,
			Set<String> spelledLiterals) {
		return new JvmEvalRuntimeBuilder(k).lookupSegments(thisClass, dispatchable, aliasReachable, spelledLiterals);
	}

	/** Builds the {@code _envLookup} method body. */
	static List<Integer> buildEnvLookup(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).envLookupBody();
	}

	/** Builds the {@code _apply} method body. */
	static List<Integer> buildApply(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).applyBody();
	}

	/** Builds the {@code _store} method body. */
	static List<Integer> buildStore(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).storeBody();
	}

	/** Builds the {@code _eval} method body. */
	static List<Integer> buildEval(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).evalBody();
	}

	// === _lookup(String name) -> Object[]{Integer funcId, Integer arity} or null ===

	/** Segment budget in code bytes; see {@link #buildLookupSegments}. */
	private static final int LOOKUP_SEGMENT_BUDGET = 24_000;

	private List<List<Integer>> lookupSegments(ConstantPool.ClassConstant thisClass,
			java.util.@org.jspecify.annotations.Nullable Set<Integer> dispatchable, boolean aliasReachable,
			Set<String> spelledLiterals) {
		// Only the rows the dispatchers kept a case for: a name whose funcId has no case
		// would resolve here and then fall through the dispatcher's search tree
		// (JvmLispCompiler.dispatchableFuncIds decides both together).
		List<Map.Entry<String, JvmLispCompiler.FunctionInfo>> entries = new ArrayList<>(this.k.functions()
			.entrySet()
			.stream()
			.filter(e -> e.getValue().paramCount() <= MAX_CALLABLE_ARITY)
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
		List<List<Integer>> segments = new ArrayList<>();
		int index = 0;
		while (true) {
			Asm a = new Asm();
			while (index < entries.size() && a.code.size() < LOOKUP_SEGMENT_BUDGET) {
				Map.Entry<String, JvmLispCompiler.FunctionInfo> e = entries.get(index++);
				JvmLispCompiler.FunctionInfo fi = e.getValue();
				int next = a.label();
				a.aload(0);
				ldcStr(a, e.getKey());
				a.invokevirtual(this.k.objectEquals());
				a.branch(Opcode.IFEQ, next);
				// return new Object[]{ Integer.valueOf(funcId), Integer.valueOf(arity)
				// }; a variadic function is encoded as a negative arity
				// (-physicalParamCount) so the eval call path evaluates every argument
				// instead of exactly arity
				a.iconst(2);
				a.anewarray(this.k.objectClass());
				a.dup();
				a.iconst(0);
				a.iconst(fi.funcId());
				a.invokestatic(this.k.integerValueOf());
				a.aastore();
				a.dup();
				a.iconst(1);
				a.iconst(fi.variadic() ? -fi.paramCount() : fi.paramCount());
				a.invokestatic(this.k.integerValueOf());
				a.aastore();
				a.areturn();
				a.bind(next);
			}
			if (index < entries.size()) {
				// Continue the chain in the next segment.
				ConstantPool cp = this.k.cp();
				ConstantPool.MethodrefConstant nextRef = cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8("_lookup$" + (segments.size() + 1)),
								cp.addUtf8("(Ljava/lang/Object;)[Ljava/lang/Object;")));
				a.aload(0);
				a.invokestatic(nextRef);
				a.areturn();
				segments.add(a.finish());
				continue;
			}
			a.aconstNull();
			a.areturn();
			segments.add(a.finish());
			return segments;
		}
	}

	// === _envLookup(String name, Object env) -> binding cons or null ===

	private List<Integer> envLookupBody() {
		Asm a = new Asm();
		final int NAME = 0, ENV = 1, PAIR = 2, NAMEFIELD = 3;
		int loop = a.label();
		int retNull = a.label();
		a.bind(loop);
		a.aload(ENV);
		a.branch(Opcode.IFNULL, retNull);
		car(a, ENV);
		a.astore(PAIR);
		car(a, PAIR);
		a.astore(NAMEFIELD);
		int skip = a.label();
		a.aload(NAMEFIELD);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFEQ, skip);
		a.aload(NAMEFIELD);
		a.aload(NAME);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, skip);
		a.aload(PAIR);
		a.areturn();
		a.bind(skip);
		cdr(a, ENV);
		a.astore(ENV);
		a.branch(Opcode.GOTO, loop);
		a.bind(retNull);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	// === _apply(Object fn, Object argList) -> value ===

	private List<Integer> applyBody() {
		Asm a = new Asm();
		final int FN = 0, ARGLIST = 1, ARR = 2, PARAMS = 3, NEWENV = 4, BODY = 5, PAIR = 6, TMP = 7, ARGCUR = 8,
				ARG0 = 9;
		final int FUNCID = 17, LEN = 18;

		// fn == null -> nil
		int notNull = a.label();
		a.aload(FN);
		a.branch(Opcode.IFNONNULL, notNull);
		a.aconstNull();
		a.areturn();
		a.bind(notNull);

		// symbol designator (CL-style): a String resolves in the function namespace
		// (_fenv then the compiled registry) and the result replaces fn
		int notSym = a.label();
		int resolved = a.label();
		a.aload(FN);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFEQ, notSym);
		int desReg = a.label();
		a.aload(FN);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, desReg);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.astore(FN);
		a.branch(Opcode.GOTO, resolved);
		a.bind(desReg);
		int desMiss = a.label();
		a.aload(FN);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, desMiss);
		a.iconst(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.aastore();
		a.astore(FN);
		a.branch(Opcode.GOTO, resolved);
		a.bind(desMiss);
		// A symbol that resolves in neither _fenv nor the registry is an undefined
		// function: fail LOUDLY like the funcall dispatcher (returning nil here
		// silently swallowed (apply (intern "NOSUCH") ...)).
		emitUndefinedFunctionThrow(a, FN);
		a.bind(resolved);
		a.bind(notSym);

		// fn instanceof Object[] ?
		int notArr = a.label();
		a.aload(FN);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, notArr);
		a.aload(FN);
		a.checkcast(this.k.objectArrayClass());
		a.astore(ARR);
		idx(a, ARR, 0);
		a.checkcast(this.k.integerClass());
		a.invokevirtual(this.k.integerValue());
		a.istore(FUNCID);

		// interpreted closure? funcId == -1
		int compiled = a.label();
		a.iload(FUNCID);
		a.iconst(-1);
		a.branch(Opcode.IF_ICMPNE, compiled);
		// arr = {Integer(-1), lambdaTail, capturedEnv}
		idx(a, ARR, 1);
		a.astore(PAIR); // lambdaTail = ((params) body...)
		idx(a, ARR, 2);
		a.astore(NEWENV); // capturedEnv
		car(a, PAIR);
		a.astore(PARAMS);
		cdr(a, PAIR);
		a.astore(BODY);
		// bind params to args
		a.aload(ARGLIST);
		a.astore(ARGCUR);
		int bloop = a.label();
		int bend = a.label();
		a.bind(bloop);
		a.aload(PARAMS);
		a.branch(Opcode.IFNULL, bend);
		// pval = argcur == null ? null : car(argcur)
		int pnull = a.label();
		int pset = a.label();
		a.aload(ARGCUR);
		a.branch(Opcode.IFNULL, pnull);
		car(a, ARGCUR);
		a.branch(Opcode.GOTO, pset);
		a.bind(pnull);
		a.aconstNull();
		a.bind(pset);
		a.astore(TMP);
		// binding = cons(car(params), pval)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		car(a, PARAMS);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(TMP);
		a.aastore();
		a.astore(PAIR);
		// newenv = cons(binding, newenv)
		consFromSlots(a, PAIR, NEWENV);
		a.astore(NEWENV);
		cdr(a, PARAMS);
		a.astore(PARAMS);
		// argcur = argcur == null ? null : cdr(argcur)
		int anull = a.label();
		int aset = a.label();
		a.aload(ARGCUR);
		a.branch(Opcode.IFNULL, anull);
		cdr(a, ARGCUR);
		a.branch(Opcode.GOTO, aset);
		a.bind(anull);
		a.aconstNull();
		a.bind(aset);
		a.astore(ARGCUR);
		a.branch(Opcode.GOTO, bloop);
		a.bind(bend);
		prognInto(a, BODY, NEWENV, TMP);
		a.aload(TMP);
		a.areturn();

		// compiled closure: dispatch by argument count
		a.bind(compiled);
		a.iconst(0);
		a.istore(LEN);
		a.aload(ARGLIST);
		a.astore(ARGCUR);
		int lloop = a.label();
		int lend = a.label();
		a.bind(lloop);
		a.aload(ARGCUR);
		a.branch(Opcode.IFNULL, lend);
		a.iinc(LEN, 1);
		cdr(a, ARGCUR);
		a.astore(ARGCUR);
		a.branch(Opcode.GOTO, lloop);
		a.bind(lend);
		// One call, any argument count: the SPREAD dispatcher takes the list whole and
		// each case reads its target's required parameters out of it, handing a variadic
		// target the remaining tail. The per-arity dispatchers cannot serve apply -- they
		// take one JVM parameter per Lisp argument, so they stop at MAX_CALLABLE_ARITY,
		// and an apply past it used to fall off the ladder and answer nil.
		a.aload(FN);
		a.aload(ARGLIST);
		a.invokestatic(this.k.invokeSpread());
		a.areturn();

		a.bind(notArr);
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	/**
	 * Emits {@code throw new RuntimeException("The function " + name + " is
	 * undefined")}, {@code name} being the String symbol in {@code nameSlot} -- the same
	 * text (and catchability) as the funcall dispatchers' miss arm
	 * ({@link JvmRuntimeBuilder}).
	 */
	private void emitUndefinedFunctionThrow(Asm a, int nameSlot) {
		ConstantPool cp = this.k.cp();
		ConstantPool.ClassConstant runtimeEx = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant exCtor = cp.addMethodref(runtimeEx,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant concat = cp.addMethodref(this.k.stringClass(),
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		a.op(Opcode.NEW);
		a.u2(runtimeEx.index());
		a.dup();
		ldcStr(a, "The function ");
		a.aload(nameSlot);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(concat);
		ldcStr(a, " is undefined");
		a.invokevirtual(concat);
		a.op(Opcode.INVOKESPECIAL);
		a.u2(exCtor.index());
		a.op(Opcode.ATHROW);
	}

	// === _store(place, value, env) -> value ===

	private List<Integer> storeBody() {
		Asm a = new Asm();
		final int PLACE = 0, VALUE = 1, ENV = 2, OP = 3, TMP = 4, TARGET = 5, ARGS = 6;
		final int IDX = 8, FIELD = 9, CH = 10, LEN = 11, VALID = 12;

		// Initialize TARGET so the slot has a reference type on every path (the inference
		// verifier is flow-insensitive about the FIELD guard before the store).
		a.aconstNull();
		a.astore(TARGET);

		// --- symbol place: variable assignment ---
		int notSym = a.label();
		a.aload(PLACE);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFEQ, notSym);
		storeSymbolBinding(a, PLACE, ENV, VALUE, TMP, false);
		storeSymbolBinding(a, PLACE, ENV, VALUE, TMP, true);
		// not bound anywhere: prepend a new binding to the global environment
		consFromSlots(a, PLACE, VALUE);
		a.astore(TMP);
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(TMP);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.getstatic(this.k.genvField());
		a.aastore();
		a.putstatic(this.k.genvField());
		a.aload(VALUE);
		a.areturn();
		a.bind(notSym);

		// --- accessor place: (op arg...) ---
		int isArr = a.label();
		a.aload(PLACE);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFNE, isArr);
		a.aload(VALUE);
		a.areturn();
		a.bind(isArr);
		car(a, PLACE);
		a.astore(OP);
		int opStr = a.label();
		a.aload(OP);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFNE, opStr);
		a.aload(VALUE);
		a.areturn();
		a.bind(opStr);
		cdr(a, PLACE);
		a.astore(ARGS);
		// FIELD = -1 (no target resolved)
		a.iconst(-1);
		a.istore(FIELD);

		// nth: walk n cdrs, set car
		int afterNth = special(a, OP, LispNames.NTH);
		evalCar(a, ARGS, ENV);
		a.checkcast(this.k.longClass());
		a.invokevirtual(this.k.longValue());
		a.op(Opcode.L2I);
		a.istore(IDX);
		cdr(a, ARGS);
		a.astore(ARGS);
		evalCar(a, ARGS, ENV);
		a.astore(TARGET);
		walkCdrs(a, TARGET, IDX);
		a.iconst(0);
		a.istore(FIELD);
		a.bind(afterNth);

		// first/second/third/fourth: k cdrs, set car
		fixedAccessorTarget(a, OP, LispNames.FIRST, ARGS, ENV, TARGET, FIELD, 0);
		fixedAccessorTarget(a, OP, LispNames.SECOND, ARGS, ENV, TARGET, FIELD, 1);
		fixedAccessorTarget(a, OP, LispNames.THIRD, ARGS, ENV, TARGET, FIELD, 2);
		fixedAccessorTarget(a, OP, LispNames.FOURTH, ARGS, ENV, TARGET, FIELD, 3);

		// car/cdr and c[ad]+r compositions (only if no named accessor matched)
		int skipCarCdr = a.label();
		a.iload(FIELD);
		a.iconst(-1);
		a.branch(Opcode.IF_ICMPNE, skipCarCdr);
		carCdrStoreTarget(a, OP, ARGS, ENV, TARGET, FIELD, IDX, CH, LEN, VALID);
		a.bind(skipCarCdr);

		// store: if a target was resolved and is a cons, set car (FIELD 0) or cdr (FIELD
		// 1)
		int doneStore = a.label();
		a.iload(FIELD);
		a.iconst(-1);
		a.branch(Opcode.IF_ICMPEQ, doneStore);
		a.aload(TARGET);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, doneStore);
		int setCdr = a.label();
		int afterSet = a.label();
		a.iload(FIELD);
		a.branch(Opcode.IFNE, setCdr);
		a.aload(TARGET);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aload(VALUE);
		a.aastore();
		a.branch(Opcode.GOTO, afterSet);
		a.bind(setCdr);
		a.aload(TARGET);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aload(VALUE);
		a.aastore();
		a.bind(afterSet);
		a.bind(doneStore);
		a.aload(VALUE);
		a.areturn();
		return a.finish();
	}

	/**
	 * Emits {@code binding = _envLookup(name, env-or-global); if (binding != null) {
	 * binding.cdr = value; return value; }}, for the lexical ({@code global == false}) or
	 * global ({@code global == true}) environment.
	 */
	private void storeSymbolBinding(Asm a, int nameSlot, int envSlot, int valueSlot, int tmpSlot, boolean global) {
		a.aload(nameSlot);
		if (global) {
			a.getstatic(this.k.genvField());
		}
		else {
			a.aload(envSlot);
		}
		a.invokestatic(this.k.envLookupRef());
		a.astore(tmpSlot);
		int skip = a.label();
		a.aload(tmpSlot);
		a.branch(Opcode.IFNULL, skip);
		a.aload(tmpSlot);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aload(valueSlot);
		a.aastore();
		a.aload(valueSlot);
		a.areturn();
		a.bind(skip);
	}

	/**
	 * Emits {@code _store} handling of a fixed numbered accessor (e.g. {@code second}):
	 * if the operator equals {@code name}, evaluates the single argument, walks
	 * {@code cdrCount} cdrs into {@code targetSlot} and sets {@code fieldSlot} to 0
	 * (car).
	 */
	private void fixedAccessorTarget(Asm a, int opSlot, String name, int argsSlot, int envSlot, int targetSlot,
			int fieldSlot, int cdrCount) {
		int next = special(a, opSlot, name);
		evalCar(a, argsSlot, envSlot);
		a.astore(targetSlot);
		for (int i = 0; i < cdrCount; i++) {
			cdr(a, targetSlot);
			a.astore(targetSlot);
		}
		a.iconst(0);
		a.istore(fieldSlot);
		a.bind(next);
	}

	/** Emits a loop replacing {@code targetSlot} with its cdr {@code idxSlot} times. */
	private void walkCdrs(Asm a, int targetSlot, int idxSlot) {
		int loop = a.label();
		int end = a.label();
		a.bind(loop);
		a.iload(idxSlot);
		a.branch(Opcode.IFLE, end);
		a.aload(targetSlot);
		a.branch(Opcode.IFNULL, end);
		cdr(a, targetSlot);
		a.astore(targetSlot);
		a.iinc(idxSlot, -1);
		a.branch(Opcode.GOTO, loop);
		a.bind(end);
	}

	/**
	 * Emits {@code _store} handling of {@code car}/{@code cdr} and the {@code c[ad]+r}
	 * compositions. When the operator matches the pattern, evaluates the single argument,
	 * applies the inner operations into {@code targetSlot}, and sets {@code fieldSlot} to
	 * 0 (outer op {@code a}) or 1 (outer op {@code d}); otherwise leaves
	 * {@code fieldSlot} unchanged.
	 */
	private void carCdrStoreTarget(Asm a, int opSlot, int argsSlot, int envSlot, int targetSlot, int fieldSlot,
			int idxSlot, int chSlot, int lenSlot, int validSlot) {
		aloadStr(a, opSlot);
		a.invokevirtual(this.k.stringLength());
		a.istore(lenSlot);
		int noMatch = a.label();
		a.iload(lenSlot);
		a.iconst(3);
		a.branch(Opcode.IF_ICMPLT, noMatch);
		aloadStr(a, opSlot);
		a.iconst(0);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('C');
		a.branch(Opcode.IF_ICMPNE, noMatch);
		aloadStr(a, opSlot);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('R');
		a.branch(Opcode.IF_ICMPNE, noMatch);
		// scan middle bytes: valid = all in {'a','d'}
		a.iconst(1);
		a.istore(validSlot);
		a.iconst(1);
		a.istore(idxSlot);
		int sloop = a.label();
		int send = a.label();
		a.bind(sloop);
		a.iload(idxSlot);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPGE, send);
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.istore(chSlot);
		int okch = a.label();
		a.iload(chSlot);
		a.iconst('A');
		a.branch(Opcode.IF_ICMPEQ, okch);
		a.iload(chSlot);
		a.iconst('D');
		a.branch(Opcode.IF_ICMPEQ, okch);
		a.iconst(0);
		a.istore(validSlot);
		a.branch(Opcode.GOTO, send);
		a.bind(okch);
		a.iinc(idxSlot, 1);
		a.branch(Opcode.GOTO, sloop);
		a.bind(send);
		int notValid = a.label();
		a.iload(validSlot);
		a.branch(Opcode.IFEQ, notValid);
		evalCar(a, argsSlot, envSlot);
		a.astore(targetSlot);
		// apply inner ops (indices len-2 down to 2)
		a.iload(lenSlot);
		a.iconst(2);
		a.op(Opcode.ISUB);
		a.istore(idxSlot);
		int iloop = a.label();
		int iend = a.label();
		a.bind(iloop);
		a.iload(idxSlot);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPLT, iend);
		int isCdr = a.label();
		int afterc = a.label();
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('A');
		a.branch(Opcode.IF_ICMPNE, isCdr);
		car(a, targetSlot);
		a.astore(targetSlot);
		a.branch(Opcode.GOTO, afterc);
		a.bind(isCdr);
		cdr(a, targetSlot);
		a.astore(targetSlot);
		a.bind(afterc);
		a.iinc(idxSlot, -1);
		a.branch(Opcode.GOTO, iloop);
		a.bind(iend);
		// field = (op.charAt(1) == 'd') ? 1 : 0
		a.iconst(0);
		a.istore(fieldSlot);
		int notD = a.label();
		aloadStr(a, opSlot);
		a.iconst(1);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('D');
		a.branch(Opcode.IF_ICMPNE, notD);
		a.iconst(1);
		a.istore(fieldSlot);
		a.bind(notD);
		a.bind(notValid);
		a.bind(noMatch);
	}

	// === _eval(form, env) -> value ===

	private List<Integer> evalBody() {
		Asm a = new Asm();
		final int VAL = 0, ENV = 1, REST = 2, TMP = 3, OP = 4, ARGHEAD = 5, ARGTAIL = 6, NEWCELL = 7, ACC = 8, FN = 9,
				BODY = 10, BINDCUR = 11, ELEM = 12;
		final int IDX = 15, LEN = 16, CH = 17, ARITY = 18, VALID = 19;

		// --- self-evaluating: nil, Long, Double ---
		int notNil = a.label();
		a.aload(VAL);
		a.branch(Opcode.IFNONNULL, notNil);
		a.aload(VAL);
		a.areturn();
		a.bind(notNil);
		int notLong = a.label();
		a.aload(VAL);
		a.instanceOf(this.k.longClass());
		a.branch(Opcode.IFEQ, notLong);
		a.aload(VAL);
		a.areturn();
		a.bind(notLong);
		int notDouble = a.label();
		a.aload(VAL);
		a.instanceOf(this.k.doubleClass());
		a.branch(Opcode.IFEQ, notDouble);
		a.aload(VAL);
		a.areturn();
		a.bind(notDouble);
		// a BigInteger (an exact integer past the long range) is self-evaluating too
		ClassConstant bigIntegerClass = this.k.cp().addClass(this.k.cp().addUtf8("java/math/BigInteger"));
		int notBigInteger = a.label();
		a.aload(VAL);
		a.instanceOf(bigIntegerClass);
		a.branch(Opcode.IFEQ, notBigInteger);
		a.aload(VAL);
		a.areturn();
		a.bind(notBigInteger);

		// --- ratios (BigInteger[]) are self-evaluating; checked before the generic
		// Object[] form handling because a ratio is also an Object[] ---
		ClassConstant ratioArrayClass = this.k.cp().addClass(this.k.cp().addUtf8("[Ljava/math/BigInteger;"));
		int notRatio = a.label();
		a.aload(VAL);
		a.instanceOf(ratioArrayClass);
		a.branch(Opcode.IFEQ, notRatio);
		a.aload(VAL);
		a.areturn();
		a.bind(notRatio);

		// --- strings: string literal (self-eval) or symbol (variable reference) ---
		int notStr = a.label();
		a.aload(VAL);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFEQ, notStr);
		int sym = a.label();
		aloadStr(a, VAL);
		a.iconst(0);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst(QUOTE_CHAR);
		a.branch(Opcode.IF_ICMPNE, sym);
		a.aload(VAL);
		a.areturn();
		a.bind(sym);
		// lexical lookup
		int global = a.label();
		a.aload(VAL);
		a.aload(ENV);
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, global);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(global);
		// global lookup; Lisp-2: a bare symbol resolves the variable namespace only,
		// never the function registry. An unbound symbol retries the case-flipped
		// spelling once (compiled references read upcased while runtime-read
		// definitions are case-preserved, and vice versa), then evaluates to ITSELF
		// under its original spelling.
		ConstantPool.MethodrefConstant varToLowerCase = stringCaseRef("toLowerCase");
		ConstantPool.MethodrefConstant varToUpperCase = stringCaseRef("toUpperCase");
		int self = a.label();
		int varFlipped = a.label();
		int retry = a.label();
		a.aload(VAL);
		a.getstatic(this.k.genvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, retry);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(retry);
		a.aload(VAL);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(varToLowerCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(VAL);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, varFlipped);
		a.aload(VAL);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(varToUpperCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(VAL);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFNE, self);
		a.bind(varFlipped);
		a.aload(TMP);
		a.getstatic(this.k.genvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, self);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(self);
		a.aload(VAL);
		a.areturn();
		a.bind(notStr);

		// --- Object[]: function value (self-eval) or cons (special form / application)
		// ---
		int isArr = a.label();
		a.aload(VAL);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFNE, isArr);
		a.aconstNull();
		a.areturn();
		a.bind(isArr);
		int cons = a.label();
		arrLen(a, VAL);
		a.branch(Opcode.IFEQ, cons);
		car(a, VAL);
		a.instanceOf(this.k.integerClass());
		a.branch(Opcode.IFEQ, cons);
		a.aload(VAL);
		a.areturn();
		a.bind(cons);
		car(a, VAL);
		a.astore(OP);
		cdr(a, VAL);
		a.astore(REST);

		// non-symbol operator (inline lambda): evaluate it, then apply
		int symOp = a.label();
		a.aload(OP);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFNE, symOp);
		a.aload(OP);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.astore(FN);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.bind(symOp);

		// ---- quote ----
		int n = special(a, OP, LispNames.QUOTE);
		car(a, REST);
		a.areturn();
		a.bind(n);

		// ---- if ----
		n = special(a, OP, LispNames.IF);
		evalCar(a, REST, ENV);
		int testTrue = a.label();
		a.branch(Opcode.IFNONNULL, testTrue);
		// false branch: rest = cddr; if null nil else eval(car)
		cdr(a, REST);
		a.astore(REST);
		cdr(a, REST);
		a.astore(REST);
		int hasElse = a.label();
		a.aload(REST);
		a.branch(Opcode.IFNONNULL, hasElse);
		a.aconstNull();
		a.areturn();
		a.bind(hasElse);
		evalCar(a, REST, ENV);
		a.areturn();
		a.bind(testTrue);
		cdr(a, REST);
		a.astore(REST);
		evalCar(a, REST, ENV);
		a.areturn();
		a.bind(n);

		// ---- progn ----
		n = special(a, OP, LispNames.PROGN);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.bind(n);

		// ---- let ----
		n = special(a, OP, LispNames.LET);
		cdr(a, REST);
		a.astore(BODY);
		car(a, REST);
		a.astore(BINDCUR);
		a.aload(ENV);
		a.astore(ELEM); // newEnv accumulator
		int letLoop = a.label();
		int letEnd = a.label();
		a.bind(letLoop);
		a.aload(BINDCUR);
		a.branch(Opcode.IFNULL, letEnd);
		car(a, BINDCUR);
		a.astore(TMP); // (name value)
		cdr(a, TMP);
		a.astore(NEWCELL); // (value)
		evalCar(a, NEWCELL, ENV);
		a.astore(NEWCELL); // value evaluated in the outer env
		// binding = cons(car(TMP), value)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		car(a, TMP);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(NEWCELL);
		a.aastore();
		a.astore(TMP);
		consFromSlots(a, TMP, ELEM);
		a.astore(ELEM);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.branch(Opcode.GOTO, letLoop);
		a.bind(letEnd);
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ELEM, TMP);
		a.aload(TMP);
		a.areturn();
		a.bind(n);

		// ---- lambda ----
		n = special(a, OP, LispNames.LAMBDA);
		a.iconst(3);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.iconst(-1);
		a.invokestatic(this.k.integerValueOf());
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aload(REST);
		a.aastore();
		a.dup();
		a.iconst(2);
		a.aload(ENV);
		a.aastore();
		a.areturn();
		a.bind(n);

		// ---- defun: (defun name (params) body...) builds a closure and installs it
		// into the _fenv function namespace so loaded files can define functions ----
		n = special(a, OP, LispNames.DEFUN);
		car(a, REST);
		a.astore(ACC); // name symbol
		// lambdaForm = cons("lambda", cdr(REST))
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		ldcStr(a, LispNames.LAMBDA);
		a.aastore();
		a.dup();
		a.iconst(1);
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
		a.bind(n);

		// ---- function: (function name) / #'name resolves the function namespace;
		// (function (lambda ...)) evaluates to a closure ----
		n = special(a, OP, LispNames.FUNCTION);
		car(a, REST);
		a.astore(ACC); // designator (unevaluated)
		int fnSym = a.label();
		a.aload(ACC);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFNE, fnSym);
		// non-symbol designator (a lambda form): evaluate it
		a.aload(ACC);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.areturn();
		a.bind(fnSym);
		emitFunctionLookupReturn(a, ACC, TMP);
		a.bind(n);

		// ---- symbol-function: like function but the argument is evaluated ----
		n = special(a, OP, LispNames.SYMBOL_FUNCTION);
		evalCar(a, REST, ENV);
		a.astore(ACC);
		int sfSym = a.label();
		a.aload(ACC);
		a.instanceOf(this.k.stringClass());
		a.branch(Opcode.IFNE, sfSym);
		a.aconstNull();
		a.areturn();
		a.bind(sfSym);
		emitFunctionLookupReturn(a, ACC, TMP);
		a.bind(n);

		// ---- cond ----
		n = special(a, OP, LispNames.COND);
		a.aload(REST);
		a.astore(BINDCUR);
		int condLoop = a.label();
		int condEnd = a.label();
		a.bind(condLoop);
		a.aload(BINDCUR);
		a.branch(Opcode.IFNULL, condEnd);
		car(a, BINDCUR);
		a.astore(TMP); // clause
		evalCar(a, TMP, ENV);
		a.astore(ACC); // test value
		int nextClause = a.label();
		a.aload(ACC);
		a.branch(Opcode.IFNULL, nextClause);
		cdr(a, TMP);
		a.astore(BODY);
		int hasBody = a.label();
		a.aload(BODY);
		a.branch(Opcode.IFNONNULL, hasBody);
		a.aload(ACC);
		a.areturn();
		a.bind(hasBody);
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.bind(nextClause);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.branch(Opcode.GOTO, condLoop);
		a.bind(condEnd);
		a.aconstNull();
		a.areturn();
		a.bind(n);

		// ---- and ----
		n = special(a, OP, LispNames.AND);
		int andNotEmpty = a.label();
		a.aload(REST);
		a.branch(Opcode.IFNONNULL, andNotEmpty);
		ldcStr(a, "T");
		a.areturn();
		a.bind(andNotEmpty);
		a.aload(REST);
		a.astore(BINDCUR);
		a.aconstNull();
		a.astore(ACC);
		int andLoop = a.label();
		int andEnd = a.label();
		a.bind(andLoop);
		a.aload(BINDCUR);
		a.branch(Opcode.IFNULL, andEnd);
		evalCar(a, BINDCUR, ENV);
		a.astore(ACC);
		int andOk = a.label();
		a.aload(ACC);
		a.branch(Opcode.IFNONNULL, andOk);
		a.aconstNull();
		a.areturn();
		a.bind(andOk);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.branch(Opcode.GOTO, andLoop);
		a.bind(andEnd);
		a.aload(ACC);
		a.areturn();
		a.bind(n);

		// ---- or ----
		n = special(a, OP, LispNames.OR);
		a.aload(REST);
		a.astore(BINDCUR);
		int orLoop = a.label();
		int orEnd = a.label();
		a.bind(orLoop);
		a.aload(BINDCUR);
		a.branch(Opcode.IFNULL, orEnd);
		evalCar(a, BINDCUR, ENV);
		a.astore(ACC);
		int orNot = a.label();
		a.aload(ACC);
		a.branch(Opcode.IFNULL, orNot);
		a.aload(ACC);
		a.areturn();
		a.bind(orNot);
		cdr(a, BINDCUR);
		a.astore(BINDCUR);
		a.branch(Opcode.GOTO, orLoop);
		a.bind(orEnd);
		a.aconstNull();
		a.areturn();
		a.bind(n);

		// ---- when ----
		n = special(a, OP, LispNames.WHEN);
		evalCar(a, REST, ENV);
		int whenProceed = a.label();
		a.branch(Opcode.IFNONNULL, whenProceed);
		a.aconstNull();
		a.areturn();
		a.bind(whenProceed);
		cdr(a, REST);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.bind(n);

		// ---- unless ----
		n = special(a, OP, LispNames.UNLESS);
		evalCar(a, REST, ENV);
		int unlessProceed = a.label();
		a.branch(Opcode.IFNULL, unlessProceed);
		a.aconstNull();
		a.areturn();
		a.bind(unlessProceed);
		cdr(a, REST);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.aload(TMP);
		a.areturn();
		a.bind(n);

		// ---- while: (while test body...) -> evaluate body while test is non-nil ----
		n = special(a, OP, LispNames.WHILE);
		car(a, REST);
		a.astore(FN); // test form
		cdr(a, REST);
		a.astore(BODY); // body list
		int whileLoop = a.label();
		int whileEnd = a.label();
		a.bind(whileLoop);
		a.aload(FN);
		a.aload(ENV);
		a.invokestatic(this.k.evalRef());
		a.branch(Opcode.IFNULL, whileEnd);
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ENV, TMP);
		a.branch(Opcode.GOTO, whileLoop);
		a.bind(whileEnd);
		a.aconstNull();
		a.areturn();
		a.bind(n);

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
		a.op(Opcode.L2I);
		a.istore(ARITY); // count limit
		cdr(a, ACC);
		a.astore(ACC); // (result?) or nil
		int dtHasResult = a.label();
		int dtResultDone = a.label();
		a.aload(ACC);
		a.branch(Opcode.IFNONNULL, dtHasResult);
		a.aconstNull();
		a.astore(FN);
		a.branch(Opcode.GOTO, dtResultDone);
		a.bind(dtHasResult);
		car(a, ACC);
		a.astore(FN); // result form
		a.bind(dtResultDone);
		cdr(a, REST);
		a.astore(BODY); // body list
		// bindCell = cons(var, Long(0)); newEnv = cons(bindCell, env)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(BINDCUR);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.iconst(0);
		a.op(Opcode.I2L);
		a.invokestatic(this.k.longValueOf());
		a.aastore();
		a.astore(NEWCELL); // mutable binding cell
		consFromSlots(a, NEWCELL, ENV);
		a.astore(ELEM); // extended environment
		a.iconst(0);
		a.istore(IDX); // loop counter
		int dtLoop = a.label();
		int dtEnd = a.label();
		a.bind(dtLoop);
		a.iload(IDX);
		a.iload(ARITY);
		a.branch(Opcode.IF_ICMPGE, dtEnd);
		// bindCell[1] = Long(i)
		a.aload(NEWCELL);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.iload(IDX);
		a.op(Opcode.I2L);
		a.invokestatic(this.k.longValueOf());
		a.aastore();
		a.aload(BODY);
		a.astore(REST);
		prognInto(a, REST, ELEM, TMP);
		a.iinc(IDX, 1);
		a.branch(Opcode.GOTO, dtLoop);
		a.bind(dtEnd);
		// var = count for the result form
		a.aload(NEWCELL);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.iload(ARITY);
		a.op(Opcode.I2L);
		a.invokestatic(this.k.longValueOf());
		a.aastore();
		int dtResult = a.label();
		a.aload(FN);
		a.branch(Opcode.IFNONNULL, dtResult);
		a.aconstNull();
		a.areturn();
		a.bind(dtResult);
		a.aload(FN);
		a.aload(ELEM);
		a.invokestatic(this.k.evalRef());
		a.areturn();
		a.bind(n);

		// ---- setq ----
		n = special(a, OP, LispNames.SETQ);
		a.aload(REST);
		a.astore(BINDCUR);
		a.aconstNull();
		a.astore(ACC);
		int setqLoop = a.label();
		int setqEnd = a.label();
		a.bind(setqLoop);
		a.aload(BINDCUR);
		a.branch(Opcode.IFNULL, setqEnd);
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
		a.branch(Opcode.GOTO, setqLoop);
		a.bind(setqEnd);
		a.aload(ACC);
		a.areturn();
		a.bind(n);

		// ---- setf ----
		n = special(a, OP, LispNames.SETF);
		car(a, REST); // place
		cdr(a, REST);
		a.astore(NEWCELL);
		evalCar(a, NEWCELL, ENV); // value
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.areturn();
		a.bind(n);

		// ---- push: (push item place) ----
		n = special(a, OP, LispNames.PUSH);
		evalCar(a, REST, ENV);
		a.astore(ACC); // item
		cdr(a, REST);
		a.astore(REST); // (place)
		car(a, REST);
		a.astore(BODY); // place form
		// newval = cons(item, eval(place))
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(ACC);
		a.aastore();
		a.dup();
		a.iconst(1);
		evalCar(a, REST, ENV);
		a.aastore();
		a.astore(ACC);
		a.aload(BODY);
		a.aload(ACC);
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.areturn();
		a.bind(n);

		// ---- pop: (pop place) ----
		n = special(a, OP, LispNames.POP);
		car(a, REST);
		a.astore(BODY); // place form
		evalCar(a, REST, ENV);
		a.astore(ELEM); // current list value
		int popNotCons = a.label();
		int popAfter = a.label();
		a.aload(ELEM);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, popNotCons);
		car(a, ELEM);
		a.astore(ACC);
		cdr(a, ELEM);
		a.astore(TMP);
		a.branch(Opcode.GOTO, popAfter);
		a.bind(popNotCons);
		a.aconstNull();
		a.astore(ACC);
		a.aconstNull();
		a.astore(TMP);
		a.bind(popAfter);
		a.aload(BODY);
		a.aload(TMP);
		a.aload(ENV);
		a.invokestatic(this.k.storeRef());
		a.pop();
		a.aload(ACC);
		a.areturn();
		a.bind(n);

		// ---- eval (nested) ----
		n = special(a, OP, LispNames.EVAL);
		evalCar(a, REST, ENV);
		a.aconstNull();
		a.invokestatic(this.k.evalRef());
		a.areturn();
		a.bind(n);

		// ---- funcall ----
		n = special(a, OP, LispNames.FUNCALL);
		evalCar(a, REST, ENV);
		a.astore(FN);
		cdr(a, REST);
		a.astore(REST);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.bind(n);

		// ---- mapcar: (mapcar fn list) ----
		n = special(a, OP, LispNames.MAPCAR);
		evalCar(a, REST, ENV);
		a.astore(FN);
		cdr(a, REST);
		a.astore(REST);
		evalCar(a, REST, ENV);
		a.astore(ELEM); // input list cursor
		a.aconstNull();
		a.astore(ARGHEAD);
		a.aconstNull();
		a.astore(ARGTAIL);
		int mapLoop = a.label();
		int mapEnd = a.label();
		a.bind(mapLoop);
		a.aload(ELEM);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, mapEnd);
		// argl = cons(car(ELEM), null)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		car(a, ELEM);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.astore(NEWCELL);
		a.aload(FN);
		a.aload(NEWCELL);
		a.invokestatic(this.k.applyRef());
		a.astore(TMP);
		// cell = cons(mapped, null)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(TMP);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.astore(NEWCELL);
		appendCell(a, NEWCELL, ARGHEAD, ARGTAIL);
		cdr(a, ELEM);
		a.astore(ELEM);
		a.branch(Opcode.GOTO, mapLoop);
		a.bind(mapEnd);
		a.aload(ARGHEAD);
		a.areturn();
		a.bind(n);

		// ---- mapc: (mapc fn list) — apply for effect, return the list ----
		n = special(a, OP, LispNames.MAPC);
		evalCar(a, REST, ENV);
		a.astore(FN);
		cdr(a, REST);
		a.astore(REST);
		evalCar(a, REST, ENV);
		a.astore(ARGHEAD); // original list, returned at the end
		a.aload(ARGHEAD);
		a.astore(ELEM); // input list cursor
		int mapcLoop = a.label();
		int mapcEnd = a.label();
		a.bind(mapcLoop);
		a.aload(ELEM);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, mapcEnd);
		// argl = cons(car(ELEM), null)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		car(a, ELEM);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.astore(NEWCELL);
		a.aload(FN);
		a.aload(NEWCELL);
		a.invokestatic(this.k.applyRef());
		a.pop(); // discard the result
		cdr(a, ELEM);
		a.astore(ELEM);
		a.branch(Opcode.GOTO, mapcLoop);
		a.bind(mapcEnd);
		a.aload(ARGHEAD);
		a.areturn();
		a.bind(n);

		// ---- reduce: (reduce fn list) or (reduce fn list :initial-value init) ----
		n = special(a, OP, LispNames.REDUCE);
		evalCar(a, REST, ENV);
		a.astore(FN);
		cdr(a, REST);
		a.astore(REST);
		cdr(a, REST);
		a.astore(TMP); // cdr(rest): null for 2-arg, (:initial-value init) for keyword
						// form
		int withInit = a.label();
		int afterInit = a.label();
		a.aload(TMP);
		a.branch(Opcode.IFNONNULL, withInit);
		// 2-arg: list = eval(car rest); acc = car(list); list = cdr(list)
		evalCar(a, REST, ENV);
		a.astore(ELEM);
		car(a, ELEM);
		a.astore(ACC);
		cdr(a, ELEM);
		a.astore(ELEM);
		a.branch(Opcode.GOTO, afterInit);
		a.bind(withInit);
		// keyword form: list = eval(car rest); acc = eval(car (cdr (cdr rest)))
		evalCar(a, REST, ENV);
		a.astore(ELEM);
		cdr(a, TMP); // TMP = (init)
		a.astore(TMP);
		evalCar(a, TMP, ENV);
		a.astore(ACC);
		a.bind(afterInit);
		int redLoop = a.label();
		int redEnd = a.label();
		a.bind(redLoop);
		a.aload(ELEM);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, redEnd);
		// inner = cons(car(ELEM), null)
		a.iconst(2);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		car(a, ELEM);
		a.aastore();
		a.dup();
		a.iconst(1);
		a.aconstNull();
		a.aastore();
		a.astore(NEWCELL);
		// argl = cons(acc, inner)
		consFromSlots(a, ACC, NEWCELL);
		a.astore(NEWCELL);
		a.aload(FN);
		a.aload(NEWCELL);
		a.invokestatic(this.k.applyRef());
		a.astore(ACC);
		cdr(a, ELEM);
		a.astore(ELEM);
		a.branch(Opcode.GOTO, redLoop);
		a.bind(redEnd);
		a.aload(ACC);
		a.areturn();
		a.bind(n);

		// ---- first/second/third/fourth ----
		fixedAccessorEval(a, OP, LispNames.FIRST, REST, ENV, ACC, 0);
		fixedAccessorEval(a, OP, LispNames.SECOND, REST, ENV, ACC, 1);
		fixedAccessorEval(a, OP, LispNames.THIRD, REST, ENV, ACC, 2);
		fixedAccessorEval(a, OP, LispNames.FOURTH, REST, ENV, ACC, 3);

		// ---- rest: (rest lst) -> (cdr lst) ----
		n = special(a, OP, LispNames.REST);
		evalCar(a, REST, ENV);
		a.astore(ACC);
		cdr(a, ACC);
		a.areturn();
		a.bind(n);

		// ---- nth: (nth n list) ----
		n = special(a, OP, LispNames.NTH);
		evalCar(a, REST, ENV);
		a.checkcast(this.k.longClass());
		a.invokevirtual(this.k.longValue());
		a.op(Opcode.L2I);
		a.istore(IDX);
		cdr(a, REST);
		a.astore(REST);
		evalCar(a, REST, ENV);
		a.astore(ACC);
		int nthLoop = a.label();
		int nthEnd = a.label();
		a.bind(nthLoop);
		a.iload(IDX);
		a.branch(Opcode.IFLE, nthEnd);
		a.aload(ACC);
		a.branch(Opcode.IFNULL, nthEnd);
		cdr(a, ACC);
		a.astore(ACC);
		a.iinc(IDX, -1);
		a.branch(Opcode.GOTO, nthLoop);
		a.bind(nthEnd);
		int nthNil = a.label();
		a.aload(ACC);
		a.instanceOf(this.k.objectArrayClass());
		a.branch(Opcode.IFEQ, nthNil);
		car(a, ACC);
		a.areturn();
		a.bind(nthNil);
		a.aconstNull();
		a.areturn();
		a.bind(n);

		// ---- list (variadic) ----
		n = special(a, OP, LispNames.LIST);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(ARGHEAD);
		a.areturn();
		a.bind(n);

		// ---- variadic + - * / : left-fold via the binary wrapper ----
		int arith = a.label();
		int notArith = a.label();
		for (String opName : new String[] { LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV }) {
			a.aload(OP);
			ldcStr(a, opName);
			a.invokevirtual(this.k.objectEquals());
			a.branch(Opcode.IFNE, arith);
		}
		a.branch(Opcode.GOTO, notArith);
		a.bind(arith);
		a.aload(OP);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		a.iconst(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.aastore();
		a.astore(FN);
		evalCar(a, REST, ENV);
		a.astore(ACC);
		cdr(a, REST);
		a.astore(REST);
		// single argument: (- x) negates and (/ x) takes the reciprocal, by seeding
		// the fold with the identity element (0 - x, 1 / x)
		int notUnary = a.label();
		int unaryDiv = a.label();
		a.aload(REST);
		a.branch(Opcode.IFNONNULL, notUnary);
		a.aload(OP);
		ldcStr(a, LispNames.SUB);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, unaryDiv);
		a.aload(FN);
		a.op(Opcode.LCONST_0);
		a.invokestatic(this.k.longValueOf());
		a.aload(ACC);
		a.invokestatic(this.k.invoke()[2]);
		a.astore(ACC);
		a.branch(Opcode.GOTO, notUnary);
		a.bind(unaryDiv);
		a.aload(OP);
		ldcStr(a, LispNames.DIV);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, notUnary);
		a.aload(FN);
		a.op(Opcode.LCONST_1);
		a.invokestatic(this.k.longValueOf());
		a.aload(ACC);
		a.invokestatic(this.k.invoke()[2]);
		a.astore(ACC);
		a.bind(notUnary);
		int foldLoop = a.label();
		int foldEnd = a.label();
		a.bind(foldLoop);
		a.aload(REST);
		a.branch(Opcode.IFNULL, foldEnd);
		a.aload(FN);
		a.aload(ACC);
		evalCar(a, REST, ENV);
		a.invokestatic(this.k.invoke()[2]);
		a.astore(ACC);
		cdr(a, REST);
		a.astore(REST);
		a.branch(Opcode.GOTO, foldLoop);
		a.bind(foldEnd);
		a.aload(ACC);
		a.areturn();
		a.bind(notArith);

		// ---- generic named application ----
		// Lisp-2: the operator resolves in the function namespace only. Variable
		// bindings (lexical or global) never shadow a function. ARITY doubles as the
		// one-shot case-flip guard until branch (b) assigns it on a registry hit: an
		// unknown operator retries once with the case-flipped spelling (compiled
		// definitions are upcased, runtime-read references case-preserved, and vice
		// versa) after the carcdr check falls through.
		ConstantPool.MethodrefConstant applyToLowerCase = stringCaseRef("toLowerCase");
		ConstantPool.MethodrefConstant applyToUpperCase = stringCaseRef("toUpperCase");
		a.iconst(0);
		a.istore(ARITY);
		int genericApply = a.label();
		a.bind(genericApply);
		// (a) operator defined at runtime via defun (the _fenv function namespace)
		a.aload(OP);
		a.getstatic(this.k.fenvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		int notFenv = a.label();
		a.aload(TMP);
		a.branch(Opcode.IFNULL, notFenv);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.astore(FN);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.bind(notFenv);
		// (b) registered function: evaluate exactly its arity, then apply
		a.aload(OP);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		int notReg = a.label();
		a.aload(TMP);
		a.branch(Opcode.IFNULL, notReg);
		a.iconst(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.aastore();
		a.astore(FN);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.checkcast(this.k.integerClass());
		a.invokevirtual(this.k.integerValue());
		a.istore(ARITY);
		// A negative arity marks a variadic function: evaluate every argument form
		// (the _apply dispatch links the surplus into the rest list); a non-negative
		// arity evaluates exactly that many, padding missing arguments with nil.
		int fixedArity = a.label();
		int applyCall = a.label();
		a.iload(ARITY);
		a.branch(Opcode.IFGE, fixedArity);
		buildArgList(a, REST, ENV, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.branch(Opcode.GOTO, applyCall);
		a.bind(fixedArity);
		buildNArgs(a, REST, ENV, ARITY, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.bind(applyCall);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.bind(notReg);
		// (c) car/cdr composition such as cadr -- BEFORE the case-flip retry, so the
		// composition sees the original spelling (a returning match ends the eval).
		carCdrComposition(a, OP, REST, ENV, ACC, IDX, CH, LEN, VALID);
		// One case-flip retry of (a)+(b), guarded by ARITY's sign (branch (b) only
		// assigns it on a registry hit, which returns; a second pass runs the
		// composition again with the flipped spelling, harmlessly).
		int noRetry = a.label();
		int applyFlipped = a.label();
		a.iload(ARITY);
		a.branch(Opcode.IFLT, noRetry);
		a.iconst(-1);
		a.istore(ARITY);
		a.aload(OP);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(applyToLowerCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(OP);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFEQ, applyFlipped);
		a.aload(OP);
		a.checkcast(this.k.stringClass());
		a.invokevirtual(applyToUpperCase);
		a.astore(TMP);
		a.aload(TMP);
		a.aload(OP);
		a.invokevirtual(this.k.objectEquals());
		a.branch(Opcode.IFNE, noRetry);
		a.bind(applyFlipped);
		a.aload(TMP);
		a.astore(OP);
		a.branch(Opcode.GOTO, genericApply);
		a.bind(noRetry);
		// (d) unknown operator
		a.aconstNull();
		a.areturn();
		return a.finish();
	}

	/**
	 * Emits an {@code _eval} fixed car/cdr accessor (e.g. {@code second}): evaluates the
	 * single argument, applies {@code cdrCount} cdrs then a final car, and returns.
	 */
	private void fixedAccessorEval(Asm a, int opSlot, String name, int restSlot, int envSlot, int accSlot,
			int cdrCount) {
		int next = special(a, opSlot, name);
		evalCar(a, restSlot, envSlot);
		a.astore(accSlot);
		for (int i = 0; i < cdrCount; i++) {
			cdr(a, accSlot);
			a.astore(accSlot);
		}
		car(a, accSlot);
		a.areturn();
		a.bind(next);
	}

	/**
	 * Emits {@code _eval} handling for {@code car}/{@code cdr} composition operators
	 * ({@code c[ad]+r}, e.g. {@code cadr}). When the operator name matches, evaluates the
	 * single argument and applies the car/cdr operations from right to left, then
	 * returns; otherwise falls through.
	 */
	private void carCdrComposition(Asm a, int opSlot, int restSlot, int envSlot, int accSlot, int idxSlot, int chSlot,
			int lenSlot, int validSlot) {
		aloadStr(a, opSlot);
		a.invokevirtual(this.k.stringLength());
		a.istore(lenSlot);
		int noMatch = a.label();
		a.iload(lenSlot);
		a.iconst(3);
		a.branch(Opcode.IF_ICMPLT, noMatch);
		aloadStr(a, opSlot);
		a.iconst(0);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('C');
		a.branch(Opcode.IF_ICMPNE, noMatch);
		aloadStr(a, opSlot);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('R');
		a.branch(Opcode.IF_ICMPNE, noMatch);
		// scan middle bytes: valid = all in {'a','d'}
		a.iconst(1);
		a.istore(validSlot);
		a.iconst(1);
		a.istore(idxSlot);
		int sloop = a.label();
		int send = a.label();
		a.bind(sloop);
		a.iload(idxSlot);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPGE, send);
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.istore(chSlot);
		int okch = a.label();
		a.iload(chSlot);
		a.iconst('A');
		a.branch(Opcode.IF_ICMPEQ, okch);
		a.iload(chSlot);
		a.iconst('D');
		a.branch(Opcode.IF_ICMPEQ, okch);
		a.iconst(0);
		a.istore(validSlot);
		a.branch(Opcode.GOTO, send);
		a.bind(okch);
		a.iinc(idxSlot, 1);
		a.branch(Opcode.GOTO, sloop);
		a.bind(send);
		int notValid = a.label();
		a.iload(validSlot);
		a.branch(Opcode.IFEQ, notValid);
		evalCar(a, restSlot, envSlot);
		a.astore(accSlot);
		a.iload(lenSlot);
		a.iconst(2);
		a.op(Opcode.ISUB);
		a.istore(idxSlot);
		int iloop = a.label();
		int iend = a.label();
		a.bind(iloop);
		a.iload(idxSlot);
		a.iconst(1);
		a.branch(Opcode.IF_ICMPLT, iend);
		int isCdr = a.label();
		int afterc = a.label();
		aloadStr(a, opSlot);
		a.iload(idxSlot);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('A');
		a.branch(Opcode.IF_ICMPNE, isCdr);
		car(a, accSlot);
		a.astore(accSlot);
		a.branch(Opcode.GOTO, afterc);
		a.bind(isCdr);
		cdr(a, accSlot);
		a.astore(accSlot);
		a.bind(afterc);
		a.iinc(idxSlot, -1);
		a.branch(Opcode.GOTO, iloop);
		a.bind(iend);
		a.aload(accSlot);
		a.areturn();
		a.bind(notValid);
		a.bind(noMatch);
	}

}
