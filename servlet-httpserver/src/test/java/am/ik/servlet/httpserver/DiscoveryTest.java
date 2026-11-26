package am.ik.servlet.httpserver;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An application that registers nothing: its servlets, filters and listeners carry the
 * annotations of the specification and an archive's services name an initializer. The
 * three containers are given the same web application -- classes compiled here from the
 * test's resources, so that no class path but the application's holds them -- and asked
 * the same requests. This container runs it in a JVM whose class path is the
 * application's, started by a {@code main} that starts the server and nothing else.
 */
class DiscoveryTest {

	@TempDir
	static Path work;

	private static Path war;

	private static Path refused;

	private static Path archive;

	@BeforeAll
	static void build() throws Exception {
		war = work.resolve("war");
		archive = compile("discovered/archive", work.resolve("archive"));
		compile("discovered/application", war.resolve("WEB-INF/classes"), archive.toString());
		Path services = Files.createDirectories(archive.resolve("META-INF/services"));
		Files.writeString(services.resolve("jakarta.servlet.ServletContainerInitializer"),
				"packed.Initializer\npacked.Handling\n");
		// Resources of the archive, and one of the classes, which only this container has
		// on its class path.
		Files.writeString(Files.createDirectories(archive.resolve("META-INF/resources/shelf")).resolve("index.html"),
				"<h1>shelf</h1>");
		Files.writeString(archive.resolve("META-INF/resources/packed.txt"), "from the archive");
		Files.writeString(
				Files.createDirectories(war.resolve("WEB-INF/classes/META-INF/resources")).resolve("classes.txt"),
				"from the classes");
		Path packed = Files.createDirectories(war.resolve("WEB-INF/lib")).resolve("packed.jar");
		try (OutputStream out = Files.newOutputStream(packed); JarOutputStream jar = new JarOutputStream(out)) {
			// each directory an entry of its own, as the jar tool writes them
			try (Stream<Path> walk = Files.walk(archive)) {
				for (Path file : walk.filter(path -> !path.equals(archive)).sorted().toList()) {
					String name = archive.relativize(file).toString().replace('\\', '/');
					jar.putNextEntry(new JarEntry(Files.isDirectory(file) ? name + "/" : name));
					if (!Files.isDirectory(file)) {
						Files.copy(file, jar);
					}
					jar.closeEntry();
				}
			}
		}
		refused = compile("discovered/refused", work.resolve("refused"));
	}

