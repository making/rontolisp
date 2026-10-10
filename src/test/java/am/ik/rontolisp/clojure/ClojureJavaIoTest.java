package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code clojure.java.io} over directories and the source path, on the interpreter, the
 * JVM and both WASM backends (a {@code --dir} preopen covering the directory): what needs
 * a directory of its own, which the shared spec program ({@code clojure-spec.yaml}, the
 * {@code clojure-java-io-*} cases) cannot hold. The expected output is the oracle's
 * ({@code clj} 1.12.6, 2026-10-08) but where a comment says otherwise.
 */
class ClojureJavaIoTest {

	@TempDir
	Path dir;

	/**
	 * Makes, lists, walks and deletes below the root the program's first form names: the
	 * oracle's answers, each path printed below the root.
	 */
	private static final String DIRECTORIES = """
			(require '[clojure.java.io :as io])
			(def d (io/file root "tree"))
			(prn (.mkdirs d) (.mkdirs d) (.mkdir d) (.isDirectory d) (.exists d) (.isFile d))
			(prn (.mkdir (io/file d "x" "y")) (.mkdirs (io/file d "x" "y")) (.mkdir (io/file d "z")))
			(spit (io/file d "a.txt") "a")
			(spit (io/file d "x" "b.txt") "bb")
			(prn (sort (.list d)) (.list (io/file d "a.txt")) (.listFiles (io/file d "missing")))
			(prn (sort (map #(.getName %) (.listFiles d))) (every? #(instance? java.io.File %) (.listFiles d)))
			(prn (map #(subs (str %) (count root)) (sort-by str (file-seq d))))
			(prn (realized? (file-seq d)) (map #(.getName %) (take 1 (file-seq d))))
			(prn (io/make-parents d "p" "q" "r.txt") (io/make-parents d "p" "q" "r.txt") (.isDirectory (io/file d "p" "q")))
			(prn (.length (io/file d "x" "b.txt")) (.isDirectory (io/file d "x")) (.isFile (io/file d "x" "b.txt")))
			(prn (.delete d) (io/delete-file (io/file d "a.txt")) (.delete (io/file d "x" "b.txt")))
			(prn (try (slurp (io/file d "x")) (catch java.io.FileNotFoundException e (subs (ex-message e) (count root)))))
			(prn (try (spit (io/file d "nope" "f.txt") "x") (catch java.io.FileNotFoundException e (subs (ex-message e) (count root)))))
			""";

	private static final String DIRECTORIES_OUT = """
			true false false true true false
			false true true
			("a.txt" "x" "z") nil nil
			("a.txt" "x" "z") true
			("/tree" "/tree/a.txt" "/tree/x" "/tree/x/b.txt" "/tree/x/y" "/tree/z")
			false ("tree")
			true false true
			2 true true
			false true true
			"/tree/x (Is a directory)"
			"/tree/nope/f.txt (No such file or directory)"
			""";

	@Test
	void directoriesAreMadeListedWalkedAndDeletedOnTheInterpreterAndTheJvm() throws Exception {
		assertThat(interpret(rooted(DIRECTORIES, "i"), null)).isEqualTo(DIRECTORIES_OUT);
		assertThat(runOnJvm(rooted(DIRECTORIES, "j"), null, "JioDirs")).isEqualTo(DIRECTORIES_OUT);
	}

