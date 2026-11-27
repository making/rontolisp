package am.ik.rontolisp.java;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.testsupport.HostBoundaryRuns;
import am.ik.rontolisp.testsupport.HostWasmtime;
import am.ik.rontolisp.testsupport.JavaLibraryJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code @WitImport} of the Java front end: a Java interface standing for a WIT
 * interface, checked against the WIT on every target, whose implementation
 * {@code ServiceLoader} finds -- the program's own under a JDK, on the interpreter and on
 * the JVM, the host's on a WASM target, through a binding the front end writes as Java.
 * Each WASM target here runs against a real host: wasmtime's own {@code wasi:keyvalue},
 * {@code wasi:sockets}, {@code wasi:http} and {@code wasi:cli} under {@code --component},
 * a module preloaded into wasmtime for a core module. And its opposite,
 * {@code @WitExport}: a class implementing a WIT world, a component's entry points where
 * the program has no {@code main}.
 */
class JavaWitImportTest {

	private static final Path EXAMPLE = Path.of("examples/wit/keyvalue");

	/** Where the annotation is: the library a program names it from. */
	private static final String API = "rontolisp-java-api/src/main/java";

	@TempDir
	Path dir;

	private Path write(String name, String text) throws Exception {
		Path file = this.dir.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	/**
	 * {@code examples/wit/keyvalue/PageHits.java} prints the report of the Lisp counter
	 * beside it everywhere: under a JDK, on the interpreter and on the JVM against the
	 * store of its own {@code ServiceLoader} finds, as a component against wasmtime's.
	 * The component imports the members the program calls, named in the directive the
	 * front end writes ahead of the program; a Preview 1 module refuses the interface at
	 * the WIT line, as it refuses the Lisp counter's, and a {@code --no-wasi} one imports
	 * it through the canonical ABI, refusing the store its main reaches while the module
	 * loads. The interface, its WIT and its provider are read from the example's
	 * directory, from two directories on the source path, the WIT in the second as a
	 * resource is in {@code src/main/resources}, and from a sources jar, what
	 * {@code --java-source-dep} hands over.
	 */
	@Test
	void theKeyvalueCounterPrintsTheLispCountersReportOnTheJdkAndEveryTarget() throws Exception {
		String expected = HostBoundaryRuns.cli(EXAMPLE.resolve("page-hits.lisp").toString());
		Path program = EXAMPLE.resolve("PageHits.java");
		assertThat(jdk(program)).isEqualTo(expected);
		assertThat(HostBoundaryRuns.cli("--java-source-path", API, program.toString())).isEqualTo(expected);
		Map<String, String> library = new LinkedHashMap<>();
		for (String file : List.of("Store.java", "MemoryStore.java", "META-INF/services/Store", "wit/keyvalue.wit")) {
			library.put(file, Files.readString(EXAMPLE.resolve(file)));
			write((file.endsWith(".java") ? "java/" : "resources/") + file, library.get(file));
		}
		String split = String.join(File.pathSeparator, API, this.dir.resolve("java").toString(),
				this.dir.resolve("resources").toString());
		Path alone = write("app/PageHits.java", Files.readString(program));
		assertThat(HostBoundaryRuns.cli("--java-source-path", split, alone.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(alone, Files.createDirectories(this.dir.resolve("classes")), "PageHits",
				"--java-source-path", split))
			.isEqualTo(expected);
		Path jar = JavaLibraryJar.build(this.dir, "store-sources.jar", Map.of(), entry -> false, library);
		String sourcePath = API + File.pathSeparator + jar;
		assertThat(HostBoundaryRuns.cli("--java-source-path", sourcePath, alone.toString())).isEqualTo(expected);
		// the WIT as a directive beside it names it, the version the WIT's; one of
		// another
		// root by the path from the program's directory, a jar's entry by the jar's path
		assertThat(read(program, API).get(0)).isEqualTo("(RONTOLISP:WIT-IMPORT \"wit/keyvalue.wit\" :INTERFACE "
				+ "\"wasi:keyvalue/store@0.2.0-draft\" :NAMES ((\"open\" \"Store$Wit.open\") (\"bucket-get\" "
				+ "\"Store$Wit.bucket-get\") (\"bucket-set\" \"Store$Wit.bucket-set\") (\"bucket-delete\" "
				+ "\"Store$Wit.bucket-delete\") (\"bucket-exists\" \"Store$Wit.bucket-exists\") (\"bucket-list-keys\" "
				+ "\"Store$Wit.bucket-list-keys\") (\"bucket-drop\" \"Store$Wit.bucket-drop\")) :OCTETS T)");
		assertThat(read(alone, split).get(0))
			.startsWith("(RONTOLISP:WIT-IMPORT \"../resources/wit/keyvalue.wit\" :INTERFACE ");
		assertThat(read(alone, sourcePath).get(0))
			.startsWith("(RONTOLISP:WIT-IMPORT \"" + jar.toAbsolutePath() + "!/wit/keyvalue.wit\" :INTERFACE ");
		assertThatThrownBy(() -> HostBoundaryRuns.cli("--java-source-path", split, alone.toString(), "-o",
				this.dir.resolve("p1.wasm").toString()))
			.hasMessageContaining("resources/wit/keyvalue.wit:36: 'open': the WIT type of the result does not cross "
					+ "the Preview 1 WASM import boundary");
		// a --no-wasi module imports it through the canonical ABI, for the glue to
		// answer -- a JavaScript host, which may suspend, and which main reaches while
		// the module loads
		assertThatThrownBy(() -> HostBoundaryRuns.cli("--java-source-path", split, alone.toString(), "-o",
				this.dir.resolve("reactor.wasm").toString(), "--no-wasi"))
			.hasMessageContaining("Store$Wit.open%raw imports wasi:keyvalue/store@0.2.0-draft.open, which the "
					+ "module's JavaScript host may answer by suspending")
			.hasMessageContaining("PageHits.main(String[]) -> Store$Wit.open(String)");
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		Path module = this.dir.resolve("page-hits.wasm");
		HostBoundaryRuns.cli("--java-source-path", sourcePath, alone.toString(), "-o", module.toString(),
				"--component");
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "-S", "keyvalue=y",
				module.toString());
		assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo(expected);
	}

	/**
	 * Every kind of value crosses wasmtime's own interfaces both ways: a static function,
	 * an enum, a variant whose case holds a record holding a tuple (a record of the
	 * program's), the unsigned integers as Java holds them (a {@code u8} of 200 is the
	 * byte -56), a result whose error arm is a variant and one whose arm carries nothing,
	 * each thrown or a {@code WitResult} as the method declares it, a constructor, an own
	 * handle handed to another; a stream of resources (the connections a socket accepts),
	 * a {@code stream<u8>} the program writes after the call and one it reads, a closed
	 * resource refusing a call, standard input read in a thread before its bytes are
	 * there. The WIT is trimmed to the members called, each exactly the host's.
	 */
	@Test
	void everyKindOfValueCrossesWasmtimesOwnInterfaces() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		write("sockets.wit", SOCKETS_WIT);
		write("Sockets.java", SOCKETS);
		Path sockets = write("SocketsMain.java",
				"""
						import java.io.IOException;
						import java.io.OutputStream;
						import java.nio.charset.StandardCharsets;
						import java.util.List;
						import java.util.ServiceLoader;

						import am.ik.rontolisp.wit.WitException;
						import am.ik.rontolisp.wit.WitFuture;
						import am.ik.rontolisp.wit.WitResult;
						import am.ik.rontolisp.wit.WitStream;

						public class SocketsMain {

						    public static void main(String[] args) throws IOException {
						        Sockets types = ServiceLoader.load(Sockets.class).findFirst().orElseThrow();
						        Sockets.TcpSocket socket = types.tcpSocketCreate(Sockets.IpAddressFamily.IPV4);
						        Sockets.Octets loopback = new Sockets.Octets((byte) 127, (byte) 0, (byte) 0, (byte) 1);
						        socket.bind(new Sockets.IpSocketAddress.Ipv4(new Sockets.Ipv4SocketAddress((short) 0, loopback)));
						        if (socket.getLocalAddress() instanceof WitResult.Ok<Sockets.IpSocketAddress, Sockets.ErrorCode>(
						                Sockets.IpSocketAddress.Ipv4(Sockets.Ipv4SocketAddress local))) {
						            System.out.println(socket.getAddressFamily() + " " + local.address() + " "
						                    + (Short.toUnsignedInt(local.port()) > 0));
						        }
						        System.out.println(socket.setHopLimit((byte) 0) + " " + socket.setHopLimit((byte) 200));
						        socket.setKeepAliveCount(7);
						        socket.setReceiveBufferSize(100000L);
						        System.out.println(socket.getHopLimit() + " " + socket.getKeepAliveCount() + " "
						                + (socket.getReceiveBufferSize() > 0));
						        try {
						            socket.bind(new Sockets.IpSocketAddress.Ipv4(new Sockets.Ipv4SocketAddress((short) 0, loopback)));
						        }
						        catch (WitException e) {
						            System.out.println(e.getMessage());
						        }
						        // an async func is a call that waits: a socket bound, never listening
						        Sockets.TcpSocket caller = types.tcpSocketCreate(Sockets.IpAddressFamily.IPV4);
						        Sockets.IpSocketAddress address =
						                ((WitResult.Ok<Sockets.IpSocketAddress, Sockets.ErrorCode>) socket.getLocalAddress()).value();
						        try {
						            caller.connect(address);
						        }
						        catch (WitException e) {
						            System.out.println(e.getMessage());
						        }
						        // listening: a stream of the connections, a handle a read; what is sent a
						        // stream of bytes written after the call, what is received one read
						        WitStream<Sockets.TcpSocket> accepted = socket.listen();
						        Sockets.TcpSocket client = types.tcpSocketCreate(Sockets.IpAddressFamily.IPV4);
						        client.connect(address);
						        List<Sockets.TcpSocket> peers = accepted.read(5);
						        System.out.println(peers.size());
						        OutputStream[] writer = new OutputStream[1];
						        WitFuture<Void> sent = client.send(out -> writer[0] = out);
						        try (OutputStream out = writer[0]) {
						            out.write("hello, ".getBytes(StandardCharsets.UTF_8));
						            out.write('w');
						            out.write("orld".getBytes(StandardCharsets.UTF_8));
						        }
						        System.out.println(sent.get());
						        Sockets.Received received = peers.get(0).receive();
						        System.out.println(new String(received.data().readAllBytes(), StandardCharsets.UTF_8) + " "
						                + received.data().read() + " " + received.done().get());
						        received.data().close();
						        try {
						            received.data().read();
						        }
						        catch (IOException e) {
						            System.out.println(e.getMessage());
						        }
						        accepted.close();
						        // a closed resource refuses a call; a second close does nothing
						        client.close();
						        client.close();
						        try {
						            client.getAddressFamily();
						        }
						        catch (IllegalStateException e) {
						            System.out.println(e.getMessage());
						        }
						    }
						}
						""");
		assertThat(component(sockets, "-S", "inherit-network=y")).isEqualTo("""
				IPV4 Octets[a=127, b=0, c=0, d=1] true
				Err[error=InvalidArgument[]] Ok[value=null]
				-56 7 true
				tcp-socket-bind of wasi:sockets/types@0.3.0 answered its error arm: InvalidState[]
				tcp-socket-connect of wasi:sockets/types@0.3.0 answered its error arm: ConnectionRefused[]
				1
				null
				hello, world -1 null
				stream<u8> of wasi:sockets/types@0.3.0 is closed
				tcp-socket of wasi:sockets/types@0.3.0 is closed
				""");
		write("http.wit", HTTP_WIT);
		write("Http.java", HTTP);
		Path http = write("HttpMain.java", """
				import java.util.ServiceLoader;

				import am.ik.rontolisp.wit.WitException;

				public class HttpMain {

				    public static void main(String[] args) {
				        Http types = ServiceLoader.load(Http.class).findFirst().orElseThrow();
				        Http.OutgoingRequest request = types.outgoingRequestNew(types.fieldsNew());
				        System.out.println(request.method());
				        request.setMethod(new Http.Method.Post());
				        System.out.println(request.method());
				        request.setMethod(new Http.Method.Other("PATCH"));
				        System.out.println(request.method());
				        try {
				            request.setMethod(new Http.Method.Other("bad method"));
				        }
				        catch (WitException e) {
				            System.out.println(e.getMessage());
				        }
				    }
				}
				""");
		assertThat(component(http, "-S", "http=y")).isEqualTo("""
				Get[]
				Post[]
				Other[value=PATCH]
				outgoing-request-set-method of wasi:http/types@0.2.0 answered its error arm
				""");
		// standard input as an InputStream, read in a thread (which puts the component's
		// bodies in asyncMode) before the bytes are there: the read waits where it is
		write("stdin.wit", STDIN_WIT);
		write("Stdin.java", STDIN);
		Path cat = write("Cat.java", """
				import java.io.IOException;
				import java.io.UncheckedIOException;
				import java.nio.charset.StandardCharsets;
				import java.util.ServiceLoader;

				public class Cat {

				    public static void main(String[] args) throws InterruptedException {
				        Stdin stdin = ServiceLoader.load(Stdin.class).findFirst().orElseThrow();
				        Thread reader = new Thread(() -> {
				            Stdin.Read read = stdin.readViaStream();
				            try {
				                System.out.println(new String(read.data().readAllBytes(), StandardCharsets.UTF_8));
				            }
				            catch (IOException e) {
				                throw new UncheckedIOException(e);
				            }
				            System.out.println(read.done().get());
				        });
				        reader.start();
				        reader.join();
				    }
				}
				""");
		Path module = this.dir.resolve("cat.wasm");
		HostBoundaryRuns.cli("--java-source-path", API, cat.toString(), "-o", module.toString(), "--component");
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("bash", "-c",
				"(sleep 0.5; printf 'hi\\nthere') | wasmtime run " + module);
		assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("hi\nthere\nnull\n");
	}

