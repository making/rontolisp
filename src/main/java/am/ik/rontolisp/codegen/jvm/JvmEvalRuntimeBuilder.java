package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

		private final MethodrefConstant[] invoke;

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
			this.invoke = Objects.requireNonNull(b.invoke);
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

		MethodrefConstant[] invoke() {
			return this.invoke;
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

			private MethodrefConstant @Nullable [] invoke;

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

			Builder invoke(MethodrefConstant[] invoke) {
				this.invoke = invoke;
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

	/** Builds the {@code _lookup} method body. */
	static List<Integer> buildLookup(EvalConstants k) {
		return new JvmEvalRuntimeBuilder(k).lookupBody();
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

	private List<Integer> lookupBody() {
		Asm a = new Asm();
		for (Map.Entry<String, JvmLispCompiler.FunctionInfo> e : this.k.functions().entrySet()) {
			JvmLispCompiler.FunctionInfo fi = e.getValue();
			if (fi.paramCount() > MAX_CALLABLE_ARITY) {
				continue;
			}
			int next = a.label();
			a.aload(0);
			ldcStr(a, e.getKey());
			a.invokevirtual(this.k.objectEquals());
			a.branch(Opcode.IFEQ, next);
			// return new Object[]{ Integer.valueOf(funcId), Integer.valueOf(arity) }
			a.iconst(2);
			a.anewarray(this.k.objectClass());
			a.dup();
			a.iconst(0);
			a.iconst(fi.funcId());
			a.invokestatic(this.k.integerValueOf());
			a.aastore();
			a.dup();
			a.iconst(1);
			a.iconst(fi.paramCount());
			a.invokestatic(this.k.integerValueOf());
			a.aastore();
			a.areturn();
			a.bind(next);
		}
		a.aconstNull();
		a.areturn();
		return a.finish();
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
		for (int n = 0; n <= MAX_CALLABLE_ARITY; n++) {
			int nextN = a.label();
			a.iload(LEN);
			a.iconst(n);
			a.branch(Opcode.IF_ICMPNE, nextN);
			a.aload(ARGLIST);
			a.astore(ARGCUR);
			for (int j = 0; j < n; j++) {
				car(a, ARGCUR);
				a.astore(ARG0 + j);
				cdr(a, ARGCUR);
				a.astore(ARGCUR);
			}
			a.aload(FN);
			for (int j = 0; j < n; j++) {
				a.aload(ARG0 + j);
			}
			a.invokestatic(this.k.invoke()[n]);
			a.areturn();
			a.bind(nextN);
		}
		// arity out of range -> nil
		a.aconstNull();
		a.areturn();

		a.bind(notArr);
		a.aconstNull();
		a.areturn();
		return a.finish();
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
		a.iconst('c');
		a.branch(Opcode.IF_ICMPNE, noMatch);
		aloadStr(a, opSlot);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('r');
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
		a.iconst('a');
		a.branch(Opcode.IF_ICMPEQ, okch);
		a.iload(chSlot);
		a.iconst('d');
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
		a.iconst('a');
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
		a.iconst('d');
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
		// global lookup
		int reg = a.label();
		a.aload(VAL);
		a.getstatic(this.k.genvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, reg);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(1);
		a.aaload();
		a.areturn();
		a.bind(reg);
		// registered function as a first-class value
		int self = a.label();
		a.aload(VAL);
		a.invokestatic(this.k.lookupRef());
		a.astore(TMP);
		a.aload(TMP);
		a.branch(Opcode.IFNULL, self);
		a.iconst(1);
		a.anewarray(this.k.objectClass());
		a.dup();
		a.iconst(0);
		a.aload(TMP);
		a.checkcast(this.k.objectArrayClass());
		a.iconst(0);
		a.aaload();
		a.aastore();
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
		ldcStr(a, "t");
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

		// ---- map: (map fn list) ----
		n = special(a, OP, LispNames.MAP);
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

		// ---- reduce: (reduce fn list) or (reduce fn init list) ----
		n = special(a, OP, LispNames.REDUCE);
		evalCar(a, REST, ENV);
		a.astore(FN);
		cdr(a, REST);
		a.astore(REST);
		cdr(a, REST);
		a.astore(TMP); // cdr(rest): null for 2-arg, (list) for 3-arg
		int threeArg = a.label();
		int afterInit = a.label();
		a.aload(TMP);
		a.branch(Opcode.IFNONNULL, threeArg);
		// 2-arg: list = eval(car rest); acc = car(list); list = cdr(list)
		evalCar(a, REST, ENV);
		a.astore(ELEM);
		car(a, ELEM);
		a.astore(ACC);
		cdr(a, ELEM);
		a.astore(ELEM);
		a.branch(Opcode.GOTO, afterInit);
		a.bind(threeArg);
		// 3-arg: acc = eval(car rest); list = eval(car (cdr rest))
		evalCar(a, REST, ENV);
		a.astore(ACC);
		evalCar(a, TMP, ENV);
		a.astore(ELEM);
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
		// (a) operator bound in the lexical environment
		a.aload(OP);
		a.aload(ENV);
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		int notLexical = a.label();
		a.aload(TMP);
		a.branch(Opcode.IFNULL, notLexical);
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
		a.bind(notLexical);
		// (a2) operator bound in the global environment
		a.aload(OP);
		a.getstatic(this.k.genvField());
		a.invokestatic(this.k.envLookupRef());
		a.astore(TMP);
		int notGlobal = a.label();
		a.aload(TMP);
		a.branch(Opcode.IFNULL, notGlobal);
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
		a.bind(notGlobal);
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
		buildNArgs(a, REST, ENV, ARITY, ARGHEAD, ARGTAIL, NEWCELL, TMP);
		a.aload(FN);
		a.aload(ARGHEAD);
		a.invokestatic(this.k.applyRef());
		a.areturn();
		a.bind(notReg);
		// (c) car/cdr composition such as cadr
		carCdrComposition(a, OP, REST, ENV, ACC, IDX, CH, LEN, VALID);
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
		a.iconst('c');
		a.branch(Opcode.IF_ICMPNE, noMatch);
		aloadStr(a, opSlot);
		a.iload(lenSlot);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(this.k.stringCharAt());
		a.iconst('r');
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
		a.iconst('a');
		a.branch(Opcode.IF_ICMPEQ, okch);
		a.iload(chSlot);
		a.iconst('d');
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
		a.iconst('a');
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
