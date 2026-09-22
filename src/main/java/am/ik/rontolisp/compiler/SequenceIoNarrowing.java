package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.ArgumentShapes.Shape;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * Narrows {@code read-sequence} / {@code write-sequence} call sites whose sequence is
 * proven not to be a string to the byte arm, so the dead character arm (and with it the
 * {@code read-char} / {@code write-string}-over-slice runtime on WASM) leaves the
 * artifact (.todo/338).
 *
 * <p>
 * The proof is lexical, in the shape of {@link LetBoundDesignators} but for element
 * types: a {@code let} / {@code let*} binding whose init has
 * {@link ArgumentShapes.Shape#VECTOR} shape (a rank-1 non-string, non-bit array -- a
 * numeric-typed or untyped {@code make-array}, a {@code (vector ...)}, a {@code subseq} /
 * {@code copy-seq} preserving one) carries that fact into the body, where a
 * {@code read-sequence} / {@code write-sequence} call over the variable expands through
 * the byte-only lowering. A direct certainly-non-string sequence form narrows with no
 * binding at all.
 *
 * <p>
 * The fact dies on anything that can change what the variable holds: a rebinding
 * ({@code setq} / {@code psetq} target, a {@code setf} place that is the variable itself,
 * another binding of the same name), a capture (any occurrence inside a nested
 * {@code lambda} / {@code defun} / local-function body, which a call between the binding
 * and the site could observe), quoted data, or dynamic scope (a special variable, which
 * any callee can rebind -- collected from {@code defvar} / {@code defparameter} and
 * {@code (declare (special ...))}). Anything undecided keeps the shared runtime-tested
 * expansion exactly as it was, so the interpreter (which never runs this pass) and the
 * compilers agree on every program.
 *
 * <p>
 * This runs backend-locally after the gate scans (the JVM and wasm-GC compile paths, next
 * to {@link DeadTypeBranchPruner}), never in {@code CompileFrontend}: the narrowed
 * expansion still attempts {@code %read-sequence-packed} / {@code %write-sequence-packed}
 * first, and the gates that emit that runtime key on the unexpanded operator spelling.
 * The character bulk arm ({@code %read-sequence-chars},
 * {@code .kb/character-sequence-io.md}) is what the narrowed expansion DROPS: no
 * character buffer can reach a proven site.
 */
public final class SequenceIoNarrowing {

	private SequenceIoNarrowing() {
	}

	/** The operator heads this pass narrows, with their byte-only expander. */
	private static final Set<String> SEQUENCE_IO_HEADS = Set.of(LispNames.READ_SEQUENCE, LispNames.WRITE_SEQUENCE,
			LispNames.READ_SEQUENCE_RAW_INTERNAL, LispNames.WRITE_SEQUENCE_RAW_INTERNAL);

	/** Binding heads whose binding list may shadow the tracked variable. */
	private static final Set<String> PAIR_BINDING_HEADS = Set.of(LispNames.LET, LispNames.LET_STAR);

	/** Heads that introduce a scope a tracked occurrence must not be inside. */
	private static final Set<String> CAPTURE_HEADS = Set.of(LispNames.LAMBDA, LispNames.DEFUN, LispNames.DEFMACRO,
			LispNames.ASYNC_DEFUN);

	/** Assignment operators whose target positions rebind the tracked variable. */
	private static final Set<String> REBINDING_HEADS = Set.of(LispNames.SETQ, LispNames.PSETQ, LispNames.SETF,
			LispNames.PUSH, LispNames.POP, LispNames.INCF, LispNames.DECF, LispNames.ROTATEF, LispNames.SHIFTF,
			LispNames.MULTIPLE_VALUE_SETQ);

	/** Element stores, which mutate a buffer without rebinding it. */
	private static final Set<String> ELEMENT_STORE_HEADS = Set.of(LispNames.AREF, LispNames.ROW_MAJOR_AREF,
			LispNames.ELT, LispNames.NTH);

