package am.ik.rontolisp.eval;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispStructLiteral;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.CompileWarnings;
import am.ik.rontolisp.compiler.SplicedComponentGroups;
import am.ik.rontolisp.reader.ReadSyntax;

/**
 * The compile path's READ-TIME image ({@code .kb/readtables.md}): the macro-time
 * evaluator a compile reads its source with. The compile path reads a whole program
 * before {@link UserMacroExpander} evaluates anything, so a readtable an
 * {@code eval-when} -- an {@code in-readtable}, an {@code enable-...-syntax} -- installs
 * at compile time would never reach the read of the forms after it. This image runs each
 * form through a shallow {@link UserMacroExpander.Session} as the read produces it, and
 * the readtable its evaluator then holds is what the next form reads with; a reader macro
 * function runs in it, and what the function answers must be a value a compiled program
 * can carry as a literal.
 *
 * <p>
 * The image is thrown away once the program is read: the pass pipeline between the read
 * and the macro-time pass ({@code CompileFrontend.expand}) rewrites and splices the
 * program, and {@link UserMacroExpander} processes the result from scratch. A form the
 * image cannot process -- one that needs what a later pass splices -- is skipped: that
 * pass reports it. A read only opens an image once a file it reads can change the syntax
 * at all ({@link #changesSyntax}); until then every form reads with the standard syntax,
 * and a program that never opens one pays nothing for it.
 */
public final class ReadTimeImage {

	// What changes how a source reads: a macro character's function, a dispatch
	// character, another character's syntax, the case. Assigning *readtable* changes
	// nothing a mutation through one of these did not build first.
	private static final Pattern CHANGES_SYNTAX = Pattern.compile(
			"(?i)set-macro-character|make-dispatch-macro-character|set-syntax-from-char|\\(\\s*setf\\s+\\(\\s*readtable-case\\b");

	// A set-dispatch-macro-character, with its sub-character when it is spelled
	// #\# #\X.
	private static final Pattern SET_DISPATCH = Pattern
		.compile("(?i)set-dispatch-macro-character(?:\\s+#\\\\#\\s+#\\\\([^\\s()]+))?");

	private final UserMacroExpander.Session session = new UserMacroExpander.Session(true);

	/** How many forms of the program being read the session has processed. */
	private int fed;

	/**
	 * Whether a Common Lisp source can change how source reads: it sets a macro or
	 * dispatch character, another character's syntax, or the case. Installing
	 * {@code #\# #\@} or {@code #\# #\L} does not count -- this reader keeps reading
	 * those two natively whatever function a readtable holds (ironclad's and iterate's).
	 * @param source the source text
	 * @return whether reading it calls for an image
	 */
	public static boolean changesSyntax(String source) {
		if (CHANGES_SYNTAX.matcher(source).find()) {
			return true;
		}
		Matcher dispatch = SET_DISPATCH.matcher(source);
		while (dispatch.find()) {
			String sub = dispatch.group(1);
			if (sub == null || !(sub.equals("@") || sub.equalsIgnoreCase("L"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Brings the image up to the forms read so far and answers the syntax the next one
	 * reads with.
	 * @param program the program read so far, in order: the image processes the forms it
	 * has not seen yet
	 * @return the syntax, or {@code null} for the standard one
	 */
	public @Nullable ReadSyntax syntax(List<LispVal> program) {
		while (this.fed < program.size()) {
			LispVal form = program.get(this.fed++);
			// The component brackets are stripped before the macro-time pass sees the
			// program (AsdfRuntimeLibrary), unless it defines a perform method.
			if (SplicedComponentGroups.isComponentMarker(form)) {
				continue;
			}
			try {
				CompileWarnings.discarding(() -> this.session.acceptTopLevel(form));
			}
			catch (RuntimeException ex) {
				// The macro-time pass meets the same form after the passes before it
				// have run, and reports what is really wrong with it.
			}
		}
		return this.session.macroEval.currentReadSyntax(ReadTimeImage::compilable);
	}

	// A reader macro function's value becomes part of a form the backends compile, so it
	// must have a literal form (.kb/readtables.md): a closure, a hash table, a stream do
	// not.
	private static ReadSyntax.Result compilable(ReadSyntax.Result result) {
		LispVal value = result.value();
		LispVal refused = value == null ? null : firstUncompilable(value);
		if (refused != null) {
			throw new ReadSyntax.Refusal("a reader macro function answered " + refused.print()
					+ ", which a compiled program cannot carry as a literal");
		}
		return result;
	}

	private static @Nullable LispVal firstUncompilable(LispVal datum) {
		Set<LispVal> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<LispVal> pending = new ArrayDeque<>();
		pending.push(datum);
		while (!pending.isEmpty()) {
			LispVal value = pending.pop();
			switch (value) {
				case LispCons cons -> {
					if (seen.add(cons)) {
						pending.push(cons.cdr());
						pending.push(cons.car());
					}
				}
				case LispArray array -> {
					if (seen.add(array)) {
						for (LispVal element : array.data()) {
							pending.push(element);
						}
					}
				}
				case LispInstance instance -> {
					if (instance.hasTag(LispLayout.READTABLE_TAG)) {
						return instance;
					}
					if (seen.add(instance)) {
						for (int i = 0; i < instance.slotCount(); i++) {
							pending.push(instance.slot(i));
						}
					}
				}
				case LispInteger ignored -> {
				}
				case LispBigInteger ignored -> {
				}
				case LispRatio ignored -> {
				}
				case LispDouble ignored -> {
				}
				case LispComplex ignored -> {
				}
				case LispSymbol ignored -> {
				}
				case LispString ignored -> {
				}
				case LispChar ignored -> {
				}
				case LispNil ignored -> {
				}
				case LispTrue ignored -> {
				}
				case LispFloatArray ignored -> {
				}
				case LispIntVector ignored -> {
				}
				case LispStructLiteral ignored -> {
				}
				default -> {
					return value;
				}
			}
		}
		return null;
	}

}
