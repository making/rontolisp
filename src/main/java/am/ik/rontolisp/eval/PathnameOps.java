package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Namestring-level pathname operations for the CL {@code make-pathname} and
 * {@code uiop:merge-pathnames*} runtime primitives. Rontolisp represents paths as strings
 * (no {@code pathname} type), so these helpers work on strings only: a "directory" is the
 * prefix through the last {@code /}, the "name.type" is what follows.
 *
 * <p>
 * Design constraint: match the specific patterns real Quicklisp libraries emit for
 * load-time data-file path resolution (uax-15's {@code precomputed-tables.lisp} is the
 * seed case), not the full CL pathname protocol. See {@code .kb/asdf.md} for the wider
 * ASDF-subset story.
 */
public final class PathnameOps {

	private PathnameOps() {
	}

	/**
	 * Coerces a Lisp value to a namestring. A {@code LispString} passes through; a
	 * keyword or a symbol {@code nil} is the empty string ("no name / no type" in the
	 * make-pathname API); anything else is a hard error.
	 * @param context the operator name for the error message
	 * @param value the Lisp value
	 * @return the namestring
	 */
	public static String namestring(String context, LispVal value) {
		if (value instanceof LispString str) {
			return str.value();
		}
		if (value instanceof LispNil || (value instanceof LispSymbol sym && "NIL".equals(sym.name()))) {
			return "";
		}
		throw new LispEvalException(context + ": expected a pathname (string), got " + value.print());
	}

	/**
	 * Merges {@code specified} onto {@code defaults}, following the CL {@code
	 * merge-pathnames} rule: the {@code specified} directory/name/type override
	 * {@code defaults}'s where present, otherwise {@code defaults} fills in the gap. A
	 * relative {@code specified} directory is appended to {@code defaults}'s directory.
	 * @param specified the primary namestring
	 * @param defaults the defaults namestring
	 * @return the merged namestring
	 */
	public static String mergePathnames(String specified, String defaults) {
		Parts p = split(specified);
		Parts d = split(defaults);
		// Directory: an absolute specified wins; otherwise the specified's tail is
		// appended to defaults's directory (matching CL merge-pathnames on a relative
		// primary). A specified with no directory keeps the defaults's directory.
		String directory;
		if (p.directory.isEmpty()) {
			directory = d.directory;
		}
		else if (p.directory.startsWith("/")) {
			directory = p.directory;
		}
		else {
			directory = combineDirectories(d.directory, p.directory);
		}
		String filename = p.filename.isEmpty() ? d.filename : p.filename;
		return directory + filename;
	}

