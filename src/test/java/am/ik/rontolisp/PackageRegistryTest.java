package am.ik.rontolisp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order contracts of {@link PackageRegistry} helpers whose result is carried around
 * in a data model -- see {@code .kb/emitted-output-determinism.md} -- and the external
 * set of the {@code cl} package, which CLHS 11.1.2.1 fixes at the standard's own list of
 * names.
 */
class PackageRegistryTest {

	/**
	 * The 978 names CLHS 11.1.2.1 requires the {@code common-lisp} package to export, one
	 * per line, upcased. Checked in rather than derived, because it is the STANDARD's
	 * list: it does not move, and deriving it from the implementation would make the pin
	 * below tautological.
	 */
	private static Set<String> standardNames() throws IOException {
		try (InputStream in = PackageRegistryTest.class.getResourceAsStream("/cl-standard-symbol-names.txt")) {
			assertThat(in).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
		}
	}

	@Test
	void bothSpellingsOfAQualifiedNameComeBackInAFixedOrder() {
		// A List, not a Set: a two-element Set.of iterates in a per-JVM-run order, and
		// the pruners that ask this question keep the answer in their data model.
		assertThat(PackageRegistry.spellings("uiop:getenv")).containsExactly("uiop:getenv", "uiop::getenv");
		assertThat(PackageRegistry.spellings("uiop::getenv")).containsExactly("uiop:getenv", "uiop::getenv");
	}

	@Test
	void anUnqualifiedNameIsItsOwnAndOnlySpelling() {
		assertThat(PackageRegistry.spellings("car")).containsExactly("car");
	}

	@Test
	void theClPackageExportsEveryStandardName() throws IOException {
		Set<String> standard = standardNames();
		assertThat(standard).hasSize(978);
		Set<String> externals = new PackageRegistry().get(LispNames.CL_PKG).externals();
		List<String> notExported = standard.stream()
			// The car/cdr compositions are a PATTERN, not a set (caar ... cddddr), so
			// they are external without being enumerated anywhere.
			.filter(name -> !externals.contains(name) && !LispNames.isCarCdrComposition(name))
			.sorted()
			.toList();
		assertThat(notExported).isEmpty();
	}

	@Test
	void theClPackageExportsNothingBeyondTheStandardNamesExceptTheDocumentedExtensions() throws IOException {
		Set<String> standard = standardNames();
		List<String> extra = new PackageRegistry().get(LispNames.CL_PKG)
			.externals()
			.stream()
			.filter(name -> !standard.contains(name))
			.sorted()
			.toList();
		// while is rontolisp's own loop primitive and lives in cl because that is where
		// the built-in operators live, which makes the suite's
		// no-extra-symbols-exported-from-common-lisp fail; see .kb/packages.md.
		assertThat(extra).containsExactly("WHILE");
	}

	@Test
	void anExportedOnlyNameIsNotASymbolANYResolutionDecisionSees() {
		// The whole point of the split: the name is exported (find-symbol answers
		// :external) and is NOT a cl symbol, so a bare reference still interns in the
		// current package, a defun of it is not "redefining a standard operator", and
		// the library pruner's reference scan does not see it as resolvable.
		assertThat(PackageRegistry.clExportedOnlyNames()).isNotEmpty()
			.allSatisfy(name -> assertThat(PackageRegistry.isClSymbol(name)).isFalse());
		assertThat(PackageRegistry.isClMemberName("BIT-AND")).isTrue();
		assertThat(PackageRegistry.isClSymbol("BIT-AND")).isFalse();
		assertThat(PackageRegistry.isClMemberName("CAR")).isTrue();
		assertThat(PackageRegistry.isClMemberName("NO-SUCH-NAME")).isFalse();
	}

}