	/** wasmtime's standard input, as WASI 0.3 reads it. */
	private static final String STDIN_WIT = """
			package wasi:cli@0.3.0;

			interface types {
			  enum error-code {
			    io,
			    illegal-byte-sequence,
			    pipe
			  }
			}

			interface stdin {
			  use types.{error-code};

			  read-via-stream: func() -> tuple<stream<u8>, future<result<_, error-code>>>;
			}
			""";

	private static final String STDIN = """
			import java.io.InputStream;

			import am.ik.rontolisp.wit.WitFuture;
			import am.ik.rontolisp.wit.WitImport;

			@WitImport(value = "wasi:cli/stdin@0.3.0", wit = "stdin.wit")
			public interface Stdin {

			    Read readViaStream();

			    record Read(InputStream data, WitFuture<Void> done) {
			    }

			    enum ErrorCode {
			        IO, ILLEGAL_BYTE_SEQUENCE, PIPE
			    }
			}
			""";

	/**
	 * What a host answers in pieces: an {@code async func} is a method that waits for its
	 * answer, a {@code stream<T>} of values a {@code WitStream<T>} read so many at a
	 * time, a {@code stream<u8>} an {@code InputStream}, a {@code future<result<_, E>>} a
	 * {@code WitFuture<Void>} whose error arm {@code get()} throws; a {@code stream<u8>}
	 * the program hands the host is a {@code Consumer<OutputStream>} handed the writable
	 * end, which the program may write after the call. Under a JDK, on the interpreter
	 * and on the JVM the program's provider answers them; on a component the directive
	 * blocks on the async func and reads and writes the streams and the future through
	 * aliases it adds to the WIT. What the binding cannot read or write is refused at the
	 * method.
	 */
	@Test
	void aStreamAndAFutureTheHostAnswersAreReadInPieces() throws Exception {
		write("rows.wit", ROWS_WIT);
		write("Rows.java", ROWS);
		write("MemoryRows.java",
				"""
						import java.io.ByteArrayInputStream;
						import java.io.ByteArrayOutputStream;
						import java.io.InputStream;
						import java.io.OutputStream;
						import java.nio.charset.StandardCharsets;
						import java.util.List;
						import java.util.function.Consumer;

						import am.ik.rontolisp.wit.WitException;
						import am.ik.rontolisp.wit.WitFuture;
						import am.ik.rontolisp.wit.WitStream;

						public class MemoryRows implements Rows {

						    public InputStream dump(String sql) {
						        return new ByteArrayInputStream("1,one\\n2,two\\n3,three\\n".getBytes(StandardCharsets.UTF_8));
						    }

						    // the lines written by the time the future is read
						    public WitFuture<Long> load(Consumer<OutputStream> data) {
						        ByteArrayOutputStream sink = new ByteArrayOutputStream();
						        data.accept(sink);
						        return new WitFuture<>() {

						            public Long get() {
						                return sink.toString(StandardCharsets.UTF_8).lines().count();
						            }

						            public void close() {
						            }
						        };
						    }

						    public Rows.Query query(String sql) {
						        if (sql.isEmpty()) {
						            throw new WitException("query of test:rows/rows answered its error arm",
						                    new Rows.Error.Refused("no statement"));
						        }
						        List<List<String>> rows = List.of(List.of("1", "one"), List.of("2", "two"), List.of("3", "three"));
						        WitFuture<Void> done = sql.equals("broken") ? WitFuture.failed(new WitException(
						                "the rows of test:rows/rows answered its error arm", new Rows.Error.Refused("at row 4")))
						                : WitFuture.of(null);
						        return new Rows.Query(List.of(new Rows.Column("id", 4), new Rows.Column("name", 16)),
						                WitStream.of(rows.iterator()), done);
						    }

						    public long total() {
						        return 3L;
						    }
						}
						""");
		write("META-INF/services/Rows", "MemoryRows\n");
		Path main = write("RowsMain.java", """
				import java.io.InputStream;
				import java.io.OutputStream;
				import java.nio.charset.StandardCharsets;
				import java.util.List;
				import java.util.ServiceLoader;

				import am.ik.rontolisp.wit.WitException;
				import am.ik.rontolisp.wit.WitFuture;

				public class RowsMain {

				    public static void main(String[] args) throws Exception {
				        Rows host = ServiceLoader.load(Rows.class).findFirst().orElseThrow();
				        System.out.println(host.total());
				        try (InputStream dump = host.dump("select")) {
				            System.out.print(new String(dump.readAllBytes(), StandardCharsets.UTF_8));
				        }
				        // written after the call
				        OutputStream[] writer = new OutputStream[1];
				        WitFuture<Long> loaded = host.load(out -> writer[0] = out);
				        try (OutputStream out = writer[0]) {
				            out.write("4,four\\n5,".getBytes(StandardCharsets.UTF_8));
				            out.write('f');
				            out.write("ive\\n".getBytes(StandardCharsets.UTF_8));
				        }
				        System.out.println(loaded.get());
				        for (String sql : List.of("select", "broken", "")) {
				            try {
				                Rows.Query query = host.query(sql);
				                System.out.println(query.columns());
				                for (List<List<String>> chunk = query.rows().read(2); !chunk.isEmpty();
				                        chunk = query.rows().read(2)) {
				                    System.out.println(chunk);
				                }
				                query.done().get();
				                System.out.println("done");
				            }
				            catch (WitException e) {
				                System.out.println(e.getMessage() + " " + e.error());
				            }
				        }
				    }
				}
				""");
		String expected = """
				3
				1,one
				2,two
				3,three
				2
				[Column[name=id, width=4], Column[name=name, width=16]]
				[[1, one], [2, two]]
				[[3, three]]
				done
				[Column[name=id, width=4], Column[name=name, width=16]]
				[[1, one], [2, two]]
				[[3, three]]
				the rows of test:rows/rows answered its error arm Refused[value=at row 4]
				query of test:rows/rows answered its error arm Refused[value=no statement]
				""";
		assertThat(jdk(main)).isEqualTo(expected);
		assertThat(HostBoundaryRuns.cli("--java-source-path", API, main.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(main, Files.createDirectories(this.dir.resolve("classes")), "RowsMain",
				"--java-source-path", API))
			.isEqualTo(expected);
		// a component: the directive blocks on the async funcs and adds an alias for
		// each type it reads or writes, naming its built-ins in the table
		assertThat(read(main, true).get(0)).isEqualTo("(RONTOLISP:WIT-IMPORT \"rows.wit\" :INTERFACE "
				+ "\"test:rows/rows@0.1.0\" :NAMES ((\"query\" \"Rows$Wit.query\") (\"total\" \"Rows$Wit.total\") "
				+ "(\"dump\" \"Rows$Wit.dump\") (\"load\" \"Rows$Wit.load\") (\"java-stream-0-read\" "
				+ "\"Rows$Wit.java-stream-0-read\") (\"java-future-1-read\" \"Rows$Wit.java-future-1-read\") "
				+ "(\"java-future-1-drop-readable\" \"Rows$Wit.java-future-1-drop-readable\") (\"java-stream-2-new\" "
				+ "\"Rows$Wit.java-stream-2-new\") (\"java-stream-2-read\" \"Rows$Wit.java-stream-2-read\") "
				+ "(\"java-stream-2-write\" \"Rows$Wit.java-stream-2-write\") (\"java-stream-2-drop-readable\" "
				+ "\"Rows$Wit.java-stream-2-drop-readable\") (\"java-stream-2-drop-writable\" "
				+ "\"Rows$Wit.java-stream-2-drop-writable\") (\"java-future-3-read\" \"Rows$Wit.java-future-3-read\") "
				+ "(\"java-future-3-drop-readable\" \"Rows$Wit.java-future-3-drop-readable\")) :OCTETS T :BLOCKING T "
				+ ":ALIASES ((\"java-stream-0\" \"stream<list<string>>\") (\"java-future-1\" "
				+ "\"future<result<_, error>>\") (\"java-stream-2\" \"stream<u8>\") (\"java-future-3\" "
				+ "\"future<result<u64, error>>\")))");
		HostBoundaryRuns.cli("--java-source-path", API, main.toString(), "-o", this.dir.resolve("rows.wasm").toString(),
				"--component");
		write("refused/rows.wit", ROWS_WIT);
		Path refused = write("refused/Main.java", """
				public class Main {

				    public static void main(String[] args) {
				        System.out.println(Rows.class);
				    }
				}
				""");
		Map<String, String> refusals = new LinkedHashMap<>();
		refusals.put("void feed(WitStream<List<String>> rows);",
				"feed (feed of test:rows/rows@0.1.0, rows.wit:13): its parameter rows is stream<list<string>>, a "
						+ "stream the program hands the host, of values the program cannot write: the guest only "
						+ "reads a stream of other than bytes");
		refusals.put("WitFuture<Boolean> ready();",
				"ready (ready of test:rows/rows@0.1.0, rows.wit:14): its result is future<bool>, a future of a type one "
						+ "of whose values the boundary cannot tell from a future its writer dropped");
		refusals.put("void settle(WitFuture<Integer> done);",
				"settle (settle of test:rows/rows@0.1.0, rows.wit:15): its parameter done is future<u32>, a future "
						+ "the program hands the host, which the binding could write only once the call has returned, "
						+ "where a host may read it only within a later call the program waits on (wasmtime's HTTP "
						+ "types read the one a request takes within client.send)");
		refusals.put("long upload(Consumer<OutputStream> data);",
				"upload (upload of test:rows/rows@0.1.0, rows.wit:16): its parameter data is stream<u8>, a stream "
						+ "the program hands an async func, which the call waits on while the host reads what the "
						+ "program can write only after it");
		refusals.put("void batch(List<Consumer<OutputStream>> parts);",
				"batch (batch of test:rows/rows@0.1.0, rows.wit:17): its parameter parts is list<stream<u8>>, "
						+ "carrying stream<u8>, which the binding hands the host only as a parameter of its own");
		refusals.put("void flag(java.util.Optional<Boolean> v);",
				"flag (flag of test:rows/rows@0.1.0, rows.wit:22): its parameter v is option<bool>, an option of a "
						+ "type one of whose values the boundary cannot tell from none");
		refusals.put("void poke(Thing t);\n    interface Thing {\n    }",
				"Rows.Thing is thing, a resource of another interface than test:rows/rows@0.1.0, whose methods the "
						+ "Java subset does not bind yet");
		refusals.put("WitFuture<Long> load(InputStream data);",
				"load (load of test:rows/rows@0.1.0, rows.wit:21): its parameter data is stream<u8>, which the "
						+ "program writes: Consumer<OutputStream> in Java, handed the writable end, not InputStream");
		for (Map.Entry<String, String> refusal : refusals.entrySet()) {
			write("refused/Rows.java", ROWS.replace("WitFuture<Long> load(Consumer<OutputStream> data);", "")
				.replace("long total();", refusal.getKey()));
			assertThatThrownBy(() -> read(refused, false)).hasMessageContaining(refusal.getValue());
		}
	}

	/**
	 * An interface answering rows in pieces, taking bytes, and members a program cannot
	 * have.
	 */
	private static final String ROWS_WIT = """
			package test:rows@0.1.0;

			interface rows {
			  variant error {
			    refused(string)
			  }

			  record column {
			    name: string,
			    width: u32
			  }

			  feed: func(rows: stream<list<string>>);
			  ready: func() -> future<bool>;
			  settle: func(done: future<u32>);
			  upload: async func(data: stream<u8>) -> u64;
			  batch: func(parts: list<stream<u8>>);
			  query: async func(sql: string) -> result<tuple<list<column>, stream<list<string>>, future<result<_, error>>>, error>;
			  total: async func() -> u64;
			  dump: func(sql: string) -> stream<u8>;
			  load: func(data: stream<u8>) -> future<result<u64, error>>;
			  flag: func(v: option<bool>);
			  use other.{thing};
			  poke: func(t: borrow<thing>);
			}

			interface other {
			  resource thing;
			}
			""";

	/** The {@code @WitImport} interface of {@link #ROWS_WIT}. */
	private static final String ROWS = """
			import java.io.InputStream;
			import java.io.OutputStream;
			import java.util.List;
			import java.util.function.Consumer;

			import am.ik.rontolisp.wit.WitFuture;
			import am.ik.rontolisp.wit.WitImport;
			import am.ik.rontolisp.wit.WitStream;

			@WitImport(value = "test:rows/rows", wit = "rows.wit")
			public interface Rows {

			    Query query(String sql);

			    long total();

			    InputStream dump(String sql);

			    WitFuture<Long> load(Consumer<OutputStream> data);

			    record Query(List<Column> columns, WitStream<List<String>> rows, WitFuture<Void> done) {
			    }

			    record Column(String name, int width) {
			    }

			    sealed interface Error {

			        record Refused(String value) implements Error {
			        }
			    }
			}
			""";

	/**
	 * A core module's host is the provider too: the interface's functions are the
	 * module's imports, here answered by a module wasmtime preloads under the interface's
	 * name. A {@code u32} crosses as the {@code s32} of its bits, which a Java
	 * {@code int} is.
	 */
	@Test
	void aFlatInterfaceIsACoreModulesImportFromItsHost() throws Exception {
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		write("flat.wit", """
				package test:flat@0.1.0;

				interface calc {
				  add: func(a: s32, b: s32) -> s32;
				  low-bits: func(x: u32) -> u32;
				  half: func(x: f64) -> f64;
				  negate: func(b: bool) -> bool;
				}
				""");
		write("Calc.java", """
				import am.ik.rontolisp.wit.WitImport;

				@WitImport(value = "test:flat/calc", wit = "flat.wit")
				public interface Calc {

				    int add(int a, int b);

				    int lowBits(int x);

				    double half(double x);

				    boolean negate(boolean b);
				}
				""");
		Path program = write("CalcMain.java", """
				import java.util.ServiceLoader;

				public class CalcMain {

				    public static void main(String[] args) {
				        Calc calc = ServiceLoader.load(Calc.class).findFirst().orElseThrow();
				        System.out.println(calc.add(2, 40) + " " + Integer.toHexString(calc.lowBits(-1)) + " "
				                + calc.half(5.0) + " " + calc.negate(false) + " " + calc.negate(true));
				    }
				}
				""");
		Path host = write("calc.wat", """
				(module
				  (func (export "add") (param i32 i32) (result i32) (i32.add (local.get 0) (local.get 1)))
				  (func (export "lowBits") (param i32) (result i32) (i32.and (local.get 0) (i32.const 0xffff)))
				  (func (export "half") (param f64) (result f64) (f64.mul (local.get 0) (f64.const 0.5)))
				  (func (export "negate") (param i32) (result i32) (i32.eqz (local.get 0))))
				""");
		Path module = this.dir.resolve("calc.wasm");
		HostBoundaryRuns.cli("--java-source-path", API, program.toString(), "-o", module.toString());
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--preload",
				"calc=" + host, module.toString());
		assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
		assertThat(run.stdout()).isEqualTo("42 ffff 2.5 true false\n");
	}