	/**
	 * Compiles the sources of a directory of the test's resources against the API and
	 * this container.
	 */
	private static Path compile(String resources, Path classes, String... more) throws IOException, URISyntaxException {
		Path sources = Path.of(DiscoveryTest.class.getClassLoader().getResource(resources).toURI());
		String classPath = Container.location(Servlet.class) + File.pathSeparator
				+ Container.location(ServletHttpServer.class);
		for (String entry : more) {
			classPath += File.pathSeparator + entry;
		}
		Files.createDirectories(classes);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			List<Path> java = new ArrayList<>();
			for (Path file : files(sources)) {
				if (file.toString().endsWith(".java")) {
					java.add(file);
				}
			}
			assertThat(compiler
				.getTask(null, files, null, List.of("-d", classes.toString(), "-cp", classPath), null,
						files.getJavaFileObjectsFromPaths(java))
				.call()).as("javac of %s", resources).isTrue();
		}
		return classes;
	}

	private static List<Path> files(Path directory) throws IOException {
		try (Stream<Path> walk = Files.walk(directory)) {
			return walk.filter(Files::isRegularFile).sorted().toList();
		}
	}

	private static <T> T measured(Container container, T httpserver, T tomcat, T jetty) {
		return container == Container.HTTPSERVER ? httpserver : container == Container.TOMCAT ? tomcat : jetty;
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void anApplicationIsWhatItsAnnotationsAndItsInitializersDeclare(Container container) throws Exception {
		Container.Running running = container.deploy("/app", war);
		try {
			// A servlet's name is its class's unless the annotation gives one, a member
			// class's with its dollar sign; a filter's too.
			Http.Reply hello = Http.get(running.port, "/app/hello");
			assertThat(hello.text()).isEqualTo("hello name=annotated.Hello greeting=null");
			assertThat(hello.field("X-Filters")).isEqualTo("first annotated.First");
			// Its multipart configuration: parts of four octets at most.
			for (String part : new String[] { "abcd", "abcde" }) {
				assertThat(Http
					.send(running.port, "POST", "/app/hello",
							("--b\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n" + part + "\r\n--b--\r\n")
								.getBytes(StandardCharsets.US_ASCII),
							"Content-Type: multipart/form-data; boundary=b")
					.text()).isEqualTo(part.length() <= 4 ? "a=" + part : "refused");
			}
			Http.Reply inner = Http.get(running.port, "/app/inner");
			assertThat(inner.text()).isEqualTo("inner name=annotated.Outer$Inner");
			// Filters the specification gives no order: here by their names, in Tomcat
			// and
			// Jetty as the directory lists their files (Zed, Mid, Abe on ext4 here).
			if (container == Container.HTTPSERVER) {
				assertThat(inner.fields.get("x-order")).containsExactly("Abe", "Mid", "Zed");
			}
			else {
				assertThat(inner.fields.get("x-order")).containsExactlyInAnyOrder("Abe", "Mid", "Zed");
			}
			// Every element of a registration: the patterns, the parameters, the servlet
			// names and dispatcher types of a filter; the listener's two interfaces.
			Http.Reply named = Http.get(running.port, "/app/named/x");
			assertThat(named.text()).isEqualTo("named name=named greeting=hi other=x info=/x started=yes counted=yes");
			assertThat(named.field("X-Filters")).isEqualTo("first annotated.First|2nd second");
			// A class of an archive, and the initializer its services name, which finds
			// the annotated servlets registered. It asks for no class: the
			// specification hands it null, Jetty an empty set.
			assertThat(Http.get(running.port, "/app/packed").text()).isEqualTo("packed");
			assertThat(Http.get(running.port, "/app/initialized").text()).isEqualTo(
					"initialized classes=" + measured(container, "null", "null", "[]") + " named=[/named, /named/*]");
			// The listener was told before the servlet loaded on startup was initialized.
			assertThat(Http.get(running.port, "/app/events").text()).isEqualTo("context initialized, init named");
			// An initializer that asks for classes is handed those below an interface of
			// the application -- through a superclass, an interface, the JDK's -- and
			// those carrying an annotation on themselves or on a member, not the types
			// asked for. Jetty takes a subclass of an annotated class and leaves what is
			// below a type of the JDK.
			String plugins = "annotated.Plugins$ByExtended annotated.Plugins$Direct annotated.Plugins$Extended"
					+ " annotated.Plugins$Indirect annotated.Plugins$Marked ";
			assertThat(Http.get(running.port, "/app/handled").text()).isEqualTo(plugins + measured(container,
					"annotated.Plugins$MarkedField annotated.Plugins$MarkedMethod annotated.Started",
					"annotated.Plugins$MarkedField annotated.Plugins$MarkedMethod annotated.Started",
					"annotated.Plugins$MarkedBelow annotated.Plugins$MarkedField annotated.Plugins$MarkedMethod"));
			// A class whose file names an annotation it does not carry is none.
			assertThat(Http.get(running.port, "/app/reads").status).isEqualTo(404);
			// The resources of an archive under META-INF/resources, served by the default
			// servlet, a directory by its welcome file; those of the classes here alone,
			// whose class path they are on.
			assertThat(Http.get(running.port, "/app/packed.txt").text()).isEqualTo("from the archive");
			assertThat(Http.get(running.port, "/app/shelf").field("Location")).isEqualTo("/app/shelf/");
			assertThat(Http.get(running.port, "/app/shelf/").text()).isEqualTo("<h1>shelf</h1>");
			assertThat(Http.get(running.port, "/app/classes.txt").status).isEqualTo(measured(container, 200, 404, 404));
		}
		finally {
			running.stop();
		}
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void anAnnotationThatCannotMeanWhatItSaysIsRefused(Container container) throws Exception {
		// The class, what is asked, this container's refusal, what Tomcat and Jetty
		// answer: the status of the request, or that the application did not start.
		String[][] cases = {
				{ "NoServlet", "/plain", "@WebServlet is on refused.Refused$NoServlet, which is no HttpServlet", "500",
						"404" },
				{ "NoFilter", "/plain", "@WebFilter is on refused.Refused$NoFilter, which is no Filter", "not started",
						"404" },
				{ "NoListener", "/plain", "@WebListener is on refused.Refused$NoListener, which is no listener", "404",
						"404" },
				{ "OfBinding", "/plain",
						"@WebListener is on refused.Refused$OfBinding: not a listener of the context, a request or a"
								+ " session: refused.Refused$OfBinding",
						"404", "404" },
				{ "Both", "/a", "@WebServlet on refused.Refused$Both has both value and urlPatterns", "not started",
						"404" },
				{ "Unmapped", "/plain", "@WebServlet on refused.Refused$Unmapped has no URL pattern", "404", "404" },
				// Of two servlets of one name Tomcat and Jetty register one and leave the
				// other's pattern unmapped; which one is the order their scanners read
				// the directory in, so the answer is one pattern mapped and one not.
				{ "NameTaken", "/free,/taken",
						"@WebServlet on refused.Refused$NameTaken names a servlet there is: taken", "404 405",
						"404 405" },
				{ "PatternTaken", "/taken",
						"@WebServlet on refused.Refused$PatternTaken maps what another servlet has: [/taken]",
						"not started", "not started" },
				{ "FilterTaken", "/taken", "@WebFilter on refused.Refused$FilterTaken names a filter there is: taken",
						"405", "405" },
				{ "Hidden", "/hidden", "no instance of refused.Refused$Hidden could be made", "500", "500" },
				{ "Secured", "/secured", "@ServletSecurity is not supported: refused.Refused$Secured", "405", "405" } };
		if (container != Container.HTTPSERVER) {
			for (String[] refusal : cases) {
				// An application of the class alone, and of what takes its name.
				Path classes = Files.createDirectories(
						work.resolve("refused-" + container + "-" + refusal[0]).resolve("WEB-INF/classes/refused"));
				List<String> copied = new ArrayList<>(List.of("Refused", "Refused$" + refusal[0]));
				if (refusal[0].endsWith("Taken")) {
					copied.addAll(List.of("Refused$Taken", "Refused$TakenFilter"));
				}
				for (String name : copied) {
					Files.copy(refused.resolve("refused").resolve(name + ".class"), classes.resolve(name + ".class"));
				}
				Container.Running running = container.deploy("/app", classes.getParent().getParent().getParent());
				try {
					String answer = "not started";
					if (running.started) {
						List<Integer> statuses = new ArrayList<>();
						for (String path : refusal[1].split(",")) {
							statuses.add(Http.get(running.port, "/app" + path).status);
						}
						Collections.sort(statuses);
						answer = statuses.stream().map(String::valueOf).collect(Collectors.joining(" "));
					}
					assertThat(answer).as(refusal[0]).isEqualTo(measured(container, null, refusal[3], refusal[4]));
				}
				finally {
					running.stop();
				}
			}
			return;
		}
		try (URLClassLoader loader = new URLClassLoader(new URL[] { refused.toUri().toURL() },
				DiscoveryTest.class.getClassLoader())) {
			for (String[] refusal : cases) {
				WebContext context = new WebContext("", loader);
				context.addServlet("taken", new HttpServlet() {
				}).addMapping("/taken");
				context.addFilter("taken", new HttpFilter() {
				});
				Class<?> type = Class.forName("refused.Refused$" + refusal[0], false, loader);
				assertThatThrownBy(() -> Discovery.register(context, type)).as(refusal[0])
					.isInstanceOf(ServletException.class)
					.hasMessage(refusal[2]);
			}
		}
		// A class an index lists -- a name a line, a comment after a number sign -- that
		// is not there.
		Path listed = work.resolve("listed");
		Files.writeString(
				Files.createDirectories(listed.resolve("META-INF/annotations"))
					.resolve("jakarta.servlet.annotation.WebServlet"),
				"# the servlets\n\n  listed.Missing # is not there\n");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { listed.toUri().toURL() },
				DiscoveryTest.class.getClassLoader())) {
			assertThatThrownBy(() -> Discovery.discover(new WebContext("", loader), loader))
				.isInstanceOf(ServletException.class)
				.hasMessage("the class listed.Missing could not be loaded");
		}
		// A file of the class path that is no class file -- cut short -- has no
		// supertypes, as a loader would make no class of it.
		assertThat(ClassPath
			.supertypes(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65, 0, 3, 7, 0 }))
			.isEmpty();
		// An initializer that asks for classes where there is no class path: a program
		// compiled ahead of time.
		String classPath = System.getProperty("java.class.path");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { archive.toUri().toURL() },
				DiscoveryTest.class.getClassLoader())) {
			System.clearProperty("java.class.path");
			assertThatThrownBy(() -> Discovery.discover(new WebContext("", loader), loader))
				.isInstanceOf(ServletException.class)
				.hasMessage("@HandlesTypes needs the class files of a class path: packed.Handling asks for classes");
		}
		finally {
			System.setProperty("java.class.path", classPath);
		}
	}

}