	/**
	 * Rewrites the program with every provably byte-only sequence-I/O site narrowed.
	 * @param program the top-level forms
	 * @param characterStreams whether a string stream can reach a site (the backend
	 * builds stream values and {@code %character-stream-p} is spliced): a string stream
	 * moves characters into a buffer of element type {@code t}, so then only a buffer
	 * that cannot hold a character -- a numeric-typed one -- is proven byte-only
	 * @param wide whether the program opens a WIDE element stream ({@code %wide-width} is
	 * spliced): a narrowed site then keeps its packed arm guarded, as the backends' own
	 * read-sequence / write-sequence cases do, because the packed primitive moves raw
	 * octets (.kb/read-load-streams.md, "Element types wider and narrower than one
	 * octet")
	 * @return the rewritten program, or {@code program} itself when nothing narrowed
	 */
	public static List<LispVal> narrow(List<LispVal> program, boolean characterStreams, boolean wide) {
		// Both facts come from the backend's function table: the top-level forms this
		// pass sees exclude every defun, the spliced ones included.
		Set<String> specials = new HashSet<>();
		for (LispVal form : program) {
			collectSpecials(form, specials);
		}
		Narrower narrower = new Narrower(specials, wide, characterStreams);
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = narrower.form(form, Map.of());
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	/** Globally special names: {@code defvar} / {@code defparameter} anywhere. */
	private static void collectSpecials(LispVal form, Set<String> out) {
		if (form instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head
					&& (LispNames.DEFVAR.equals(head.name()) || LispNames.DEFPARAMETER.equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				out.add(name.name());
			}
			if (!(cons.car() instanceof LispSymbol quoteHead && LispNames.QUOTE.equals(quoteHead.name()))) {
				collectSpecials(cons.car(), out);
				LispVal rest = cons.cdr();
				while (rest instanceof LispCons cell) {
					collectSpecials(cell.car(), out);
					rest = cell.cdr();
				}
			}
		}
	}

	private static final class Narrower {

		private final Set<String> specials;

		private final boolean wide;

		private final boolean characterStreams;

		private Narrower(Set<String> specials, boolean wide, boolean characterStreams) {
			this.specials = specials;
			this.wide = wide;
			this.characterStreams = characterStreams;
		}

		/**
		 * Whether the form is a proven byte buffer. Without character streams that is any
		 * rank-1 non-string vector ({@link Shape#VECTOR}); with them a buffer of element
		 * type {@code t} may receive characters, so only a NUMERIC-typed one -- a literal
		 * numeric {@code :element-type}, directly, through a {@code subseq} /
		 * {@code copy-seq}, or through a binding this walk recorded (which, in this mode,
		 * records numeric buffers only) -- qualifies.
		 */
		private boolean byteBuffer(LispVal form, Map<String, Shape> env) {
			if (ArgumentShapes.of(form, env, Map.of()) != Shape.VECTOR) {
				return false;
			}
			return !this.characterStreams || numericBuffer(form, env);
		}

		private static boolean numericBuffer(LispVal form, Map<String, Shape> env) {
			if (form instanceof LispSymbol sym) {
				return env.containsKey(sym.name());
			}
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
				return false;
			}
			if ((LispNames.SUBSEQ.equals(head.name()) || LispNames.COPY_SEQ.equals(head.name()))
					&& cons.cdr() instanceof LispCons seqCell) {
				return numericBuffer(seqCell.car(), env);
			}
			if (!LispNames.MAKE_ARRAY.equals(head.name())) {
				return false;
			}
			LispVal rest = cons.cdr() instanceof LispCons dims ? dims.cdr() : LispNil.INSTANCE;
			while (rest instanceof LispCons keyCell && keyCell.cdr() instanceof LispCons valueCell) {
				if (keyCell.car() instanceof LispSymbol key && ":ELEMENT-TYPE".equals(key.name())) {
					return numericElementType(valueCell.car());
				}
				rest = valueCell.cdr();
			}
			return false;
		}

		/** A quoted numeric type: {@code '(unsigned-byte 8)}, {@code 'single-float}. */
		private static boolean numericElementType(LispVal spec) {
			if (!(spec instanceof LispCons quoted && quoted.car() instanceof LispSymbol q
					&& LispNames.QUOTE.equals(q.name()) && quoted.cdr() instanceof LispCons cell)) {
				return false;
			}
			LispVal datum = cell.car();
			LispVal head = datum instanceof LispCons compound ? compound.car() : datum;
			return head instanceof LispSymbol sym && NUMERIC_ELEMENT_TYPES.contains(sym.name());
		}

		private static final Set<String> NUMERIC_ELEMENT_TYPES = Set.of("UNSIGNED-BYTE", "SIGNED-BYTE", "INTEGER",
				"FIXNUM", "FLOAT", "SINGLE-FLOAT", "DOUBLE-FLOAT", "SHORT-FLOAT", "LONG-FLOAT", "REAL", "NUMBER");

		private LispVal form(LispVal val, Map<String, Shape> env) {
			if (!(val instanceof LispCons cons) || !cons.isProperList()) {
				return val;
			}
			List<LispVal> parts = cons.toList();
			if (!(parts.get(0) instanceof LispSymbol head)) {
				return LispCons.rebuilt(cons, this.form(cons.car(), env), this.forms(cons.cdr(), env));
			}
			String name = head.name();
			if (LispNames.QUOTE.equals(name)) {
				return cons;
			}
			if (SEQUENCE_IO_HEADS.contains(name)) {
				LispVal narrowed = this.narrowSite(cons, head, env);
				if (narrowed != cons) {
					return narrowed;
				}
				return LispCons.rebuilt(cons, head, this.forms(cons.cdr(), env));
			}
			if (PAIR_BINDING_HEADS.contains(name)) {
				return this.bindingForm(cons, head, env);
			}
			if (CAPTURE_HEADS.contains(name) || LispNames.FUNCTION.equals(name)) {
				// A nested function value closes over the extent: whatever it names is
				// read later, so no lexical fact travels inside.
				return this.forms(cons.cdr(), Map.of()) == cons.cdr() ? cons
						: LispCons.rebuilt(cons, head, this.forms(cons.cdr(), Map.of()));
			}
			if (LispNames.FLET.equals(name) || LispNames.LABELS.equals(name)) {
				return this.localFunctions(cons, head, env);
			}
			return LispCons.rebuilt(cons, head, this.forms(cons.cdr(), env));
		}

		private LispVal forms(LispVal tail, Map<String, Shape> env) {
			if (!(tail instanceof LispCons cons)) {
				return tail;
			}
			return LispCons.rebuilt(cons, this.form(cons.car(), env), this.forms(cons.cdr(), env));
		}

		/** Narrows one sequence-I/O call when its sequence proves non-string. */
		private LispVal narrowSite(LispCons cons, LispSymbol head, Map<String, Shape> env) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3) {
				return cons;
			}
			if (!this.byteBuffer(parts.get(1), env)) {
				return cons;
			}
			boolean read = LispNames.READ_SEQUENCE.equals(head.name())
					|| LispNames.READ_SEQUENCE_RAW_INTERNAL.equals(head.name());
			LispVal expanded = read ? LispMacroExpander.expandReadSequence(cons, true, false)
					: LispMacroExpander.expandWriteSequence(cons, true, false);
			if (this.wide) {
				expanded = LispMacroExpander.guardPackedSequenceForWideStreams(expanded);
			}
			return SourceProvenance.inherit(cons, expanded);
		}

		/** {@code (let ((var init) ...) body...)} and {@code let*}, threading shapes. */
		private LispVal bindingForm(LispCons cons, LispSymbol head, Map<String, Shape> env) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3 || !(parts.get(1) instanceof LispCons bindings) || !bindings.isProperList()) {
				return LispCons.rebuilt(cons, head, this.forms(cons.cdr(), env));
			}
			boolean sequential = LispNames.LET_STAR.equals(head.name());
			Map<String, Shape> inner = new HashMap<>(env);
			Map<String, Shape> evalEnv = sequential ? inner : env;
			List<LispVal> scope = new ArrayList<>();
			for (LispVal binding : bindings.toList()) {
				if (binding instanceof LispCons pair && pair.car() instanceof LispSymbol var
						&& pair.cdr() instanceof LispCons initCell) {
					LispVal init = this.form(initCell.car(), evalEnv);
					boolean byteBuffer = this.byteBuffer(initCell.car(), evalEnv);
					if (byteBuffer && !this.specials.contains(var.name())
							&& this.stableIn(var.name(), initCell.cdr(), parts.subList(2, parts.size()))) {
						inner.put(var.name(), Shape.VECTOR);
					}
					else {
						inner.remove(var.name());
					}
					scope.add(LispCons.rebuilt(pair, pair.car(),
							LispCons.rebuilt(initCell, init, this.forms(initCell.cdr(), evalEnv))));
					if (sequential) {
						evalEnv = inner;
					}
				}
				else {
					if (binding instanceof LispSymbol bare) {
						inner.remove(bare.name());
					}
					scope.add(binding);
				}
			}
			List<LispVal> rewritten = new ArrayList<>();
			rewritten.add(head);
			rewritten.add(LispCons.rebuiltList(bindings, scope));
			for (LispVal bodyForm : parts.subList(2, parts.size())) {
				rewritten.add(this.form(bodyForm, inner));
			}
			return SourceProvenance.inherit(cons, (LispCons) LispCons.rebuiltList(cons, rewritten));
		}

		/**
		 * Whether the variable still holds its init's shape over the extent: no
		 * rebinding, no capture, no shadowing, no dynamic scope.
		 */
		private boolean stableIn(String name, LispVal afterInit, List<LispVal> body) {
			if (this.specials.contains(name)) {
				return false;
			}
			if (this.invalidated(afterInit, name, false)) {
				return false;
			}
			for (LispVal form : body) {
				if (this.invalidated(form, name, false)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Whether the extent can change what {@code name} holds or observe it from a
		 * scope the fact does not cover.
		 * @param inCapture whether already inside a nested function value
		 */
		private boolean invalidated(LispVal val, String name, boolean inCapture) {
			if (val instanceof LispSymbol sym) {
				// A bare read is harmless; a captured one may run anywhere.
				return inCapture && name.equals(sym.name());
			}
			if (!(val instanceof LispCons cons)) {
				return false;
			}
			if (!cons.isProperList()) {
				return this.invalidated(cons.car(), name, inCapture) || this.invalidated(cons.cdr(), name, inCapture);
			}
			List<LispVal> parts = cons.toList();
			if (!(parts.get(0) instanceof LispSymbol head)) {
				return this.anyInvalidated(parts, name, inCapture);
			}
			String op = head.name();
			if (LispNames.QUOTE.equals(op)) {
				// Data the program could hand anywhere, including a writer.
				return this.occurs(parts.get(1), name);
			}
			if (LispNames.FUNCTION.equals(op)) {
				return parts.size() == 2 && this.occurs(parts.get(1), name);
			}
			if (CAPTURE_HEADS.contains(op)) {
				return this.anyOccurs(parts, name);
			}
			if (LispNames.FLET.equals(op) || LispNames.LABELS.equals(op)) {
				return this.localInvalidated(parts, name);
			}
			if (PAIR_BINDING_HEADS.contains(op) || LispNames.DO.equals(op) || LispNames.DO_STAR.equals(op)) {
				return this.bindingInvalidated(parts, name, inCapture);
			}
			if (LispNames.DOTIMES.equals(op) || LispNames.DOLIST.equals(op)) {
				return this.countedInvalidated(parts, name, inCapture);
			}
			if (LispNames.MULTIPLE_VALUE_BIND.equals(op) || LispNames.DESTRUCTURING_BIND.equals(op)) {
				return this.anyOccurs(parts, name);
			}
			if (LispNames.DECLARE.equals(op)) {
				return this.declaresSpecial(parts, name);
			}
			if (LispNames.PROGV.equals(op)) {
				return this.anyOccurs(parts, name);
			}
			if (REBINDING_HEADS.contains(op)) {
				return this.rebindingInvalidated(parts, name, inCapture);
			}
			if (LispNames.SYMBOL_MACROLET.equals(op) || LispNames.MACROLET.equals(op)) {
				return this.anyOccurs(parts, name);
			}
			return this.anyInvalidated(parts, name, inCapture);
		}

		private boolean anyInvalidated(List<LispVal> parts, String name, boolean inCapture) {
			for (LispVal part : parts) {
				if (this.invalidated(part, name, inCapture)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Every occurrence of the symbol, shape-blind like the certified count it guards.
		 */
		private boolean occurs(LispVal val, String name) {
			if (val instanceof LispSymbol sym) {
				return name.equals(sym.name());
			}
			if (!(val instanceof LispCons cons)) {
				return false;
			}
			return this.occurs(cons.car(), name) || this.occurs(cons.cdr(), name);
		}

		private boolean anyOccurs(List<LispVal> parts, String name) {
			for (LispVal part : parts) {
				if (this.occurs(part, name)) {
					return true;
				}
			}
			return false;
		}

		/** A nested binding of the same name shadows the fact; its extent is walked. */
		private boolean bindingInvalidated(List<LispVal> parts, String name, boolean inCapture) {
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons bindings)) {
				return this.anyInvalidated(parts, name, inCapture);
			}
			for (LispVal binding : bindings.toList()) {
				if (binding instanceof LispSymbol bare && name.equals(bare.name())) {
					return true;
				}
				if (binding instanceof LispCons pair) {
					if (pair.car() instanceof LispSymbol var && name.equals(var.name())) {
						return true;
					}
					if (this.invalidated(pair, name, inCapture)) {
						return true;
					}
				}
			}
			for (int k = 2; k < parts.size(); k++) {
				if (this.invalidated(parts.get(k), name, inCapture)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * {@code (dotimes (v n) ...)} / {@code (dolist (v l) ...)}: the counter shadows.
		 */
		private boolean countedInvalidated(List<LispVal> parts, String name, boolean inCapture) {
			if (parts.size() >= 2 && parts.get(1) instanceof LispCons counter && counter.car() instanceof LispSymbol var
					&& name.equals(var.name())) {
				return true;
			}
			return this.anyInvalidated(parts, name, inCapture);
		}

		/** {@code flet} / {@code labels}: the local bodies capture, the body does not. */
		private boolean localInvalidated(List<LispVal> parts, String name) {
			if (parts.size() >= 2 && parts.get(1) instanceof LispCons locals) {
				for (LispVal local : locals.toList()) {
					if (local instanceof LispCons fn && fn.cdr() instanceof LispCons afterName) {
						// Params shadow; the local body captures.
						if (this.occurs(afterName.car(), name)) {
							return true;
						}
						LispVal rest = afterName.cdr();
						while (rest instanceof LispCons cell) {
							if (this.invalidated(cell.car(), name, true)) {
								return true;
							}
							rest = cell.cdr();
						}
					}
					else if (this.invalidated(local, name, false)) {
						return true;
					}
				}
			}
			for (int k = 2; k < parts.size(); k++) {
				if (this.invalidated(parts.get(k), name, false)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * An assignment form: a target that IS the variable rebinds it; an element store
		 * through it does not, but its index and value forms are still walked.
		 */
		private boolean rebindingInvalidated(List<LispVal> parts, String name, boolean inCapture) {
			for (int k = 1; k < parts.size(); k++) {
				LispVal part = parts.get(k);
				if (part instanceof LispSymbol sym && name.equals(sym.name())) {
					// A setq/psetq target (odd positions name one, but a value in an
					// even slot cannot be the variable without being read -- and a
					// read there is harmless, so declining on any of them over-counts
					// only toward safety).
					return true;
				}
				if (part instanceof LispCons place && !place.isProperList()) {
					if (this.invalidated(part, name, inCapture)) {
						return true;
					}
				}
				else if (part instanceof LispCons place && place.car() instanceof LispSymbol placeHead
						&& ELEMENT_STORE_HEADS.contains(placeHead.name())) {
					List<LispVal> placeParts = place.toList();
					for (int j = 2; j < placeParts.size(); j++) {
						if (this.invalidated(placeParts.get(j), name, inCapture)) {
							return true;
						}
					}
				}
				else if (this.invalidated(part, name, inCapture)) {
					return true;
				}
			}
			return false;
		}

		/** Whether a {@code declare} form proclaims the variable special. */
		private boolean declaresSpecial(List<LispVal> parts, String name) {
			for (int k = 1; k < parts.size(); k++) {
				LispVal spec = parts.get(k);
				if (spec instanceof LispCons decl && decl.car() instanceof LispSymbol kind
						&& LispNames.SPECIAL.equals(kind.name()) && this.occurs(decl.cdr(), name)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * {@code flet} / {@code labels} in the main walk: locals capture, body keeps env.
		 */
		private LispVal localFunctions(LispCons cons, LispSymbol head, Map<String, Shape> env) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons)) {
				return LispCons.rebuilt(cons, head, this.forms(cons.cdr(), env));
			}
			LispVal locals = this.forms(parts.get(1), Map.of());
			List<LispVal> rewritten = new ArrayList<>();
			rewritten.add(head);
			rewritten.add(locals);
			for (int k = 2; k < parts.size(); k++) {
				rewritten.add(this.form(parts.get(k), env));
			}
			return SourceProvenance.inherit(cons, (LispCons) LispCons.rebuiltList(cons, rewritten));
		}

	}

}