	/**
	 * A {@code result} is a {@code WitResult} wherever the WIT has one -- a list's
	 * element, an option's value, a record's field, a variant's payload, a parameter, an
	 * answer --, an arm carrying nothing {@code Void}, a tuple arm a record of the
	 * program's; beside it an answer declared as its ok arm throws the error arm. The
	 * program prints the same under a JDK, on the interpreter and on the JVM against its
	 * own provider, and as a {@code --no-wasi} module against a JavaScript host answering
	 * alike; a component is the same binding. A declaration of another arm is refused,
	 * and so is a tuple in an error arm thrown, as no type is written for it.
	 */
	@Test
	void aResultIsAWitResultWhereverTheWitHasOne() throws Exception {
		write("results.wit", RESULTS_WIT);
		write("Checks.java", CHECKS);
		write("LocalChecks.java", LOCAL_CHECKS);
		write("META-INF/services/Checks", "LocalChecks\n");
		Path results = write("Results.java", RESULTS);
		Path main = write("ResultsMain.java", """
				public class ResultsMain {

				    public static void main(String[] args) {
				        System.out.print(Results.run());
				    }
				}
				""");
		String expected = """
				[Ok[value=3], Err[error=Empty[]], Err[error=TooLong[value=8]]]
				Optional[Ok[value=ABCD]] Optional[Err[error=TooLong[value=7]]] Optional.empty
				Report[name=wit, verdict=Ok[value=3]]
				Done[value=Ok[value=null]] Done[value=Err[error=broke]] Skipped[]
				Ok[value=Span[at=1, length=3]] Err[error=Miss[name=ab, length=2]]
				Ok[value=null] Err[error=null]
				refused TooLong[value=11]
				4 measure of test:results/checks@0.1.0 answered its error arm: Empty[] Empty[]
				""";
		assertThat(jdk(main)).isEqualTo(expected);
		assertThat(HostBoundaryRuns.cli("--java-source-path", API, main.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(main, Files.createDirectories(this.dir.resolve("classes")), "ResultsMain",
				"--java-source-path", API))
			.isEqualTo(expected);
		HostBoundaryRuns.cli("--java-source-path", API, main.toString(), "-o",
				this.dir.resolve("results-component.wasm").toString(), "--component");
		write("Checks.java", CHECKS.replace("List<WitResult<Integer, Problem>> checkAll",
				"List<WitResult<Long, Problem>> checkAll"));
		assertThatThrownBy(() -> read(main, false)).hasMessageEndingWith("Checks.java:10:36: checkAll (check-all of "
				+ "test:results/checks@0.1.0, results.wit:19): its result is list<result<u32, problem>>, which is "
				+ "List<WitResult<Integer, Checks.Problem>> in Java, not List<WitResult<Long,Problem>>");
		write("Checks.java", CHECKS.replace("WitResult<Span, Miss> locate", "Span locate"));
		assertThatThrownBy(() -> read(main, false)).hasMessageEndingWith("Checks.java:18:10: locate (locate of "
				+ "test:results/checks@0.1.0, results.wit:23): its result's error arm is tuple<string, u32>, a tuple, "
				+ "which is a record of the program's where a type is written for it, and none is written for an error "
				+ "arm thrown: the method answers a WitResult of it");
		write("Checks.java", CHECKS);
		assumeTrue(HostBoundaryRuns.nodeAvailable(), "no node on PATH");
		HostBoundaryRuns.cli("--java-source-path", API, results.toString(), "-o",
				this.dir.resolve("results.wasm").toString(), "--no-wasi", "--emit-js-glue");
		write("package.json", "{ \"type\": \"module\" }\n");
		write("host.js", RESULTS_HOST);
		assertThat(HostBoundaryRuns.node(this.dir, "host.js")).isEqualTo(expected);
	}

	/**
	 * The declarations are checked against the WIT on every target, the interpreter's
	 * included: each refusal names the Java declaration's line and the WIT member's. The
	 * version is the WIT's: an interface declaring what two drafts of
	 * {@code wasi:keyvalue} share is checked against either, and a member the drafts
	 * spell apart is refused against the other.
	 */
	@Test
	void theDeclarationsAreCheckedAgainstTheWitTheyName() throws Exception {
		write("draft/keyvalue.wit", KEYVALUE_WIT);
		write("draft2/keyvalue.wit", KEYVALUE_WIT.replace("0.2.0-draft;", "0.2.0-draft2;")
			.replace("cursor: option<u64>", "cursor: option<string>"));
		for (String draft : List.of("draft", "draft2")) {
			write("Store.java", STORE.formatted(draft, ""));
			assertThat(read(write("Main.java", MAIN), true).get(0)).startsWith("(RONTOLISP:WIT-IMPORT \"" + draft
					+ "/keyvalue.wit\" :INTERFACE \"wasi:keyvalue/store@0.2.0-" + draft + "\"");
		}
		refused("KeyResponse listKeys(Optional<Long> cursor);",
				"Store.java:12:21: listKeys (bucket-list-keys of wasi:keyvalue/store@0.2.0-draft2, draft2/keyvalue.wit:"
						+ "20): its parameter cursor is option<string>, which is Optional<String> in Java, not "
						+ "Optional<Long>");
		write("Store.java", STORE.formatted("draft", "Optional<String> list(String key);"));
		assertThatThrownBy(() -> read(write("Main.java", MAIN), false)).hasMessageEndingWith(
				"Store.java:12:26: list names no method of the resource bucket of wasi:keyvalue/store@0.2.0-"
						+ "draft, whose methods are get, set, listKeys, and close() its release");
		write("Store.java", STORE.formatted("draft", "Optional<Boolean> get(String key);")
			.replace("Optional<byte[]> get(String key);", ""));
		assertThatThrownBy(() -> read(write("Main.java", MAIN), false))
			.hasMessageContaining("get (bucket-get of wasi:keyvalue/store@0.2.0-draft, draft/keyvalue.wit:18): its "
					+ "result's ok arm is option<list<u8>>, which is Optional<byte[]> in Java, not Optional<Boolean>");
		write("Store.java", STORE.formatted("draft", "Keys listKeys(Optional<Long> cursor);")
			.replace("record KeyResponse", "record Keys"));
		assertThatThrownBy(() -> read(write("Main.java", MAIN), false)).hasMessageContaining(
				"its result's ok arm is key-response, which is Store.KeyResponse in Java, a record the "
						+ "@WitImport interface declares; it declares none of that name");
		// the WIT is a resource of the interface's package in any root, a leading /
		// naming
		// it from the top of the root; nothing climbs above a root
		write("res/kv/draft/keyvalue.wit", KEYVALUE_WIT);
		Path packaged = write("lib/main/kv/Store.java", "package kv;\n\n" + STORE.formatted("draft", ""));
		Path app = write("app/Main.java", "import kv.Store;\n\n" + MAIN);
		String roots = String.join(File.pathSeparator, API, this.dir.resolve("lib/main").toString(),
				this.dir.resolve("res").toString());
		for (String wit : List.of("draft", "/kv/draft", "../kv/draft")) {
			Files.writeString(packaged, "package kv;\n\n" + STORE.formatted(wit, ""));
			assertThat(read(app, roots).get(0)).startsWith("(RONTOLISP:WIT-IMPORT \"../res/kv/draft/keyvalue.wit\"");
		}
		Files.writeString(packaged, "package kv;\n\n" + STORE.formatted("dra", ""));
		assertThatThrownBy(() -> read(app, roots)).hasMessageEndingWith("Store.java:8:1: @WitImport: cannot read WIT "
				+ "dra/keyvalue.wit: no root of the source path holds kv/dra/keyvalue.wit");
		Path climbs = JavaLibraryJar.build(this.dir, "climbs.jar", Map.of(), entry -> false,
				Map.of("Store.java", STORE.formatted("../draft", "")));
		assertThatThrownBy(() -> read(write("top/Main.java", MAIN), API + File.pathSeparator + climbs))
			.hasMessageEndingWith("cannot read WIT ../draft/keyvalue.wit: it names a resource above the top of a root");
		write("Store.java", STORE.formatted("draft", "").replace("wit = \"draft/keyvalue.wit\"", "wit = WIT"));
		assertThatThrownBy(() -> read(write("Main.java", MAIN), false)).hasMessageEndingWith(
				"Store.java:6:1: @WitImport's wit is a string literal, which the compiler reads: the WIT names "
						+ "nothing it computes");
		write("Store.java",
				STORE.formatted("draft", "")
					.replace("public interface Store", "public abstract class Store")
					.replace("    Bucket open", "    abstract Bucket open"));
		assertThatThrownBy(() -> read(write("Main.java", MAIN), false)).hasMessageContaining(
				"@WitImport annotates an interface, whose abstract methods are the functions of the WIT interface");
	}

	/**
	 * A program with no {@code main} starts at its {@code @WitExport} class: a reactor
	 * component exporting the world, each export the static method of its name, an
	 * interface's of the member class of its name, an unsigned integer crossing as Java
	 * holds its bits, the class's static initialization run by the first call, an
	 * {@code async func} a method answering its value, which may wait. Under a JDK, on
	 * the interpreter and on the JVM the class is an ordinary one a program with a
	 * {@code main} calls, which prints what the component answers; the program with no
	 * {@code main} is refused there and on a module. The class is checked against its
	 * world on every target, the interpreter's included. On {@code --no-gc} a class of
	 * scalar values is the scalar component of the world, an uncaught exception its bare
	 * trap, an {@code async func} lifted async as on the GC one. A world of compound
	 * types crosses too: a record a member record, a variant a sealed interface, a list a
	 * {@code List}, a result a {@code WitResult} or its ok arm with a
	 * {@code WitException} thrown, in an {@code async func} as in any other.
	 */
	@Test
	void aClassImplementsTheWorldAComponentExports() throws Exception {
		write("kinds.wit", KINDS_WIT);
		Path kinds = write("Kinds.java", KINDS);
		Path main = write("KindsMain.java", KINDS_MAIN);
		String expected = "201\ntrue\n9223372036854775807\n3\nHello, Java! seen: Java\n1\n5\n1\n2.5\n";
		assertThat(jdk(main)).isEqualTo(expected);
		assertThat(HostBoundaryRuns.cli("--java-source-path", API, main.toString())).isEqualTo(expected);
		assertThat(HostBoundaryRuns.jvm(main, Files.createDirectories(this.dir.resolve("classes")), "KindsMain",
				"--java-source-path", API))
			.isEqualTo(expected);
		assertThat(read(kinds, true).getLast()).isEqualTo("(RONTOLISP:WIT-EXPORT \"kinds.wit\" :WORLD \"kinds\" :NAMES "
				+ "((\"low-byte\" \"Kinds.lowByte(byte)%wit\") (\"negate\" \"Kinds.negate(boolean)\") (\"signed-wide\" "
				+ "\"Kinds.signedWide(long)\") (\"unsigned-wide\" \"Kinds.unsignedWide(long)\") (\"greet\" "
				+ "\"Kinds.greet(String)\") (\"touch\" \"Kinds.touch()\") (\"count\" \"Kinds.count()\") (\"pause\" "
				+ "\"Kinds.pause(int)%wit\") (\"test:kinds/math@0.1.0#add\" \"Kinds.Math.add(int,int)%wit\") "
				+ "(\"test:kinds/math@0.1.0#half\" \"Kinds.Math.half(double)\") (\"test:kinds/math@0.1.0#count\" "
				+ "\"Kinds.Math.count()\")) :REACTOR T :OCTETS T :ENVELOPE T)");
		assertThatThrownBy(() -> HostBoundaryRuns.cli("--java-source-path", API, kinds.toString()))
			.hasMessageEndingWith("Kinds.java:4:14: no main method found in Kinds: its entry points are the exports of "
					+ "the @WitExport class Kinds, which a component exports, where a host calls them: compile it with "
					+ "-o out.wasm --component");
		assertThatThrownBy(() -> HostBoundaryRuns.cli("--java-source-path", API, kinds.toString(), "-o",
				this.dir.resolve("p1.wasm").toString()))
			.hasMessageEndingWith("compile it with --component, not to a module");
		String mismatch = "Kinds.java:21:24: signedWide (signed-wide of world kinds, kinds.wit:12): its parameter x is "
				+ "s64, which is long in Java, not int";
		exportRefused("Kinds.java", KINDS, "public static long signedWide(long x)",
				"public static long signedWide(int x)", mismatch);
		// a command's class is checked too, on the interpreter's target
		assertThatThrownBy(() -> read(write("CountMain.java", """
				public class CountMain {

				    public static void main(String[] args) {
				        System.out.println(Kinds.count());
				    }
				}
				"""), false)).hasMessageEndingWith(mismatch);
		exportRefused("Kinds.java", KINDS, "public static byte lowByte(byte x)", "public byte lowByte(byte x)",
				"Kinds.java:10:17: lowByte (low-byte of world kinds, kinds.wit:10) is a static method in Java, which the "
						+ "host calls with no instance");
		exportRefused("Kinds.java", KINDS, "public static class Math", "public static class Maths",
				"Kinds.java:4:14: Kinds declares no member class Math, which is the interface test:kinds/math@0.1.0 the "
						+ "world kinds exports (kinds.wit:18): a static member class whose static methods are its "
						+ "functions");
		write("Kinds.java", KINDS);
		Path module = this.dir.resolve("kinds.wasm");
		HostBoundaryRuns.cli("--java-source-path", API, kinds.toString(), "-o", module.toString(), "--component");
		// --no-gc: the scalar component of the world, the static String the start
		// function initializes, the async func lifted async (the backend has no thread
		// to sleep in)
		Path scalar = this.dir.resolve("scalar.wasm");
		write("Kinds.java", KINDS
			.replace("StringBuilder seen = new StringBuilder(\"seen:\")", "String seen = \"seen: \" + (touched - 1)")
			.replace("seen.append(' ').append(name);", "")
			.replace(KINDS.substring(KINDS.indexOf("        long start = System.nanoTime();"),
					KINDS.indexOf("    public static class Math")), "        return ms;\n    }\n\n"));
		HostBoundaryRuns.cli("--java-source-path", API, kinds.toString(), "-o", scalar.toString(), "--component",
				"--no-gc", "--emit-wit");
		assertThat(Files.readString(this.dir.resolve("scalar.wit")))
			.contains("export pause: async func(ms: u32) -> u32;", "export count: func() -> s32;");
		// java.lisp's System.exit and thread ends, which no export of this world
		// reaches, import no wasi:cli/exit (a sleep does: the refusal of a monitor
		// held, Thread's id counter's)
		Path exitless = this.dir.resolve("exitless.wasm");
		HostBoundaryRuns.cli("--java-source-path", API, kinds.toString(), "-o", exitless.toString(), "--component");
		assertThat(new String(Files.readAllBytes(exitless), StandardCharsets.ISO_8859_1)).contains("wasi:cli/stdout")
			.doesNotContain("wasi:cli/exit");
		// a world of compound types, a program of its own beside its WIT: under a JDK,
		// on the interpreter and on the JVM ordinary methods over the program's types
		write("shapes/shapes.wit", SHAPES_WIT);
		Path shapes = write("shapes/Shapes.java", SHAPES);
		Path shapesMain = write("shapes/ShapesMain.java", SHAPES_MAIN);
		String printed = """
				Point[x=2, y=1]
				[Point[x=1, y=1], Point[x=2, y=4], Point[x=3, y=9]]
				Optional[Corners[low=Point[x=1, y=1], high=Point[x=3, y=9]]]
				12.0
				a dot has no area
				Err[error=BLUE]
				a green dot called spot of 256
				Summary[count=2, total=4.0, widest=-1, initial=120, mean=2.0]
				3
				remembered -5 five
				-5 five
				""";
		assertThat(jdk(shapesMain)).isEqualTo(printed);
		assertThat(HostBoundaryRuns.cli("--java-source-path", API, shapesMain.toString())).isEqualTo(printed);
		assertThat(HostBoundaryRuns.jvm(shapesMain, Files.createDirectories(this.dir.resolve("shapes-classes")),
				"ShapesMain", "--java-source-path", API))
			.isEqualTo(printed);
		// an export of a type outside the flat set is a method of generated Java, which
		// answers a result's envelope itself; a flat one beside it stays the method
		assertThat(read(shapes, true).getLast())
			.startsWith("(RONTOLISP:WIT-EXPORT \"shapes.wit\" :WORLD \"shapes\" :NAMES "
					+ "((\"test:shapes/geometry@0.1.0#flip\" \"Shapes$Geometry$WitExport.flip(Object)\") ")
			.endsWith("(\"remember\" \"Shapes$WitExport.remember(Object)\") (\"remembered\" \"Shapes.remembered()\")) "
					+ ":REACTOR T :OCTETS T :ENVELOPE T)");
		// a named type is the member type of its name, checked against its definition
		exportRefused("shapes/Shapes.java", SHAPES, "record Point(int x, int y)", "record Point(int x, long y)",
				"Shapes.java:75:29: Geometry.Point (point of test:shapes/geometry@0.1.0): its field y is s32, which is "
						+ "int in Java, not long");
		write("shapes/Shapes.java", SHAPES);
		write("shapes/shapes.wit", SHAPES_WIT.replace("summary", "totals"));
		assertThatThrownBy(() -> read(shapes, true)).hasMessageEndingWith(
				"Shapes.java:20:27: summarize (summarize of world shapes, shapes.wit:47): its result is totals, which is "
						+ "Shapes.Totals in Java, a record the class Shapes declares; it declares none of that name");
		// what no export carries is refused at its WIT line, and so is every compound
		// type by the scalar backend, which has no value for one
		write("shapes/shapes.wit",
				SHAPES_WIT.replace("export remembered: func() -> string;", "export remembered: func() -> stream<u8>;"));
		assertThatThrownBy(() -> read(shapes, true)).hasMessageEndingWith(
				"Shapes.java:9:1: @WitExport: shapes.wit:50: export 'remembered': the WIT type of the result holds a "
						+ "stream, which an export does not carry (carried: every primitive type, and a list, an "
						+ "option, a result, a tuple, a record, a variant, an enum or flags of them)");
		write("shapes/shapes.wit", SHAPES_WIT.replace("export remembered: func() -> string;",
				"export remembered: func() -> option<bool>;"));
		assertThatThrownBy(() -> read(shapes, true)).hasMessageContaining(
				"remembered (remembered of world shapes, shapes.wit:50): its result is option<bool>, an option of a type "
						+ "one of whose values the boundary cannot tell from none");
		write("shapes/shapes.wit", SHAPES_WIT);
		assertThatThrownBy(() -> HostBoundaryRuns.cli("--java-source-path", API, shapes.toString(), "-o",
				this.dir.resolve("shapes-scalar.wasm").toString(), "--component", "--no-gc"))
			.hasMessageContaining("shapes.wit:46: export 'flip': the WIT type of p is not one --no-gc can carry");
		Path shapesModule = this.dir.resolve("shapes.wasm");
		HostBoundaryRuns.cli("--java-source-path", API, shapes.toString(), "-o", shapesModule.toString(),
				"--component");
		assumeTrue(HostWasmtime.isAvailable(), "no wasmtime " + HostWasmtime.MINIMUM_MAJOR + "+ on PATH");
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("low-byte(200)", "201");
		// a u64 is the long up to Long.MAX_VALUE, on the scalar component too
		answers.put("unsigned-wide(5)", "3");
		answers.put("greet(\"Java\")", "\"Hello, Java! seen: -1\"");
		answers.put("count()", "0");
		answers.put("test:kinds/math.count@0.1.0()", "10");
		answers.put("add(4294967295, 2)", "1");
		answers.put("half(5.0)", "2.5");
		answers.put("pause(5)", "5");
		invoked(scalar, answers);
		answers.put("greet(\"Java\")", "\"Hello, Java! seen: Java\"");
		// an async func lifts async: its call's task waits where the method sleeps
		invoked(module, answers);
		// what an export leaves uncaught is reported by the line a JDK writes, then
		// traps;
		// the scalar component has no condition to write it from: the bare trap
		HostWasmtime.ExecResult thrown = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke",
				"low-byte(0)", module.toString());
		assertThat(thrown.exitCode()).isNotZero();
		assertThat(thrown.stderr())
			.startsWith("Exception in thread \"main\" java.lang.IllegalArgumentException: no low byte of 0\n")
			.contains("unreachable");
		thrown = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke", "low-byte(0)", scalar.toString());
		assertThat(thrown.exitCode()).isNotZero();
		assertThat(thrown.stderr()).doesNotContain("Exception").contains("unreachable");
		// a long below 0 is no u64 the flat boundary carries: the call traps on both
		for (Path built : List.of(module, scalar)) {
			thrown = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke", "unsigned-wide(1)",
					built.toString());
			assertThat(thrown.exitCode()).as(built.toString()).isNotZero();
		}
		// a world of compound types: each crosses as the Java type the mapping gives
		// it, converted by generated Java around the method
		invoked(shapesModule, shapesAnswers());
	}

	/** What each export of {@link #SHAPES} answers a host. */
	private static Map<String, String> shapesAnswers() {
		Map<String, String> answers = new LinkedHashMap<>();
		answers.put("flip({x: 1, y: 2})", "{x: 2, y: 1}");
		answers.put("squares(3)", "[{x: 1, y: 1}, {x: 2, y: 4}, {x: 3, y: 9}]");
		answers.put("bounds([{x: 3, y: 1}, {x: 1, y: 9}])", "some(({x: 1, y: 1}, {x: 3, y: 9}))");
		answers.put("bounds([])", "none");
		// the ok arm answered and the error arm thrown, or the WitResult of either
		answers.put("area(circle(2.0))", "ok(12)");
		answers.put("area(dot)", "err(\"a dot has no area\")");
		answers.put("checked(rect({x: 2, y: -3}))", "ok(6)");
		answers.put("checked(dot)", "err(blue)");
		answers.put("caption({shape: dot, color: green, label: some(\"spot\"), weights: [1, 255]})",
				"\"a green dot called spot of 256\"");
		answers.put("settle(ok(7))", "ok");
		answers.put("settle(err(\"no\"))", "err");
		// a u64 in a compound answer is the long's bits
		answers.put("summarize([1.5, 2.5])",
				"{count: 2, total: 4, widest: 18446744073709551615, initial: 'x', mean: 2}");
		answers.put("octets([1, 200, 255])", "[255, 200, 1]");
		answers.put("remember((-5, \"five\"))", "remembered -5 five\n()");
		answers.put("remembered()", "\"nothing\"");
		return answers;
	}

	/** Each export of a component invoked under wasmtime, with the answer it writes. */
	private static void invoked(Path module, Map<String, String> answers) throws Exception {
		for (Map.Entry<String, String> answer : answers.entrySet()) {
			HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer("wasmtime", "run", "--invoke",
					answer.getKey(), module.toString());
			assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
			assertThat(run.stdout()).as(answer.getKey()).isEqualTo(answer.getValue() + "\n");
		}
	}

	/** The program of a class refused, a declaration of it replaced. */
	private void exportRefused(String file, String source, String declared, String replaced, String message)
			throws Exception {
		Path program = write(file, source.replace(declared, replaced));
		assertThatThrownBy(() -> read(program, true)).hasMessageEndingWith(message);
	}

	private void refused(String member, String message) throws Exception {
		write("Store.java", STORE.formatted("draft2", member));
		assertThatThrownBy(() -> read(write("Main.java", MAIN), false)).hasMessageEndingWith(message);
	}

	/** The program's forms for a component, or the interpreter's. */
	private List<String> read(Path main, boolean component) throws Exception {
		return read(main, component, List.of(API));
	}

	/** The program's forms for a component, its source path the roots given. */
	private static List<String> read(Path main, String sourcePath) throws Exception {
		return read(main, true, List.of(sourcePath.split(File.pathSeparator)));
	}

	private static List<String> read(Path main, boolean component, List<String> sourcePath) throws Exception {
		return SourceLanguage.JAVA
			.read(Files.readString(main),
					component ? Features.WASM.with(List.of(Features.COMPONENT)) : Features.INTERPRETER, main.toString(),
					SourceStandards.DEFAULT.withJavaSourcePath(sourcePath), SourceLoader.fileSystem())
			.stream()
			.map(LispVal::print)
			.toList();
	}

	/** A program compiled as a component and run under wasmtime with the flags given. */
	private String component(Path program, String... flags) throws Exception {
		Path module = this.dir.resolve(program.getFileName().toString().replace(".java", ".wasm"));
		HostBoundaryRuns.cli("--java-source-path", API, program.toString(), "-o", module.toString(), "--component");
		List<String> command = new ArrayList<>(List.of("wasmtime", "run"));
		command.addAll(List.of(flags));
		command.add(module.toString());
		HostWasmtime.ExecResult run = HostWasmtime.INSTANCE.execInContainer(command.toArray(String[]::new));
		assertThat(run.exitCode()).as("wasmtime: %s", run.stderr()).isZero();
		return run.stdout();
	}

	/**
	 * A program run under the source launcher of the JDK running the test, the
	 * annotation's library compiled by its {@code javac} on the class path: what javac
	 * and a JDK make of the same source.
	 */
	private String jdk(Path program) throws Exception {
		Path classes = Files.createDirectories(this.dir.resolve("api"));
		List<Path> sources;
		try (Stream<Path> files = Files.walk(Path.of(API))) {
			sources = files.filter(file -> file.toString().endsWith(".java")).toList();
		}
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		try (var files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			assertThat(javac
				.getTask(null, files, null, List.of("-d", classes.toString()), null,
						files.getJavaFileObjectsFromPaths(sources))
				.call()).isTrue();
		}
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				classes.toString(), program.getFileName().toString())
			.directory(java.util.Objects.requireNonNull(program.getParent()).toFile())
			.redirectErrorStream(true)
			.start();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		process.getInputStream().transferTo(out);
		assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
		assertThat(process.exitValue()).as(out.toString(StandardCharsets.UTF_8)).isZero();
		return out.toString(StandardCharsets.UTF_8);
	}

	/** A world exporting a function of each kind of value, and an interface. */
	private static final String KINDS_WIT = """
			package test:kinds@0.1.0;

			interface math {
			  add: func(a: u32, b: u32) -> u32;
			  half: func(x: f64) -> f64;
			  count: func() -> s32;
			}

			world kinds {
			  export low-byte: func(x: u8) -> u8;
			  export negate: func(b: bool) -> bool;
			  export signed-wide: func(x: s64) -> s64;
			  export unsigned-wide: func(x: u64) -> u64;
			  export greet: func(name: string) -> string;
			  export touch: func();
			  export count: func() -> s32;
			  export pause: async func(ms: u32) -> u32;
			  export math;
			}
			""";

	/** The class implementing {@link #KINDS_WIT}. */
	private static final String KINDS = """
			import am.ik.rontolisp.wit.WitExport;

			@WitExport(value = "kinds", wit = "kinds.wit")
			public class Kinds {

			    static int touched;

			    static final StringBuilder seen = new StringBuilder("seen:");

			    public static byte lowByte(byte x) {
			        if (x == 0) {
			            throw new IllegalArgumentException("no low byte of 0");
			        }
			        return (byte) (x + 1);
			    }

			    public static boolean negate(boolean b) {
			        return !b;
			    }

			    public static long signedWide(long x) {
			        return -x;
			    }

			    public static long unsignedWide(long x) {
			        return x - 2;
			    }

			    public static String greet(String name) {
			        seen.append(' ').append(name);
			        return "Hello, " + name + "! " + seen;
			    }

			    public static void touch() {
			        touched++;
			    }

			    public static int count() {
			        return touched;
			    }

			    public static int pause(int ms) throws InterruptedException {
			        long start = System.nanoTime();
			        Thread.sleep(ms);
			        return System.nanoTime() - start >= ms * 1_000_000L ? ms : -1;
			    }

			    public static class Math {

			        public static int add(int a, int b) {
			            return a + b;
			        }

			        public static double half(double x) {
			            return x / 2;
			        }

			        public static int count() {
			            return touched + 10;
			        }
			    }
			}
			""";

	/** A program calling {@link #KINDS}' methods as a host would. */
	private static final String KINDS_MAIN = """
			public class KindsMain {

			    public static void main(String[] args) throws InterruptedException {
			        System.out.println(Kinds.lowByte((byte) 200) & 0xff);
			        System.out.println(Kinds.negate(false));
			        System.out.println(Kinds.signedWide(-9223372036854775807L));
			        System.out.println(Kinds.unsignedWide(5));
			        System.out.println(Kinds.greet("Java"));
			        Kinds.touch();
			        System.out.println(Kinds.count());
			        System.out.println(Kinds.pause(5));
			        System.out.println(Kinds.Math.add(-1, 2));
			        System.out.println(Kinds.Math.half(5.0));
			    }
			}
			""";

	/**
	 * A world whose exports carry each kind of compound type: an interface's own types
	 * and the world's.
	 */
	private static final String SHAPES_WIT = """
			package test:shapes@0.1.0;

			interface geometry {
			  record point {
			    x: s32,
			    y: s32,
			  }

			  enum color {
			    red,
			    green,
			    blue,
			  }

			  variant shape {
			    circle(f64),
			    rect(point),
			    dot,
			  }

			  record tagged {
			    shape: shape,
			    color: color,
			    label: option<string>,
			    weights: list<u8>,
			  }

			  flip: func(p: point) -> point;
			  squares: func(n: u32) -> list<point>;
			  bounds: func(ps: list<point>) -> option<tuple<point, point>>;
			  area: func(s: shape) -> result<f64, string>;
			  checked: func(s: shape) -> result<f64, color>;
			  caption: func(t: tagged) -> string;
			  settle: func(r: result<u32, string>) -> result;
			}

			world shapes {
			  record summary {
			    count: u32,
			    total: f64,
			    widest: u64,
			    initial: char,
			    mean: f32,
			  }

			  export geometry;
			  export summarize: async func(areas: list<f64>) -> summary;
			  export octets: func(bytes: list<u8>) -> list<u8>;
			  export remember: func(noted: tuple<s64, string>);
			  export remembered: func() -> string;
			}
			""";

	/**
	 * The class implementing {@link #SHAPES_WIT}: the world's types its member types, the
	 * interface's those of the member class standing for it.
	 */
	private static final String SHAPES = """
			import java.util.ArrayList;
			import java.util.List;
			import java.util.Optional;

			import am.ik.rontolisp.wit.WitException;
			import am.ik.rontolisp.wit.WitExport;
			import am.ik.rontolisp.wit.WitResult;

			@WitExport(value = "shapes", wit = "shapes.wit")
			public class Shapes {

			    static String last = "nothing";

			    public record Summary(int count, double total, long widest, int initial, float mean) {
			    }

			    public record Noted(long number, String name) {
			    }

			    public static Summary summarize(List<Double> areas) throws InterruptedException {
			        Thread.sleep(1);
			        double total = 0;
			        for (double area : areas) {
			            total += area;
			        }
			        return new Summary(areas.size(), total, -1L, 'x', (float) (total / areas.size()));
			    }

			    public static byte[] octets(byte[] bytes) {
			        byte[] reversed = new byte[bytes.length];
			        for (int i = 0; i < bytes.length; i++) {
			            reversed[i] = bytes[bytes.length - 1 - i];
			        }
			        return reversed;
			    }

			    public static void remember(Noted noted) {
			        last = noted.number() + " " + noted.name();
			        System.out.println("remembered " + last);
			    }

			    public static String remembered() {
			        return last;
			    }

			    public static class Geometry {

			        public record Point(int x, int y) {
			        }

			        public enum Color {

			            RED, GREEN, BLUE

			        }

			        public sealed interface Shape {

			            record Circle(double value) implements Shape {
			            }

			            record Rect(Point value) implements Shape {
			            }

			            record Dot() implements Shape {
			            }
			        }

			        public record Tagged(Shape shape, Color color, Optional<String> label, byte[] weights) {
			        }

			        public record Corners(Point low, Point high) {
			        }

			        public static Point flip(Point p) {
			            return new Point(p.y(), p.x());
			        }

			        public static List<Point> squares(int n) {
			            List<Point> points = new ArrayList<>();
			            for (int i = 1; i <= n; i++) {
			                points.add(new Point(i, i * i));
			            }
			            return points;
			        }

			        public static Optional<Corners> bounds(List<Point> ps) {
			            if (ps.isEmpty()) {
			                return Optional.empty();
			            }
			            int lowX = Integer.MAX_VALUE;
			            int lowY = Integer.MAX_VALUE;
			            int highX = Integer.MIN_VALUE;
			            int highY = Integer.MIN_VALUE;
			            for (Point p : ps) {
			                lowX = Math.min(lowX, p.x());
			                lowY = Math.min(lowY, p.y());
			                highX = Math.max(highX, p.x());
			                highY = Math.max(highY, p.y());
			            }
			            return Optional.of(new Corners(new Point(lowX, lowY), new Point(highX, highY)));
			        }

			        // the ok arm answered, the error arm thrown
			        public static double area(Shape s) {
			            return switch (s) {
			                case Shape.Circle circle -> 3 * circle.value() * circle.value();
			                case Shape.Rect rect -> Math.abs(rect.value().x() * rect.value().y());
			                case Shape.Dot dot -> throw new WitException("a dot has no area", "a dot has no area");
			            };
			        }

			        // the result answered whole
			        public static WitResult<Double, Color> checked(Shape s) {
			            if (s instanceof Shape.Dot) {
			                return new WitResult.Err<>(Color.BLUE);
			            }
			            return new WitResult.Ok<>(area(s));
			        }

			        public static String caption(Tagged t) {
			            int weight = 0;
			            for (byte b : t.weights()) {
			                weight += b & 0xff;
			            }
			            return "a " + t.color().name().toLowerCase() + " " + t.shape().getClass().getSimpleName().toLowerCase()
			                    + t.label().map(label -> " called " + label).orElse("") + " of " + weight;
			        }

			        public static void settle(WitResult<Integer, String> r) {
			            if (r instanceof WitResult.Err<Integer, String> err) {
			                throw new WitException("unsettled: " + err.error(), null);
			            }
			        }
			    }
			}
			""";

	/** A program calling {@link #SHAPES}' methods as a host would. */
	private static final String SHAPES_MAIN = """
			import java.util.List;
			import java.util.Optional;

			import am.ik.rontolisp.wit.WitException;
			import am.ik.rontolisp.wit.WitResult;

			public class ShapesMain {

			    public static void main(String[] args) throws InterruptedException {
			        System.out.println(Shapes.Geometry.flip(new Shapes.Geometry.Point(1, 2)));
			        System.out.println(Shapes.Geometry.squares(3));
			        System.out.println(Shapes.Geometry.bounds(List.of(new Shapes.Geometry.Point(3, 1), new Shapes.Geometry.Point(1, 9))));
			        System.out.println(Shapes.Geometry.area(new Shapes.Geometry.Shape.Circle(2.0)));
			        try {
			            Shapes.Geometry.area(new Shapes.Geometry.Shape.Dot());
			        }
			        catch (WitException e) {
			            System.out.println(e.error());
			        }
			        System.out.println(Shapes.Geometry.checked(new Shapes.Geometry.Shape.Dot()));
			        System.out.println(Shapes.Geometry.caption(new Shapes.Geometry.Tagged(new Shapes.Geometry.Shape.Dot(),
			                Shapes.Geometry.Color.GREEN, Optional.of("spot"), new byte[] { 1, (byte) 255 })));
			        Shapes.Geometry.settle(new WitResult.Ok<>(7));
			        System.out.println(Shapes.summarize(List.of(1.5, 2.5)));
			        System.out.println(Shapes.octets(new byte[] { 1, 2, 3 })[0]);
			        Shapes.remember(new Shapes.Noted(-5, "five"));
			        System.out.println(Shapes.remembered());
			    }
			}
			""";

	/** An interface of a result in every place the WIT has one. */
	private static final String RESULTS_WIT = """
			package test:results@0.1.0;

			interface checks {
			  variant problem {
			    empty,
			    too-long(u32),
			  }

			  record report {
			    name: string,
			    verdict: result<u32, problem>,
			  }

			  variant step {
			    done(result<_, string>),
			    skipped,
			  }

			  check-all: func(names: list<string>) -> list<result<u32, problem>>;
			  first-long: func(names: list<string>) -> option<result<string, problem>>;
			  review: func(name: string) -> report;
			  settle: func(answers: list<result<u32, string>>) -> step;
			  locate: func(name: string) -> result<tuple<u32, u32>, tuple<string, u32>>;
			  confirm: func(ok: bool) -> result;
			  measure: func(name: string) -> result<u32, problem>;
			}
			""";

	/** {@link #RESULTS_WIT} in Java. */
	private static final String CHECKS = """
			import java.util.List;
			import java.util.Optional;

			import am.ik.rontolisp.wit.WitImport;
			import am.ik.rontolisp.wit.WitResult;

			@WitImport(value = "test:results/checks", wit = "results.wit")
			public interface Checks {

			    List<WitResult<Integer, Problem>> checkAll(List<String> names);

			    Optional<WitResult<String, Problem>> firstLong(List<String> names);

			    Report review(String name);

			    Step settle(List<WitResult<Integer, String>> answers);

			    WitResult<Span, Miss> locate(String name);

			    WitResult<Void, Void> confirm(boolean ok);

			    int measure(String name);

			    sealed interface Problem {
			        record Empty() implements Problem {
			        }

			        record TooLong(int value) implements Problem {
			        }
			    }

			    record Report(String name, WitResult<Integer, Problem> verdict) {
			    }

			    sealed interface Step {
			        record Done(WitResult<Void, String> value) implements Step {
			        }

			        record Skipped() implements Step {
			        }
			    }

			    record Span(int at, int length) {
			    }

			    record Miss(String name, int length) {
			    }
			}
			""";

	/** The program's own provider of {@link #CHECKS}. */
	private static final String LOCAL_CHECKS = """
			import java.util.ArrayList;
			import java.util.List;
			import java.util.Optional;

			import am.ik.rontolisp.wit.WitException;
			import am.ik.rontolisp.wit.WitResult;

			public class LocalChecks implements Checks {

			    public LocalChecks() {
			    }

			    static WitResult<Integer, Problem> check(String name) {
			        if (name.isEmpty()) {
			            return new WitResult.Err<>(new Problem.Empty());
			        }
			        if (name.length() > 5) {
			            return new WitResult.Err<>(new Problem.TooLong(name.length()));
			        }
			        return new WitResult.Ok<>(name.length());
			    }

			    public List<WitResult<Integer, Problem>> checkAll(List<String> names) {
			        List<WitResult<Integer, Problem>> out = new ArrayList<>();
			        for (String name : names) {
			            out.add(check(name));
			        }
			        return out;
			    }

			    public Optional<WitResult<String, Problem>> firstLong(List<String> names) {
			        for (String name : names) {
			            if (name.length() > 3) {
			                return Optional.of(switch (check(name)) {
			                    case WitResult.Ok<Integer, Problem> ok -> new WitResult.Ok<>(name.toUpperCase());
			                    case WitResult.Err<Integer, Problem>(Problem problem) -> new WitResult.Err<>(problem);
			                });
			            }
			        }
			        return Optional.empty();
			    }

			    public Report review(String name) {
			        return new Report(name, check(name));
			    }

			    public Step settle(List<WitResult<Integer, String>> answers) {
			        if (answers.isEmpty()) {
			            return new Step.Skipped();
			        }
			        for (WitResult<Integer, String> answer : answers) {
			            if (answer instanceof WitResult.Err<Integer, String> err) {
			                return new Step.Done(new WitResult.Err<>(err.error()));
			            }
			        }
			        return new Step.Done(new WitResult.Ok<>(null));
			    }

			    public WitResult<Span, Miss> locate(String name) {
			        int at = name.indexOf('-');
			        return at < 0 ? new WitResult.Err<>(new Miss(name, name.length()))
			                : new WitResult.Ok<>(new Span(at, name.length()));
			    }

			    public WitResult<Void, Void> confirm(boolean ok) {
			        return ok ? new WitResult.Ok<>(null) : new WitResult.Err<>(null);
			    }

			    public int measure(String name) {
			        WitResult<Integer, Problem> checked = check(name);
			        if (checked instanceof WitResult.Err<Integer, Problem>(Problem problem)) {
			            throw new WitException("measure of test:results/checks@0.1.0 answered its error arm: " + problem,
			                    problem);
			        }
			        return ((WitResult.Ok<Integer, Problem>) checked).value();
			    }
			}
			""";

	/** A reactor calling each member of {@link #CHECKS}. */
	private static final String RESULTS = """
			import java.util.List;
			import java.util.ServiceLoader;

			import am.ik.rontolisp.wit.WitException;
			import am.ik.rontolisp.wit.WitResult;

			public class Results {

			    public static String run() {
			        Checks checks = ServiceLoader.load(Checks.class).findFirst().orElseThrow();
			        StringBuilder out = new StringBuilder();
			        out.append(checks.checkAll(List.of("abc", "", "abcdefgh"))).append('\\n');
			        out.append(checks.firstLong(List.of("ab", "abcd"))).append(' ')
			            .append(checks.firstLong(List.of("ab", "abcdefg"))).append(' ')
			            .append(checks.firstLong(List.of("ab"))).append('\\n');
			        out.append(checks.review("wit")).append('\\n');
			        out.append(checks.settle(List.of(new WitResult.Ok<>(1), new WitResult.Ok<>(2)))).append(' ')
			            .append(checks.settle(List.of(new WitResult.Ok<>(1), new WitResult.Err<>("broke")))).append(' ')
			            .append(checks.settle(List.of())).append('\\n');
			        out.append(checks.locate("a-b")).append(' ').append(checks.locate("ab")).append('\\n');
			        out.append(checks.confirm(true)).append(' ').append(checks.confirm(false)).append('\\n');
			        switch (checks.review("toolongname").verdict()) {
			            case WitResult.Ok<Integer, Checks.Problem>(Integer length) -> out.append("length ").append(length);
			            case WitResult.Err<Integer, Checks.Problem>(Checks.Problem problem) -> out.append("refused ").append(problem);
			        }
			        out.append('\\n').append(checks.measure("four")).append(' ');
			        try {
			            checks.measure("");
			        }
			        catch (WitException e) {
			            out.append(e.getMessage()).append(' ').append(e.error());
			        }
			        return out.append('\\n').toString();
			    }
			}
			""";

	/** A JavaScript host answering {@link #RESULTS_WIT} as {@link #LOCAL_CHECKS} does. */
	private static final String RESULTS_HOST = """
			import { readFile } from "node:fs/promises";
			import { instantiate, WitError } from "./results.js";

			const check = (name) =>
			  name.length === 0 ? { tag: "err", val: { tag: "empty" } }
			  : name.length > 5 ? { tag: "err", val: { tag: "too-long", val: name.length } }
			  : { tag: "ok", val: name.length };
			const checks = {
			  "check-all": (names) => names.map(check),
			  "first-long": (names) => {
			    const name = names.find((n) => n.length > 3);
			    if (name === undefined) return undefined;
			    const c = check(name);
			    return c.tag === "ok" ? { tag: "ok", val: name.toUpperCase() } : c;
			  },
			  review: (name) => ({ name, verdict: check(name) }),
			  settle: (answers) => {
			    if (answers.length === 0) return { tag: "skipped" };
			    const err = answers.find((a) => a.tag === "err");
			    return { tag: "done", val: err ? { tag: "err", val: err.val } : { tag: "ok" } };
			  },
			  locate: (name) => {
			    const at = name.indexOf("-");
			    if (at < 0) throw new WitError([name, name.length]);
			    return [at, name.length];
			  },
			  confirm: (ok) => {
			    if (!ok) throw new WitError(undefined);
			  },
			  measure: (name) => {
			    const c = check(name);
			    if (c.tag === "err") throw new WitError(c.val);
			    return c.val;
			  },
			};
			const results = instantiate(new WebAssembly.Module(await readFile("results.wasm")),
			  { "test:results/checks@0.1.0": checks });
			process.stdout.write(await results.run());
			""";

	/** wasi:keyvalue's store, trimmed to what the declarations below name. */
	private static final String KEYVALUE_WIT = """
			package wasi:keyvalue@0.2.0-draft;

			interface store {
			  variant error {
			    no-such-store,
			    access-denied,
			    other(string)
			  }

			  record key-response {
			    keys: list<string>,
			    cursor: option<u64>
			  }

			  open: func(identifier: string) -> result<bucket, error>;

			  resource bucket {
			    get: func(key: string) -> result<option<list<u8>>, error>;
			    set: func(key: string, value: list<u8>) -> result<_, error>;
			    list-keys: func(cursor: option<u64>) -> result<key-response, error>;
			  }
			}
			""";

	/**
	 * An interface of the members both drafts spell alike, its WIT's directory and one
	 * more member given.
	 */
	private static final String STORE = """
			import java.util.List;
			import java.util.Optional;

			import am.ik.rontolisp.wit.WitImport;

			@WitImport(value = "wasi:keyvalue/store", wit = "%s/keyvalue.wit")
			public interface Store {

			    Bucket open(String identifier);

			    interface Bucket {
			        %s
			        Optional<byte[]> get(String key);

			        void set(String key, byte[] value);
			    }

			    record KeyResponse(List<String> keys, Optional<Long> cursor) {
			    }

			    sealed interface Error {

			        record NoSuchStore() implements Error {
			        }

			        record AccessDenied() implements Error {
			        }

			        record Other(String value) implements Error {
			        }
			    }
			}
			""";

	private static final String MAIN = """
			import java.util.ServiceLoader;

			public class Main {

			    public static void main(String[] args) {
			        Store.Bucket bucket = ServiceLoader.load(Store.class).findFirst().orElseThrow().open("");
			        bucket.set("k", new byte[] { 1 });
			        System.out.println(bucket.get("k").isPresent());
			    }
			}
			""";

	private static final String SOCKETS_WIT = """
			package wasi:sockets@0.3.0;

			interface types {
			  variant error-code {
			    access-denied,
			    not-supported,
			    invalid-argument,
			    out-of-memory,
			    timeout,
			    invalid-state,
			    address-not-bindable,
			    address-in-use,
			    remote-unreachable,
			    connection-refused,
			    connection-broken,
			    connection-reset,
			    connection-aborted,
			    datagram-too-large,
			    other(option<string>)
			  }

			  enum ip-address-family {
			    ipv4,
			    ipv6
			  }

			  type ipv4-address = tuple<u8, u8, u8, u8>;
			  type ipv6-address = tuple<u16, u16, u16, u16, u16, u16, u16, u16>;

			  record ipv4-socket-address {
			    port: u16,
			    address: ipv4-address
			  }

			  record ipv6-socket-address {
			    port: u16,
			    flow-info: u32,
			    address: ipv6-address,
			    scope-id: u32
			  }

			  variant ip-socket-address {
			    ipv4(ipv4-socket-address),
			    ipv6(ipv6-socket-address)
			  }

			  resource tcp-socket {
			    create: static func(address-family: ip-address-family) -> result<tcp-socket, error-code>;
			    bind: func(local-address: ip-socket-address) -> result<_, error-code>;
			    get-local-address: func() -> result<ip-socket-address, error-code>;
			    get-address-family: func() -> ip-address-family;
			    get-keep-alive-count: func() -> result<u32, error-code>;
			    set-keep-alive-count: func(value: u32) -> result<_, error-code>;
			    get-hop-limit: func() -> result<u8, error-code>;
			    set-hop-limit: func(value: u8) -> result<_, error-code>;
			    get-receive-buffer-size: func() -> result<u64, error-code>;
			    set-receive-buffer-size: func(value: u64) -> result<_, error-code>;
			    connect: async func(remote-address: ip-socket-address) -> result<_, error-code>;
			    listen: func() -> result<stream<tcp-socket>, error-code>;
			    send: func(data: stream<u8>) -> future<result<_, error-code>>;
			    receive: func() -> tuple<stream<u8>, future<result<_, error-code>>>;
			  }
			}
			""";

	private static final String SOCKETS = """
			import java.io.InputStream;
			import java.io.OutputStream;
			import java.util.Optional;
			import java.util.function.Consumer;

			import am.ik.rontolisp.wit.WitFuture;
			import am.ik.rontolisp.wit.WitImport;
			import am.ik.rontolisp.wit.WitResult;
			import am.ik.rontolisp.wit.WitStream;

			@WitImport(value = "wasi:sockets/types@0.3.0", wit = "sockets.wit")
			public interface Sockets {

			    TcpSocket tcpSocketCreate(IpAddressFamily addressFamily);

			    interface TcpSocket {
			        void bind(IpSocketAddress localAddress);
			        WitResult<IpSocketAddress, ErrorCode> getLocalAddress();
			        IpAddressFamily getAddressFamily();
			        int getKeepAliveCount();
			        void setKeepAliveCount(int value);
			        byte getHopLimit();
			        WitResult<Void, ErrorCode> setHopLimit(byte value);
			        long getReceiveBufferSize();
			        void setReceiveBufferSize(long value);
			        void connect(IpSocketAddress remoteAddress);
			        WitStream<TcpSocket> listen();
			        WitFuture<Void> send(Consumer<OutputStream> data);
			        void close();
			        Received receive();
			    }

			    record Received(InputStream data, WitFuture<Void> done) {
			    }

			    enum IpAddressFamily {
			        IPV4, IPV6
			    }

			    // the tuples, records of the program's
			    record Octets(byte a, byte b, byte c, byte d) {
			    }

			    record Segments(short a, short b, short c, short d, short e, short f, short g, short h) {
			    }

			    record Ipv4SocketAddress(short port, Octets address) {
			    }

			    record Ipv6SocketAddress(short port, int flowInfo, Segments address, int scopeId) {
			    }

			    sealed interface IpSocketAddress {
			        record Ipv4(Ipv4SocketAddress value) implements IpSocketAddress {
			        }

			        record Ipv6(Ipv6SocketAddress value) implements IpSocketAddress {
			        }
			    }

			    sealed interface ErrorCode {
			        record AccessDenied() implements ErrorCode {}
			        record NotSupported() implements ErrorCode {}
			        record InvalidArgument() implements ErrorCode {}
			        record OutOfMemory() implements ErrorCode {}
			        record Timeout() implements ErrorCode {}
			        record InvalidState() implements ErrorCode {}
			        record AddressNotBindable() implements ErrorCode {}
			        record AddressInUse() implements ErrorCode {}
			        record RemoteUnreachable() implements ErrorCode {}
			        record ConnectionRefused() implements ErrorCode {}
			        record ConnectionBroken() implements ErrorCode {}
			        record ConnectionReset() implements ErrorCode {}
			        record ConnectionAborted() implements ErrorCode {}
			        record DatagramTooLarge() implements ErrorCode {}
			        record Other(Optional<String> value) implements ErrorCode {}
			    }
			}
			""";

	private static final String HTTP_WIT = """
			package wasi:http@0.2.0;

			interface types {
			  variant method {
			    get,
			    head,
			    post,
			    put,
			    delete,
			    connect,
			    options,
			    trace,
			    patch,
			    other(string)
			  }

			  resource fields {
			    constructor();
			  }

			  type headers = fields;

			  resource outgoing-request {
			    constructor(headers: headers);

			    method: func() -> method;

			    set-method: func(method: method) -> result;
			  }
			}
			""";

	private static final String HTTP = """
			import am.ik.rontolisp.wit.WitImport;

			@WitImport(value = "wasi:http/types", wit = "http.wit")
			public interface Http {

			    Fields fieldsNew();

			    OutgoingRequest outgoingRequestNew(Fields headers);

			    interface Fields {
			    }

			    interface OutgoingRequest {

			        Method method();

			        void setMethod(Method method);
			    }

			    sealed interface Method {
			        record Get() implements Method {}
			        record Head() implements Method {}
			        record Post() implements Method {}
			        record Put() implements Method {}
			        record Delete() implements Method {}
			        record Connect() implements Method {}
			        record Options() implements Method {}
			        record Trace() implements Method {}
			        record Patch() implements Method {}
			        record Other(String value) implements Method {}
			    }
			}
			""";

}
