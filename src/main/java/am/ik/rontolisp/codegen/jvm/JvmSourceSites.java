package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.UncaughtReport;

import org.jspecify.annotations.Nullable;

/**
 * Where each stretch of a compiled program came from: the table behind the location lines
 * the uncaught-condition report prints under its report line ({@link JvmUncaughtHandler},
 * {@code .kb/error-handling.md}).
 *
 * <p>
 * <b>A site is a form's file and line, plus the function whose body holds it.</b> Every
 * method carries a {@code LineNumberTable}, and the number it maps an instruction to is a
 * SITE id rather than a line: one method can hold the forms of several files (a top-level
 * chunk spans the files the program loads; a macro can hand back a form read from its own
 * file) while a class names one {@code SourceFile}, and a line alone would not say which
 * function a frame is in. The report reads the id back through
 * {@link StackTraceElement#getLineNumber()} and decodes it from the tables this class
 * builds, which the class carries as string constants ({@link #tableChunks},
 * {@link #nameChunks}). Nothing runs until a condition escapes: a program that never
 * signals pays the table bytes and nothing else.
 *
 * <p>
 * <b>Only positions the compile path recorded, from a NAMED file.</b> A form's position
 * is {@link SourceProvenance#locate}'s -- the reader records every list it reads -- so a
 * {@code -e} program, library source spliced from the jar and forms a macro built have
 * none, exactly the forms the interpreter does not locate either ({@code LocatedCons}). A
 * compile with nothing located produces an empty table, and then the class carries no
 * line numbers, no table and no report code: the bytes it always had.
 *
 * <p>
 * <b>A function is named only when its body was read from a named file</b>
 * ({@link #sourced}) -- the same rule the interpreter's report applies, so a library
 * function a user callback runs under ({@code %sort-runtime} here, a Java built-in there)
 * never takes the attribution from the user function around it.
 */
final class JvmSourceSites {

	/**
	 * The most sites one compilation can number: a {@code LineNumberTable} carries its
	 * number in a u2, and 0 means "no site". Past it a form gets no site, so a report
	 * from it names the enclosing one -- a degraded answer, never a wrong one.
	 */
	static final int MAX_SITES = 0xFFFF;

	/** The largest line a site can carry: the table holds it in one {@code char}. */
	private static final int MAX_LINE = 0xFFFF;

	/**
	 * The most bytes a {@code CONSTANT_Utf8} holds (JVMS 4.4.7); a table longer than one
	 * constant travels as several, concatenated back by the report.
	 */
	private static final int MAX_CONSTANT_BYTES = 0xFFFF;

	/** What separates the names in {@link #nameChunks}: no file or symbol name has it. */
	static final char NAME_SEPARATOR = '\0';

	/**
	 * One site: indexes into {@link #names} (0: none) and a line.
	 *
	 * @param file the form's file, or 0 for a function's base site
	 * @param line the form's 1-based line, or 0 for a base site
	 * @param owner the function whose body holds the form, or 0 for the top level and an
	 * anonymous function
	 */
	private record Site(int file, int line, int owner) {
	}

	/** File and function names, numbered from 1 in first-use order. */
	private final Map<String, Integer> names = new LinkedHashMap<>();

	private final Map<Site, Integer> ids = new HashMap<>();

	/** The sites in id order: site {@code k} is at index {@code k - 1}. */
	private final List<Site> sites = new ArrayList<>();

	/**
	 * The name the report calls a compiled defun by
	 * ({@link UncaughtReport#functionName}): the program's own spelling, where a lowering
	 * renamed the function.
	 * @param defunName the defun's name as compiled
	 * @return the name to report
	 */
	static String reportedName(String defunName) {
		return UncaughtReport.functionName(defunName);
	}