	/**
	 * Builds a namestring from a CL {@code make-pathname} argument list. Supported
	 * keywords: {@code :directory} (a list starting with {@code :relative} or
	 * {@code :absolute}, followed by name components), {@code :name} (a string or
	 * {@code nil}), {@code :type} (a string or {@code nil}), {@code :defaults} (a
	 * namestring merged into the result), plus the tolerated no-ops {@code :host},
	 * {@code :device}, {@code :version}, {@code :case} (accepted and dropped).
	 * @param args the keyword-value pairs after {@code make-pathname}
	 * @return the composed namestring
	 */
	public static String makePathname(List<LispVal> args) {
		if ((args.size() & 1) != 0) {
			throw new LispEvalException(LispNames.MAKE_PATHNAME + " expects :option value pairs");
		}
		@Nullable LispVal directoryArg = null;
		@Nullable LispVal nameArg = null;
		@Nullable LispVal typeArg = null;
		@Nullable LispVal defaultsArg = null;
		for (int i = 0; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new LispEvalException(LispNames.MAKE_PATHNAME + " expects :option value pairs");
			}
			switch (key.name()) {
				case ":DIRECTORY" -> directoryArg = args.get(i + 1);
				case ":NAME" -> nameArg = args.get(i + 1);
				case ":TYPE" -> typeArg = args.get(i + 1);
				case ":DEFAULTS" -> defaultsArg = args.get(i + 1);
				case ":HOST", ":DEVICE", ":VERSION", ":CASE" -> {
					// Tolerated no-ops (the components rontolisp's string paths do not
					// model): accepted so a portability layer's make-pathname call still
					// works.
				}
				default -> throw new LispEvalException(LispNames.MAKE_PATHNAME + ": unsupported option " + key.name());
			}
		}
		String directory = formatDirectory(directoryArg);
		String name = optionalString(nameArg);
		String type = optionalString(typeArg);
		String filename = type.isEmpty() ? name : name.isEmpty() ? "" : name + "." + type;
		String out = directory + filename;
		if (defaultsArg != null) {
			out = mergePathnames(out, namestring(LispNames.MAKE_PATHNAME, defaultsArg));
		}
		return out;
	}

	private record Parts(String directory, String filename) {
	}

	private static Parts split(String namestring) {
		int slash = namestring.lastIndexOf('/');
		if (slash < 0) {
			return new Parts("", namestring);
		}
		return new Parts(namestring.substring(0, slash + 1), namestring.substring(slash + 1));
	}

	private static String combineDirectories(String defaultsDir, String specifiedDir) {
		if (defaultsDir.isEmpty()) {
			return specifiedDir;
		}
		if (specifiedDir.isEmpty()) {
			return defaultsDir;
		}
		String left = defaultsDir.endsWith("/") ? defaultsDir : defaultsDir + "/";
		return left + specifiedDir;
	}

	private static String optionalString(@Nullable LispVal value) {
		if (value == null || value instanceof LispNil
				|| (value instanceof LispSymbol sym && "NIL".equals(sym.name()))) {
			return "";
		}
		if (value instanceof LispString str) {
			return str.value();
		}
		if (value instanceof LispSymbol sym) {
			return sym.isKeyword() ? sym.name().substring(1) : sym.name();
		}
		throw new LispEvalException(
				LispNames.MAKE_PATHNAME + ": :name and :type must be a string or nil, got " + value.print());
	}

	private static String formatDirectory(@Nullable LispVal directoryArg) {
		if (directoryArg == null || directoryArg instanceof LispNil
				|| (directoryArg instanceof LispSymbol sym && "NIL".equals(sym.name()))) {
			return "";
		}
		if (directoryArg instanceof LispString str) {
			// A bare string directory (uiop convention): return as-is, adding the
			// trailing / if missing so downstream merges keep the directory intact.
			String s = str.value();
			return s.isEmpty() || s.endsWith("/") ? s : s + "/";
		}
		if (!(directoryArg instanceof LispCons list) || !list.isProperList()) {
			throw new LispEvalException(
					LispNames.MAKE_PATHNAME + ": :directory must be a list or string, got " + directoryArg.print());
		}
		List<LispVal> parts = list.toList();
		boolean absolute;
		int start = 0;
		if (parts.isEmpty()) {
			return "";
		}
		if (parts.get(0) instanceof LispSymbol head && head.isKeyword()) {
			absolute = ":ABSOLUTE".equals(head.name());
			if (!absolute && !":RELATIVE".equals(head.name())) {
				throw new LispEvalException(LispNames.MAKE_PATHNAME
						+ ": :directory head must be :relative or :absolute, got " + head.name());
			}
			start = 1;
		}
		else {
			// A bare list of components is treated as relative.
			absolute = false;
		}
		StringBuilder sb = new StringBuilder(absolute ? "/" : "");
		for (int i = start; i < parts.size(); i++) {
			LispVal component = parts.get(i);
			if (component instanceof LispString str) {
				sb.append(str.value()).append('/');
			}
			else if (component instanceof LispSymbol sym && sym.isKeyword()) {
				switch (sym.name()) {
					case ":UP", ":BACK" -> sb.append("../");
					case ":WILD" -> sb.append("*/");
					default -> throw new LispEvalException(
							LispNames.MAKE_PATHNAME + ": unsupported :directory component " + sym.name());
				}
			}
			else {
				throw new LispEvalException(
						LispNames.MAKE_PATHNAME + ": :directory component must be a string, got " + component.print());
			}
		}
		return sb.toString();
	}

}
