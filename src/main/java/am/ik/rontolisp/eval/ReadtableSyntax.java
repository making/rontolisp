package am.ik.rontolisp.eval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.ReadSyntax;

/**
 * Reads a readtable VALUE ({@code LispLayout.READTABLE}, built and changed by the
 * prelude's readtable family) into the {@link ReadSyntax} the interpreter's reader reads
 * with ({@code .kb/readtables.md}). An entry is the prelude's list
 * {@code (KIND FN NON-TERMINATING-P SUBS STD)}; only what differs from the standard
 * syntax becomes an entry of the view, so a readtable equal to the standard one -- an
 * empty table, or one named-readtables refilled with the standard functions -- reads with
 * no view at all, which is the reader's own fast path.
 */
final class ReadtableSyntax {

	private ReadtableSyntax() {
	}

	/**
	 * The syntax a readtable reads with.
	 * @param readtable the {@code *readtable*} value
	 * @param invoker calls a reader macro function
	 * @return the syntax, or {@code null} when the readtable reads as the standard one
	 * (or is not a readtable at all)
	 */
	static @Nullable ReadSyntax of(LispVal readtable, ReadSyntax.Invoker invoker) {
		if (!(readtable instanceof LispInstance rt) || !rt.hasTag(LispLayout.READTABLE_TAG)) {
			return null;
		}
		ReadSyntax.Case caseMode = caseOf(rt.slot(0));
		Map<Integer, ReadSyntax.Entry> entries = new HashMap<>();
		if (rt.slot(1) instanceof LispHashTable chars) {
			for (LispHashTable.Entry entry : chars.entries()) {
				if (entry.key() instanceof LispChar c) {
					ReadSyntax.Entry view = entryOf(c.codePoint(), entry.value());
					if (view != null) {
						entries.put(c.codePoint(), view);
					}
				}
			}
		}
		if (entries.isEmpty() && caseMode == ReadSyntax.Case.UPCASE) {
			return null;
		}
		return new ReadSyntax(caseMode, entries, invoker);
	}

	private static ReadSyntax.Case caseOf(LispVal mode) {
		String name = mode instanceof LispSymbol sym ? sym.name() : "";
		return switch (name) {
			case ":DOWNCASE" -> ReadSyntax.Case.DOWNCASE;
			case ":PRESERVE" -> ReadSyntax.Case.PRESERVE;
			case ":INVERT" -> ReadSyntax.Case.INVERT;
			default -> ReadSyntax.Case.UPCASE;
		};
	}

	// One entry (KIND FN NON-TERMINATING-P SUBS STD) as the reader sees it, or null when
	// it reads exactly as the standard syntax does.
	private static ReadSyntax.@Nullable Entry entryOf(int c, LispVal entry) {
		List<LispVal> parts = entry instanceof LispCons cons && cons.isProperList() ? cons.toList() : List.of();
		if (parts.size() < 5 || !(parts.get(0) instanceof LispSymbol kind)) {
			return null;
		}
		switch (kind.name()) {
			case ":WHITESPACE":
				return isStandardWhitespace(c) ? null : new ReadSyntax.Whitespace();
			case ":CONSTITUENT":
			case ":SINGLE-ESCAPE":
			case ":MULTIPLE-ESCAPE":
				// An escape character's syntax under another character is not read
				// (.kb/readtables.md); the character is a constituent there.
				if (kind.name().equals(":SINGLE-ESCAPE") && c == '\\'
						|| kind.name().equals(":MULTIPLE-ESCAPE") && c == '|') {
					return null;
				}
				return isStandardConstituent(c) ? null : new ReadSyntax.Constituent();
			case ":MACRO":
				return macroEntry(c, parts);
			default:
				return null;
		}
	}

	private static ReadSyntax.@Nullable Entry macroEntry(int c, List<LispVal> parts) {
		LispVal function = parts.get(1);
		boolean nonTerminating = !(parts.get(2) instanceof LispNil);
		if (parts.get(3) instanceof LispHashTable subs) {
			if (c != '#') {
				// Any other dispatch character: its dispatching function reads the
				// sub-character and calls what the table holds.
				return new ReadSyntax.Call(function, nonTerminating);
			}
			Map<Integer, ReadSyntax.Sub> view = new HashMap<>();
			boolean allStandard = true;
			for (LispHashTable.Entry sub : subs.entries()) {
				if (!(sub.key() instanceof LispChar subChar) || !(sub.value() instanceof LispCons pair)) {
					continue;
				}
				// #L and #N@ are this reader's own syntax on every backend -- iterate's
				// and
				// ironclad's dispatch functions, which the compile paths cannot call --
				// so
				// the reader keeps reading them natively whatever function the table
				// holds: the interpreter then reads those libraries as the compiled
				// backends do (.kb/readtables.md).
				int standardAs = pair.cdr() instanceof LispChar std ? std.codePoint()
						: isNativeSub(subChar.codePoint()) ? subChar.codePoint() : -1;
				view.put(subChar.codePoint(), new ReadSyntax.Sub(standardAs >= 0 ? null : pair.car(), standardAs));
				allStandard &= standardAs == subChar.codePoint();
			}
			return allStandard ? null : new ReadSyntax.Sharp(view);
		}
		if (parts.get(4) instanceof LispChar standard) {
			// A standard macro function: under its own character it is the standard
			// syntax; under another one that character reads as the standard one does.
			return standard.codePoint() == c && isStandardMacro(c) ? null
					: new ReadSyntax.Standard(standard.codePoint());
		}
		return new ReadSyntax.Call(function, nonTerminating);
	}

	private static boolean isNativeSub(int sub) {
		return sub == 'L' || sub == '@';
	}

	private static boolean isStandardWhitespace(int c) {
		return c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f';
	}

	private static boolean isStandardMacro(int c) {
		return c == '(' || c == ')' || c == '\'' || c == ';' || c == '"' || c == '`' || c == ',' || c == '#';
	}

	private static boolean isStandardConstituent(int c) {
		return !isStandardWhitespace(c) && !isStandardMacro(c) && c != '\\' && c != '|';
	}

}