	/**
	 * Whether a function body holds a form read from a named file -- what makes it the
	 * program's own code, and so a function the report may name.
	 * @param body the body forms
	 * @return true when one of them, at any depth, has a recorded position in a file
	 */
	static boolean sourced(List<LispVal> body) {
		for (LispVal form : body) {
			if (LispTrees.anyCons(form, SourceProvenance::locatedInFile)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The owner code of a function, for {@link #site} and {@link #base}.
	 * @param function the name the report calls it by, or {@code null} for the top level
	 * and an anonymous function
	 * @return the code; 0 for {@code null}
	 */
	int owner(@Nullable String function) {
		return function == null ? 0 : this.nameIndex(function);
	}

	/**
	 * The site of a form, numbering it on first use.
	 * @param form the form being compiled
	 * @param owner the owner code of the function whose method it is compiled into
	 * @return its id, or 0 when the form has no position in a named file (or the table is
	 * full)
	 */
	int site(LispCons form, int owner) {
		SourceLocation location = SourceProvenance.locate(form);
		if (location == null) {
			return 0;
		}
		String file = location.file();
		if (file == null || location.line() > MAX_LINE) {
			return 0;
		}
		return this.id(new Site(this.nameIndex(file), location.line(), owner));
	}

	/**
	 * The site every instruction of a named function's methods reports outside its
	 * located forms: no file or line, only the function -- so a frame stopped in a
	 * prologue, or in code a macro built, still says which function it is.
	 * @param owner the function's owner code
	 * @return its id, or 0 for owner 0 (and when the table is full)
	 */
	int base(int owner) {
		return owner == 0 ? 0 : this.id(new Site(0, 0, owner));
	}

	/**
	 * @return whether no site was numbered -- the compile located nothing, and the class
	 * must come out as it would have without this table
	 */
	boolean isEmpty() {
		return this.sites.isEmpty();
	}

	/**
	 * The site table as the report reads it: three chars per site, in id order -- the
	 * file's name index (0 for a base site), the line, and the function's name index (0
	 * for none) -- cut into pieces that each fit one constant.
	 * @return the pieces, to be concatenated in order
	 */
	List<String> tableChunks() {
		StringBuilder table = new StringBuilder(this.sites.size() * 3);
		for (Site site : this.sites) {
			table.append((char) site.file()).append((char) site.line()).append((char) site.owner());
		}
		return chunks(table);
	}

	/**
	 * Every name the table indexes, each preceded by {@link #NAME_SEPARATOR}, so that
	 * splitting the concatenation at the separator puts name {@code k} at index
	 * {@code k}.
	 * @return the pieces, to be concatenated in order
	 */
	List<String> nameChunks() {
		StringBuilder joined = new StringBuilder();
		for (String name : this.names.keySet()) {
			joined.append(NAME_SEPARATOR).append(name);
		}
		return chunks(joined);
	}

	private int nameIndex(String name) {
		// A separator inside a name would shift every name after it; no file path or
		// symbol a reader produces holds one, but a report must not depend on that.
		String safe = name.replace(NAME_SEPARATOR, '?');
		Integer index = this.names.get(safe);
		if (index == null) {
			index = this.names.size() + 1;
			this.names.put(safe, index);
		}
		return index;
	}

	private int id(Site site) {
		Integer id = this.ids.get(site);
		if (id != null) {
			return id;
		}
		if (this.sites.size() >= MAX_SITES || site.file() > Character.MAX_VALUE || site.owner() > Character.MAX_VALUE) {
			return 0;
		}
		this.sites.add(site);
		this.ids.put(site, this.sites.size());
		return this.sites.size();
	}

	/**
	 * Cuts a string into pieces whose modified UTF-8 encoding each fits one
	 * {@code CONSTANT_Utf8}. Modified UTF-8 encodes every {@code char} on its own
	 * ({@code U+0000} in two bytes, a surrogate in three), so any char boundary is a
	 * legal cut.
	 */
	private static List<String> chunks(CharSequence text) {
		List<String> chunks = new ArrayList<>();
		int start = 0;
		int bytes = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			int width = c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
			if (bytes + width > MAX_CONSTANT_BYTES) {
				chunks.add(text.subSequence(start, i).toString());
				start = i;
				bytes = 0;
			}
			bytes += width;
		}
		if (start < text.length() || chunks.isEmpty()) {
			chunks.add(text.subSequence(start, text.length()).toString());
		}
		return chunks;
	}

}