	@Test
	void directoriesAreMadeListedWalkedAndDeletedOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		assertThat(runOnWasm(rooted(DIRECTORIES, "w"), null, false, this.dir)).isEqualTo(DIRECTORIES_OUT);
		assertThat(runOnWasm(rooted(DIRECTORIES, "c"), null, true, this.dir)).isEqualTo(DIRECTORIES_OUT);
	}

	/**
	 * An empty directory is deleted and a non-empty one is not, like the oracle; a file's
	 * {@code .lastModified} is its modification time in whole seconds' worth of
	 * milliseconds (between the first instants of 2023 and 2100), a missing file's 0. The
	 * same answers on all four backends: wasm removes the directory through
	 * {@code path_remove_directory} and dates the file through {@code path_filestat_get}.
	 */
	private static final String EMPTY_DIRECTORY = """
			(def e (java.io.File. root "empty"))
			(prn (.mkdir e) (.delete e) (.exists e))
			(def full (java.io.File. root "full"))
			(def inner (java.io.File. full "f.txt"))
			(prn (.mkdir full) (.createNewFile inner) (.delete full) (.exists full))
			(def stamp (.lastModified inner))
			(prn (< 1672531200000 stamp 4102444800000) (zero? (mod stamp 1000)))
			(prn (.lastModified (java.io.File. root "missing")))
			""";

	private static final String EMPTY_DIRECTORY_OUT = """
			true true false
			true true false true
			true true
			0
			""";

	@Test
	void anEmptyDirectoryIsDeletedAndAFileDatedOnEveryBackend() throws Exception {
		assertThat(interpret(rooted(EMPTY_DIRECTORY, "ei"), null)).isEqualTo(EMPTY_DIRECTORY_OUT);
		assertThat(runOnJvm(rooted(EMPTY_DIRECTORY, "ej"), null, "JioEmpty")).isEqualTo(EMPTY_DIRECTORY_OUT);
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		assertThat(runOnWasm(rooted(EMPTY_DIRECTORY, "ew"), null, false, this.dir)).isEqualTo(EMPTY_DIRECTORY_OUT);
		assertThat(runOnWasm(rooted(EMPTY_DIRECTORY, "ec"), null, true, this.dir)).isEqualTo(EMPTY_DIRECTORY_OUT);
	}

	/**
	 * A project whose source path holds a resources directory and a jar: a literal name
	 * is found while the program lowers (a jar's entry too, which travels with the
	 * program), a computed one below the roots, the jar's among them, when the program
	 * runs. The oracle's answers.
	 */
	private static final String RESOURCES = """
			(ns app.main (:require [clojure.java.io :as io]))
			(prn (slurp (io/resource "conf.edn")))
			(prn (slurp (io/resource "jarres/msg.txt")))
			(prn (str (io/resource "conf.edn")))
			(prn (str (io/resource "jarres/msg.txt")))
			(def computed (str "co" "nf.edn"))
			(prn (some-> (io/resource computed) slurp))
			(prn (io/resource "missing.txt") (io/resource (str "miss" "ing.txt")))
			(with-open [r (io/reader (io/resource "conf.edn"))] (prn (read-string (slurp r))))
			(prn (.getPath (io/resource "conf.edn")) (.getProtocol (io/resource "jarres/msg.txt")))
			(with-open [in (io/input-stream (io/resource "jarres/msg.txt"))] (prn (.read in)))
			(def in-jar (str "jarres/" "msg.txt"))
			(prn (str (io/resource in-jar)) (slurp (io/resource in-jar)))
			""";

	/**
	 * A jar's entries when the program runs, against the jar {@link #resourceProject}
	 * makes: a computed name the jar holds after the directories hold none, a stored
	 * entry, an entry whose name needs quoting, a binary entry octet for octet (found by
	 * a literal name and by a computed one) and decoded in two charsets, an explicit
	 * directory entry and a directory a jar only implies, a directory root's directory
	 * and the empty name (the first root), dot segments resolved over the root and a name
	 * leaving it, and the reads that fail as a {@code JarURLConnection}'s do; then a jar
	 * ending in a zip64 end record and one behind a stub, which {@code ZipFile} reads
	 * too. The oracle's answers ({@code clj} 1.12.6 on JDK 25, 2026-10-10).
	 */
	private static final String JAR_ENTRIES = """
			(ns app.main (:require [clojure.java.io :as io]))
			(def jar (str "%JAR%" ""))
			(def deps (str "%DEPS%" ""))
			(defn c [& parts] (apply str parts))
			(defn octets [u] (with-open [in (io/input-stream u)] (vec (take 4 (drop 252 (.readAllBytes in))))))
			(prn (str (io/resource (c "public/" "p.txt"))) (slurp (io/resource (c "public/" "p.txt"))))
			(prn (slurp (io/resource (c "jarres/" "stored.txt"))) (str (io/resource (c "jarres/sp" " ace.txt"))))
			(prn (octets (io/resource "jarres/img.bin")) (octets (io/resource (c "jarres/" "img.bin"))))
			(prn (count (slurp (io/resource "jarres/img.bin")))
			     (count (slurp (io/resource (c "jarres/img" ".bin")) :encoding "ISO-8859-1")))
			(prn (str (io/resource "jardir")) (str (io/resource (c "jar" "dir/"))) (io/resource "nodir") (io/resource (c "no" "dir")))
			(prn (str (io/resource "public")) (str (io/resource (c "public/"))) (str (io/resource "")) (str (io/resource (c ""))))
			(prn (slurp (io/resource "jardir")) (slurp (io/resource (c "jardir"))))
			(prn (str (io/resource (c "../resources/public/r.txt"))) (io/resource (c "/public/r.txt"))
			     (str (io/resource (c "public/sub/../r.txt"))) (str (io/resource "public/sub/../r.txt"))
			     (io/resource (c "../../res.jar")) (str (io/resource (c "public/sub/.."))))
			(prn (try (slurp (io/as-url (c "jar:file:" jar "!/nope.txt"))) (catch java.io.FileNotFoundException e (ex-message e))))
			(prn (try (slurp (io/as-url "jar:file:/no/such.jar!/x.txt")) (catch java.nio.file.NoSuchFileException e (ex-message e))))
			(prn (try (slurp (io/as-url (c "jar:file:" jar "!/"))) (catch java.io.IOException e (ex-message e))))
			(prn (try (slurp (io/as-url (c "jar:file:" deps "!/x"))) (catch java.util.zip.ZipException e (ex-message e))))
			(with-open [in (io/input-stream (io/resource (c "jarres/" "msg.txt")))] (prn (.read in) (.available in)))
			(prn (line-seq (io/reader (io/resource (c "jarres/" "msg.txt")))) (slurp (c "jar:file:" jar "!/jarres/msg.txt")))
			(prn (slurp (io/resource "z64/hello.txt")) (slurp (io/resource (c "z64/" "hello.txt")))
			     (slurp (io/resource (c "sfx/" "hello.txt"))))
			""";

	private Path resourceProject(String program) throws IOException {
		Path proj = this.dir.resolve("proj");
		write(proj.resolve("deps.edn"), "{:paths [\"src\" \"resources\"]\n :deps {my/res {:local/root \"../res.jar\"}"
				+ " my/z64 {:local/root \"../z64.jar\"} my/sfx {:local/root \"../sfx.jar\"}}}\n");
		write(proj.resolve("resources/conf.edn"), "{:a 1}\n");
		write(proj.resolve("resources/public/r.txt"), "res text\n");
		write(proj.resolve("resources/public/sub/s.txt"), "sub");
		Files.write(this.dir.resolve("z64.jar"), zip64(zipOf("z64/hello.txt", "zip64 entry\n")));
		byte[] stub = "#!/bin/sh\nexec java -jar \"$0\" \"$@\"\n".getBytes(StandardCharsets.UTF_8);
		byte[] archive = zipOf("sfx/hello.txt", "behind a stub\n");
		byte[] sfx = new byte[stub.length + archive.length];
		System.arraycopy(stub, 0, sfx, 0, stub.length);
		System.arraycopy(archive, 0, sfx, stub.length, archive.length);
		Files.write(this.dir.resolve("sfx.jar"), sfx);
		Path jar = this.dir.resolve("res.jar");
		byte[] image = new byte[1024];
		for (int i = 0; i < image.length; i++) {
			image[i] = (byte) i;
		}
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			deflated(zip, "jarres/msg.txt", "from the jar\n".getBytes(StandardCharsets.UTF_8));
			stored(zip, "jarres/stored.txt", "stored entry\n".getBytes(StandardCharsets.UTF_8));
			deflated(zip, "jarres/img.bin", image);
			deflated(zip, "jarres/sp ace.txt", "space\n".getBytes(StandardCharsets.UTF_8));
			// a directory entry the way the jar tool writes one: stored, empty
			stored(zip, "jardir/", new byte[0]);
			deflated(zip, "jardir/x.txt", "x in jardir\n".getBytes(StandardCharsets.UTF_8));
			deflated(zip, "nodir/y.txt", "y\n".getBytes(StandardCharsets.UTF_8));
			deflated(zip, "public/p.txt", "public in jar\n".getBytes(StandardCharsets.UTF_8));
		}
		return write(proj.resolve("src/app/main.clj"), program.replace("%JAR%", jar.toRealPath().toString())
			.replace("%DEPS%", proj.resolve("deps.edn").toRealPath().toString()));
	}

	/** A zip archive of one deflated entry. */
	private static byte[] zipOf(String name, String text) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			deflated(zip, name, text.getBytes(StandardCharsets.UTF_8));
		}
		return out.toByteArray();
	}

	/**
	 * The archive, which has no comment, ending as one of more than 65,535 entries ends:
	 * the zip64 end record, its locator, and an end record whose fields overflow.
	 */
	private static byte[] zip64(byte[] zip) {
		int at = zip.length - 22;
		ByteBuffer end = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
		long count = end.getShort(at + 10) & 0xFFFF;
		long size = end.getInt(at + 12) & 0xFFFFFFFFL;
		long offset = end.getInt(at + 16) & 0xFFFFFFFFL;
		ByteBuffer out = ByteBuffer.allocate(at + 56 + 20 + 22).order(ByteOrder.LITTLE_ENDIAN);
		out.put(zip, 0, at);
		out.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0);
		out.putLong(count).putLong(count).putLong(size).putLong(offset);
		out.putInt(0x07064b50).putInt(0).putLong(at).putInt(1);
		out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) -1).putShort((short) -1);
		out.putInt(-1).putInt(-1).putShort((short) 0);
		return out.array();
	}

	private static void deflated(ZipOutputStream zip, String name, byte[] octets) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(octets);
		zip.closeEntry();
	}

	private static void stored(ZipOutputStream zip, String name, byte[] octets) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(octets.length);
		entry.setCompressedSize(octets.length);
		CRC32 crc = new CRC32();
		crc.update(octets);
		entry.setCrc(crc.getValue());
		zip.putNextEntry(entry);
		zip.write(octets);
		zip.closeEntry();
	}

	private String resourcesOut() throws IOException {
		String proj = this.dir.resolve("proj").toRealPath().toString();
		String jar = this.dir.resolve("res.jar").toRealPath().toString();
		return "\"{:a 1}\\n\"\n\"from the jar\\n\"\n\"file:" + proj + "/resources/conf.edn\"\n\"jar:file:" + jar
				+ "!/jarres/msg.txt\"\n\"{:a 1}\\n\"\nnil nil\n{:a 1}\n\"" + proj
				+ "/resources/conf.edn\" \"jar\"\n102\n\"jar:file:" + jar + "!/jarres/msg.txt\" \"from the jar\\n\"\n";
	}

	private String jarEntriesOut() throws IOException {
		String proj = this.dir.resolve("proj").toRealPath().toString();
		String jar = "jar:file:" + this.dir.resolve("res.jar").toRealPath() + "!/";
		return """
				"%Jpublic/p.txt" "public in jar\\n"
				"stored entry\\n" "%Jjarres/sp%20ace.txt"
				[-4 -3 -2 -1] [-4 -3 -2 -1]
				1024 1024
				"%Jjardir" "%Jjardir/" nil nil
				"file:%P/resources/public" "file:%P/resources/public/" "file:%P/src/" "file:%P/src/"
				"" ""
				"file:%P/resources/public/r.txt" nil "file:%P/resources/public/r.txt" "file:%P/resources/public/r.txt" nil "file:%P/resources/public/"
				"JAR entry not found in jar file"
				"/no/such.jar"
				"no entry name specified"
				"zip END header not found"
				102 12
				("from the jar") "from the jar\\n"
				"zip64 entry\\n" "zip64 entry\\n" "behind a stub\\n"
				"""
			.replace("%J", jar)
			.replace("%P", proj);
	}

	@Test
	void resourcesComeFromTheSourcePathOnTheInterpreterAndTheJvm() throws Exception {
		Path main = resourceProject(RESOURCES);
		assertThat(interpret(Files.readString(main), main)).isEqualTo(resourcesOut());
		assertThat(runOnJvm(Files.readString(main), main, "JioResources")).isEqualTo(resourcesOut());
	}

	@Test
	void resourcesComeFromTheSourcePathOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path main = resourceProject(RESOURCES);
		// the computed lookup reads the resources directory and the jar beside the
		// project, which the preopen covers
		Path root = this.dir.toRealPath();
		assertThat(runOnWasm(Files.readString(main), main, false, root)).isEqualTo(resourcesOut());
		assertThat(runOnWasm(Files.readString(main), main, true, root)).isEqualTo(resourcesOut());
	}

	/**
	 * {@code resource} is a part of the namespace: a program naming it only with string
	 * literals, which the lowering finds itself, carries no lookup; one computing a name
	 * carries the lookup below the directory roots, or below every root where the source
	 * path holds a jar -- the one producer of a URL read from a jar.
	 */
	@Test
	void aResourceLookupIsCarriedOnlyWhereAProgramComputesAName() throws Exception {
		Path main = resourceProject(RESOURCES);
		String literal = lowered("(ns app.main (:require [clojure.java.io :as io]))"
				+ " (prn (slurp (io/resource \"conf.edn\")) (io/resource \"jarres/msg.txt\"))", main);
		assertThat(literal).contains("(RONTOLISP::%CLOJURE-IO-URL-FOUND ")
			.doesNotContain("%CLOJURE-IO-RESOURCE")
			.doesNotContain("%CLOJURE-IO-JAR-")
			.doesNotContain("|c%clojure.java.io/resource|");
		String computed = lowered(
				"(ns app.main (:require [clojure.java.io :as io])) (prn (io/resource (str \"conf\" \".edn\")))", main);
		assertThat(computed).contains("(RONTOLISP::%CLOJURE-IO-JAR-RESOURCE ").contains("|c%clojure.java.io/resource|");
		String noJar = Clojure
			.read("(ns app.main (:require [clojure.java.io :as io])) (prn (map io/resource [\"a\"]))", null)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
		assertThat(noJar).contains("(RONTOLISP::%CLOJURE-IO-RESOURCE ").doesNotContain("%CLOJURE-IO-JAR-");
	}

	private static String lowered(String program, Path entry) {
		return SourceLanguage.CLOJURE
			.read(program, Features.INTERPRETER, entry.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem())
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	@Test
	void aJarsEntriesAreFoundAndReadWhenTheProgramRunsOnTheInterpreterAndTheJvm() throws Exception {
		Path main = resourceProject(JAR_ENTRIES);
		assertThat(interpret(Files.readString(main), main)).isEqualTo(jarEntriesOut());
		assertThat(runOnJvm(Files.readString(main), main, "JioJarEntries")).isEqualTo(jarEntriesOut());
	}

	@Test
	void aJarsEntriesAreFoundAndReadWhenTheProgramRunsOnBothWasmBackends() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no usable wasmtime on PATH");
		Path main = resourceProject(JAR_ENTRIES);
		Path root = this.dir.toRealPath();
		assertThat(runOnWasm(Files.readString(main), main, false, root)).isEqualTo(jarEntriesOut());
		assertThat(runOnWasm(Files.readString(main), main, true, root)).isEqualTo(jarEntriesOut());
	}

	/**
	 * A URL of another protocol than {@code file:}, {@code http:} and {@code https:}
	 * names nothing a read takes: reading one is refused by name (the oracle opens a
	 * connection), writing one -- an {@code https:} one too -- in the oracle's words. An
	 * {@code http:} read is {@code ClojureHttpUrlsTest}'s.
	 */
	private static final String NON_FILE_URL = """
			(require '[clojure.java.io :as io])
			(prn (try (slurp (io/as-url "ftp://example.invalid/x")) (catch UnsupportedOperationException e (ex-message e))))
			(prn (try (io/writer "https://example.invalid/y") (catch IllegalArgumentException e (ex-message e))))
			""";

	@Test
	void aUrlOfAnotherProtocolThanFileIsRefused() throws Exception {
		String out = """
				"reading the ftp: URL ftp://example.invalid/x is not built in"
				"Can not write to non-file URL <https://example.invalid/y>"
				""";
		assertThat(interpret(NON_FILE_URL, null)).isEqualTo(out);
		assertThat(runOnJvm(NON_FILE_URL, null, "JioNonFileUrl")).isEqualTo(out);
	}

	/** The program with {@code root} defined first: a fresh directory of its own. */
	private String rooted(String program, String leg) throws IOException {
		Path root = Files.createDirectories(this.dir.resolve(leg)).toRealPath();
		// built when the program runs, as a path a program computes
		return "(def root (str \"" + root + "\" \"\"))\n" + program;
	}

	private static Path write(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
		return path;
	}

	private static String interpret(String program, @Nullable Path entry) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-jio", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator.clojureProgram(SourceLanguage.CLOJURE.read(program, Features.INTERPRETER,
					entry == null ? null : entry.toString(), SourceStandards.DEFAULT, SourceLoader.fileSystem()))) {
				evaluator.eval(form);
			}
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnJvm(String program, @Nullable Path entry, String name) throws Exception {
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure")
			.compile(program, entry == null ? null : entry.toString());
		Path classes = Files.createTempDirectory(this.dir, name);
		Files.write(classes.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = classes.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
				new java.net.URL[] { classes.toUri().toURL() }, ClassLoader.getSystemClassLoader());
				ThreadStdio.Scope redirected = ThreadStdio.out(out)) {
			CliStack.call("clojure-jio", () -> {
				Method main = loader.loadClass(name).getMethod("main", String[].class);
				main.invoke(null, (Object) new String[0]);
				return null;
			});
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private String runOnWasm(String program, @Nullable Path entry, boolean component, Path preopen) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program,
				entry == null ? null : entry.toString(), true, component);
		byte[] module = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(frontend.features().names())
			.build()
			.compile(frontend.forms());
		Path path = Files.createTempFile(this.dir, "jio", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-W", "gc=y", "-W",
				"exceptions=y", "--dir", preopen.toString(), path.toString());
		assertThat(run.exitCode()).as("wasmtime exit code; stderr: %s", run.stderr()).isZero();
		return run.stdout();
	}

}
