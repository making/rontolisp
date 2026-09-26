package am.ik.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The class-file reader and the class path a compile-time resolver reads classes from: a
 * JDK's {@code ct.sym} for one release (with its modules' exports) and class path
 * directories and archives.
 */
class JvmClassPathTest {

	private static final Path CT_SYM = Path.of(System.getProperty("java.home"), "lib", "ct.sym");

	private static byte[] classBytes(Class<?> type) throws IOException {
		try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
			return Objects.requireNonNull(in).readAllBytes();
		}
	}

	@Test
	void aClassFileReadsItsDeclaredShape() throws IOException {
		ClassFileInfo info = ClassFileInfo.parse(classBytes(JvmClassPath.class));
		assertThat(info.name()).isEqualTo("am/ik/jvm/JvmClassPath");
		assertThat(info.superName()).isEqualTo("java/lang/Object");
		assertThat(info.interfaces()).containsExactly("java/lang/AutoCloseable");
		assertThat(info.isPublic()).isTrue();
		assertThat(info.isFinal()).isTrue();
		assertThat(info.methods()).extracting(ClassFileInfo.Member::name).contains("of", "find", "close", "<init>");
		assertThat(info.methods()).filteredOn(m -> "find".equals(m.name()))
			.extracting(ClassFileInfo.Member::descriptor)
			.containsExactly("(Ljava/lang/String;)Lam/ik/jvm/JvmClassPath$Entry;");
	}

	@Test
	void ctSymHoldsEveryReleaseFromEightToTheJdksOwn() {
		List<Integer> releases = JvmClassPath.releases(CT_SYM);
		assertThat(releases).contains(8, 11, 17, 21, Runtime.version().feature());
		assertThat(releases.get(releases.size() - 1)).isEqualTo(Runtime.version().feature());
	}

	@Test
	void aReleaseReadsThatReleasesApi() {
		try (JvmClassPath release8 = JvmClassPath.of(CT_SYM, 8, List.of());
				JvmClassPath release17 = JvmClassPath.of(CT_SYM, 17, List.of())) {
			// java.lang.Module arrived in 9, String.repeat in 11.
			assertThat(release8.find("java/lang/Module")).isNull();
			assertThat(release17.find("java/lang/Module")).isNotNull();
			assertThat(methodNames(Objects.requireNonNull(release8.find("java/lang/String")))).doesNotContain("repeat");
			assertThat(methodNames(Objects.requireNonNull(release17.find("java/lang/String")))).contains("repeat");
		}
	}

	@Test
	void onlyAPublicClassOfAnExportedPackageIsAccessible() {
		try (JvmClassPath path = JvmClassPath.of(CT_SYM, Runtime.version().feature(), List.of())) {
			assertThat(Objects.requireNonNull(path.find("java/lang/String")).accessible()).isTrue();
			// Package-private, in an exported package: present (a supertype of a public
			// class) but not callable.
			assertThat(Objects.requireNonNull(path.find("java/lang/AbstractStringBuilder")).accessible()).isFalse();
			assertThat(path.find("no/such/Type")).isNull();
		}
	}

	@Test
	void aClassPathDirectoryOrArchiveFollowsThePlatform(@TempDir Path dir) throws IOException {
		Path classes = dir.resolve("classes");
		Path file = classes.resolve("am/ik/jvm/ClassFileInfo.class");
		Files.createDirectories(Objects.requireNonNull(file.getParent()));
		Files.write(file, classBytes(ClassFileInfo.class));
		Path jar = dir.resolve("lib.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("am/ik/jvm/JvmClassPath.class"));
			zip.write(classBytes(JvmClassPath.class));
			zip.closeEntry();
		}
		try (JvmClassPath path = JvmClassPath.of(CT_SYM, 17, List.of(classes, jar))) {
			// Every class of the class path is in the unnamed module: accessible.
			assertThat(Objects.requireNonNull(path.find("am/ik/jvm/ClassFileInfo")).accessible()).isTrue();
			assertThat(Objects.requireNonNull(path.find("am/ik/jvm/JvmClassPath")).accessible()).isTrue();
			assertThat(path.find("java/lang/String")).isNotNull();
			assertThat(path.find("am/ik/jvm/Opcode")).isNull();
		}
	}

	private static List<String> methodNames(JvmClassPath.Entry entry) {
		return entry.info().methods().stream().map(ClassFileInfo.Member::name).toList();
	}

}
