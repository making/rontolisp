package am.ik.rontolisp.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.eval.TlsPemSupport;

import org.jspecify.annotations.Nullable;

/**
 * Rewrites {@code rontolisp:tls-listen-pem} calls into {@code rontolisp:%tls-listen-p12}
 * on the compile path. The interpreter reads PEM files at run time, but the JVM/WASM
 * compilers emit hand-assembled bytecode and cannot practically parse PEM (Base64 + a
 * {@code KeyFactory} algorithm loop) there. Instead this pass parses the PEM at compile
 * time (via {@link TlsPemSupport}, the same parser the interpreter uses), serializes the
 * resulting PKCS12 keystore to a Base64 string literal, and swaps in a call to the
 * internal {@code %tls-listen-p12} built-in that binds the listener from those embedded
 * bytes -- reusing the existing {@code tls-listen} machinery.
 *
 * <p>
 * Only calls whose {@code cert-file} and {@code key-file} arguments are string literals
 * are rewritten (the certificate and key are baked into the compiled output); a call with
 * a computed path is a compile error, because there is no runtime PEM parser in compiled
 * output. The pass walks the whole tree (a {@code tls-listen-pem} may appear inside a
 * {@code let}/loop), and relative paths resolve against the directory of the source file.
 */
public final class TlsPemInliner {

	private TlsPemInliner() {
	}

	/**
	 * Returns a copy of {@code program} with every {@code rontolisp:tls-listen-pem} call
	 * rewritten to {@code rontolisp:%tls-listen-p12} carrying the compile-time-parsed
	 * PKCS12 keystore as a Base64 literal.
	 * @param program the top-level forms
	 * @param baseDir the directory of the source file (for resolving relative PEM paths),
	 * or {@code null} for the working directory
	 * @return the rewritten program
	 */
	public static List<LispVal> inline(List<LispVal> program, @Nullable String baseDir) {
		List<LispVal> result = new ArrayList<>(program.size());
		for (LispVal form : program) {
			result.add(rewrite(form, baseDir));
		}
		return result;
	}

	private static LispVal rewrite(LispVal form, @Nullable String baseDir) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol op && LispNames.TLS_LISTEN_PEM.equals(member(op.name()))) {
			return rewriteCall(cons.toList(), baseDir);
		}
		return new LispCons(rewrite(cons.car(), baseDir), rewrite(cons.cdr(), baseDir));
	}

	private static LispVal rewriteCall(List<LispVal> elements, @Nullable String baseDir) {
		List<LispVal> args = elements.subList(1, elements.size());
		if (args.size() < 3 || args.size() > 4) {
			throw new UnsupportedOperationException(
					LispNames.TLS_LISTEN_PEM + " expects 3 or 4 arguments, got " + args.size());
		}
		if (!(args.get(0) instanceof LispString certPath) || !(args.get(1) instanceof LispString keyPath)) {
			throw new UnsupportedOperationException(
					LispNames.TLS_LISTEN_PEM + ": cert-file and key-file must be string literals when compiling "
							+ "(compiled output embeds the certificate; use the interpreter for runtime paths)");
		}
		String cert = resolve(baseDir, certPath.value());
		String key = resolve(baseDir, keyPath.value());
		String base64 = TlsPemSupport.pemToBase64Pkcs12(cert, key);
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TLS_LISTEN_P12)));
		out.add(new LispString(base64));
		out.add(new LispString(TlsPemSupport.KEYSTORE_PASSWORD));
		// port and optional host pass through unchanged (they may be runtime
		// expressions).
		for (int i = 2; i < args.size(); i++) {
			out.add(args.get(i));
		}
		LispVal result = LispNil.INSTANCE;
		for (int i = out.size() - 1; i >= 0; i--) {
			result = new LispCons(out.get(i), result);
		}
		return result;
	}

	private static String resolve(@Nullable String baseDir, String path) {
		Path p = Path.of(path);
		if (baseDir == null || baseDir.isEmpty() || p.isAbsolute()) {
			return p.toString();
		}
		return Path.of(baseDir).resolve(p).normalize().toString();
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
