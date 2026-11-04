package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.SourceProvenance;

/**
 * The first pass of the compile path's pipeline, in a program whose packages have
 * nicknames of their own ({@code .kb/packages.md}, "Package-local nicknames"): reads each
 * qualified token through the nicknames of the package current at it, what a reader does.
 * The passes after it recognize a library by the package a symbol spells, so
 * {@code l:mul} under {@code (:local-nicknames (:l :linalg))} has to reach them as
 * {@code LINALG:MUL}, and a package's {@code ffi:} nickname for another library must not
 * reach them as the {@code ffi} package. It sees the program as read, so what only a
 * macro expansion defines a nickname with is left to the resolvers, which read through
 * what they know.
 */
public final class LocalNicknameRead {

	private LocalNicknameRead() {
	}

	/**
	 * Reads the program's qualifiers through its local nicknames.
	 * @param program the top-level forms as read
	 * @return the program, the same list when it has no local nickname
	 */
	public static List<LispVal> read(List<LispVal> program) {
		if (program.stream().noneMatch(PackageResolver::namesLocalNicknames)) {
			return program;
		}
		PackageResolver resolver = new PackageResolver();
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(read(form, resolver));
		}
		return out;
	}

	private static LispVal read(LispVal form, PackageResolver resolver) {
		// The members of a top-level progn or eval-when are top-level forms: a package
		// directive among them governs the members after it.
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& (isOperator(op, LispNames.PROGN) || isOperator(op, LispNames.EVAL_WHEN)) && cons.isProperList()) {
			List<LispVal> parts = new ArrayList<>(cons.toList());
			for (int i = isOperator(op, LispNames.PROGN) ? 1 : 2; i < parts.size(); i++) {
				parts.set(i, read(parts.get(i), resolver));
			}
			return SourceProvenance.inherit(cons, LispCons.rebuiltList(cons, parts));
		}
		LispVal read = resolver.readLocalNicknames(form);
		resolver.followLocalNicknameDirective(read);
		return read;
	}

	private static boolean isOperator(LispSymbol op, String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return name.equals(qn == null ? op.name() : qn.member());
	}

}
