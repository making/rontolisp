package am.ik.servlet.httpserver;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is this server's own: its start and stop, and what it answers for the parts of the
 * specification it does not have.
 */
class ServletHttpServerTest {

	public static final class Hello extends HttpServlet {

		/** What the listener of a request held asynchronous heard. */
		static final List<String> held = new CopyOnWriteArrayList<>();

		static final CountDownLatch holding = new CountDownLatch(1);

		static final CountDownLatch released = new CountDownLatch(1);

		private final AtomicInteger refusals = new AtomicInteger(1);

		@Override
		public void init(ServletConfig config) throws ServletException {
			super.init(config);
			if ("once".equals(config.getInitParameter("refuse")) && this.refusals.getAndDecrement() > 0) {
				throw new ServletException("not yet");
			}
		}

		@Override
		protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			String asked = request.getParameter("ask");
			try {
				if ("async".equals(asked)) {
					request.startAsync();
				}
				else if ("hold".equals(asked)) {
					request.startAsync().addListener(new AsyncListener() {
						@Override
						public void onComplete(AsyncEvent event) {
							held.add("complete");
							released.countDown();
						}

						@Override
						public void onTimeout(AsyncEvent event) {
							held.add("timeout");
						}

						@Override
						public void onError(AsyncEvent event) {
							held.add("error " + event.getThrowable().getMessage());
						}

						@Override
						public void onStartAsync(AsyncEvent event) {
						}
					});
					holding.countDown();
					return;
				}
				else if ("parts".equals(asked)) {
					request.getParts();
				}
				else if ("trailers".equals(asked)) {
					response.setTrailerFields(Map::of);
				}
				response.getWriter().print("hello on " + Thread.currentThread().getName());
			}
			catch (Exception ex) {
				response.getWriter().print(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			}
		}

	}

	private static ServletHttpServer.Builder loopback() {
		return ServletHttpServer.builder().address(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
	}

	/**
	 * Asserts the accept backlog of the listening socket of the port is what the
	 * container asked for, as far as the system's limit lets: the JDK's default made a
	 * burst of 2,000 connections wait 2 to 3.7 s for the clients' SYN resent. Read with
	 * {@code ss}, whose Send-Q of a listening socket is its backlog; where Linux's tools
	 * are not, nothing is asserted.
	 */
	private static void assertAcceptBacklog(int port) throws Exception {
		Path somaxconn = Path.of("/proc/sys/net/core/somaxconn");
		Process ss;
		try {
			ss = new ProcessBuilder("ss", "-ltnH", "sport = :" + port).redirectErrorStream(true).start();
		}
		catch (IOException ex) {
			return;
		}
		String listing = new String(ss.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		if (ss.waitFor() != 0 || !Files.exists(somaxconn)) {
			return;
		}
		int limit = Integer.parseInt(Files.readAllLines(somaxconn).get(0).trim());
		// LISTEN <Recv-Q> <Send-Q> <local> <peer>
		String[] columns = listing.lines().findFirst().orElseThrow().trim().split("\\s+");
		assertThat(Integer.parseInt(columns[2])).isEqualTo(Math.min(1024, limit));
	}

	@Test
	void registrationIsOpenUntilTheStart() throws Exception {
		List<ServletContext> started = new ArrayList<>();
		AtomicReference<ServletHttpServer.ServletOptions> leaked = new AtomicReference<>();
		ServletHttpServer.Builder builder = loopback()
			.servlet("hello", Hello.class,
					servlet -> leaked.set(servlet.urlPatterns("/hello", "/again").initParameter("refuse", "once")))
			.executor(task -> new Thread(task, "own-thread").start())
			.onStartup(context -> {
				// The context's own registration: a name once, a pattern another servlet
				// has maps nothing.
				ServletRegistration.Dynamic hello = (ServletRegistration.Dynamic) context
					.getServletRegistration("hello");
				assertThat(hello.setInitParameter("refuse", "never")).isFalse();
				assertThat(context.addServlet("hello", new Hello())).isNull();
				ServletRegistration.Dynamic other = context.addServlet("other", new Hello());
				other.setAsyncSupported(true);
				assertThat(other.addMapping("/other", "/hello")).containsExactly("/hello");
				assertThat(other.getMappings()).isEmpty();
				assertThat(other.addMapping("/other")).isEmpty();
				assertThat(context.getServletRegistrations()).containsOnlyKeys("hello", "other");
				assertThat(hello.getMappings()).containsExactly("/hello", "/again");
				assertThatThrownBy(() -> other.addMapping("no-slash")).isInstanceOf(IllegalArgumentException.class);
				// What this container does not have is refused where it is asked for.
				assertThatThrownBy(() -> other.setServletSecurity(new jakarta.servlet.ServletSecurityElement()))
					.isInstanceOf(UnsupportedOperationException.class);
				started.add(context);
			});
		ServletHttpServer.Definition definition = builder.build();
		// What the builder is given afterwards is not the definition's, nor what an
		// options lambda left behind.
		builder.servlet("late", new Hello(), servlet -> servlet.urlPatterns("/late"));
		assertThatThrownBy(() -> leaked.get().urlPatterns("/leaked")).isInstanceOf(IllegalStateException.class)
			.hasMessage("the options of a registration are given inside its lambda");
		try (ServletHttpServer server = definition.start()) {
			ServletContext context = started.get(0);
			assertThatThrownBy(() -> context.addServlet("late", new Hello())).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> context.getServletRegistration("hello").addMapping("/late"))
				.isInstanceOf(IllegalStateException.class);
			int port = server.port();
			// A servlet that refused to initialize is asked again by the next request.
			assertThat(Http.get(port, "/hello").status).isEqualTo(500);
			assertThat(Http.get(port, "/again").text()).isEqualTo("hello on own-thread");
			assertThat(Http.get(port, "/late").status).isEqualTo(404);
			assertThat(Http.get(port, "/hello?ask=async").text()).isEqualTo(
					"IllegalStateException: a filter or the servlet the request is inside of does not support asynchronous requests");
			assertThat(Http.get(port, "/hello?ask=parts").text())
				.isEqualTo("ServletException: the request is not multipart/form-data");
			assertThat(Http.get(port, "/hello?ask=trailers").text())
				.isEqualTo("IllegalStateException: trailer fields are not supported");
			// Another server on the port: the address is refused before anything is
			// initialized.
			List<String> events = new ArrayList<>();
			assertThatThrownBy(() -> ServletHttpServer.builder()
				.address(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
				.listener(listener(events))
				.start()).isInstanceOf(BindException.class);
			assertThat(events).isEmpty();
			// An asynchronous request in hand at the stop ends as one that failed.
			ExecutorService client = Executors.newSingleThreadExecutor();
			try {
				client.submit(() -> Http.get(port, "/other?ask=hold"));
				assertThat(Hello.holding.await(10, TimeUnit.SECONDS)).isTrue();
				server.stop();
				assertThat(Hello.released.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(Hello.held).containsExactly("error the server stopped", "complete");
			}
			finally {
				client.shutdownNow();
			}
			server.stop();
		}
		// The definition starts a server again: its servlet's class made anew, which
		// refuses once more.
		try (ServletHttpServer again = definition.start()) {
			assertThat(Http.get(again.port(), "/hello").status).isEqualTo(500);
			assertThat(Http.get(again.port(), "/hello").text()).isEqualTo("hello on own-thread");
			assertThat(started).hasSize(2);
		}
		// A pattern two servlets map fails the start.
		assertThatThrownBy(() -> loopback().servlet("a", new Hello(), servlet -> servlet.urlPatterns("/x"))
			.servlet("b", new Hello(), servlet -> servlet.urlPatterns("/y", "/x"))
			.start()).isInstanceOf(ServletException.class)
			.hasMessage("the servlet b maps what another servlet has: [/x]");
	}

	/**
	 * A store that copies a session, as one outside the process does, and keeps no value
	 * but an Integer. A provider of the service: every instance keeps in one map.
	 */
	public static final class CopyingStore implements SessionStore {

		static final Map<String, SessionData> kept = new ConcurrentHashMap<>();

		static SessionData copy(SessionData session) {
			if (session == null) {
				return null;
			}
			SessionData copy = new SessionData(session.getId(), session.getCreationTime(), session.getAccessedTime(),
					session.getLastAccessedTime(), session.getMaxInactiveInterval(), session.isFresh());
			copy.getAttributes().putAll(session.getAttributes());
			return copy;
		}

		@Override
		public SessionData load(String id) {
			return copy(kept.get(id));
		}

		@Override
		public void save(SessionData session) {
			for (Object value : session.getAttributes().values()) {
				if (!(value instanceof Integer)) {
					throw new IllegalArgumentException("not an Integer: " + value);
				}
			}
			kept.put(session.getId(), copy(session));
		}

		@Override
		public void delete(String id) {
			kept.remove(id);
		}

		@Override
		public Collection<SessionData> expired(long now) {
			return kept.values().stream().filter(session -> session.isExpired(now)).toList();
		}

	}

	@Test
	void sessionsAreKeptInTheStoreTheServerIsGivenOrTheOneItsLoaderNames(@TempDir Path services) throws Exception {
		Map<String, SessionData> kept = CopyingStore.kept;
		List<String> events = new CopyOnWriteArrayList<>();
		// The store a provider of the context's class loader names, with no call.
		Path named = services.resolve("META-INF/services/" + SessionStore.class.getName());
		Files.createDirectories(named.getParent());
		Files.writeString(named, "# the test's\n" + CopyingStore.class.getName() + "\n");
		// What the builder refuses where it is given.
		assertThatThrownBy(
				() -> loopback().sessions(sessions -> sessions.cookie(cookie -> cookie.attribute("Same Site", ""))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> loopback().sessions(sessions -> sessions.trackingModes(SessionTrackingMode.SSL)))
			.isInstanceOf(IllegalArgumentException.class);
		ServletHttpServer.Definition definition = loopback().listener(new HttpSessionListener() {
			@Override
			public void sessionCreated(HttpSessionEvent event) {
				events.add("created");
			}

			@Override
			public void sessionDestroyed(HttpSessionEvent event) {
				events.add("destroyed n=" + event.getSession().getAttribute("n"));
			}
		}).servlet("count", (request, response) -> {
			response.setHeader("X-Virtual", Boolean.toString(Thread.currentThread().isVirtual()));
			HttpSession session = request.getSession();
			Integer n = (Integer) session.getAttribute("n");
			session.setAttribute("n", n == null ? 1 : n + 1);
			if (request.getParameter("keep") != null) {
				// A value the store cannot keep leaves the session as it was.
				try {
					session.setAttribute("n", request.getParameter("keep"));
				}
				catch (IllegalArgumentException ex) {
					response.getWriter().print(ex.getMessage() + ", n=");
				}
			}
			response.getWriter().print(session.getAttribute("n"));
		}, servlet -> servlet.urlPatterns("/count"))
			.sessions(sessions -> sessions.timeout(Duration.ofMinutes(2))
				.cookie(cookie -> cookie.name("SID")
					.httpOnly(false)
					.maxAge(Duration.ofMinutes(1))
					.attribute("SameSite", "Strict")))
			.build();
		// The application's loader is the one of the thread that starts it.
		Thread thread = Thread.currentThread();
		ClassLoader before = thread.getContextClassLoader();
		URLClassLoader loader = new URLClassLoader(new URL[] { services.toUri().toURL() }, before);
		thread.setContextClassLoader(loader);
		ServletHttpServer started;
		try {
			started = definition.start();
		}
		finally {
			thread.setContextClassLoader(before);
		}
		try (loader; ServletHttpServer server = started) {
			int port = server.port();
			// Each request reads the session back from the store.
			Http.Reply first = Http.get(port, "/count");
			String id = first.field("Set-Cookie").substring(4, 36);
			assertThat(first.field("Set-Cookie")).isEqualTo("SID=" + id + "; Max-Age=60; Path=/; SameSite=Strict");
			assertThat(first.text()).isEqualTo("1");
			// Given no executor, the server runs a request on a virtual thread of its
			// own.
			assertThat(first.field("X-Virtual")).isEqualTo("true");
			assertAcceptBacklog(port);
			assertThat(Http.get(port, "/count", "Cookie: SID=" + id).text()).isEqualTo("2");
			assertThat(Http.get(port, "/count?keep=many", "Cookie: SID=" + id).text())
				.isEqualTo("not an Integer: many, n=3");
			assertThat(kept.get(id).getAttributes()).containsExactly(Map.entry("n", 3));
			assertThat(kept.get(id).getMaxInactiveInterval()).isEqualTo(120);
			// A session whose interval passed with no request is invalidated where it is
			// asked for, and the request makes another.
			SessionData idle = new SessionData("IDLE", 0, 1_000, 0, 1, false);
			idle.getAttributes().put("n", 7);
			kept.put("IDLE", idle);
			Http.Reply expired = Http.get(port, "/count", "Cookie: SID=IDLE");
			assertThat(expired.text()).isEqualTo("1");
			assertThat(expired.field("Set-Cookie")).doesNotContain("IDLE");
			assertThat(kept).doesNotContainKey("IDLE").hasSize(2);
		}
		// The sessions another store keeps outlive the server.
		assertThat(events).containsExactly("created", "destroyed n=7", "created");
		assertThat(kept).hasSize(2);
	}

	@Test
	void anHttpsServerWithItsLogItsFormLimitAndADefaultErrorPage(@TempDir Path work) throws Exception {
		// a key of its own, which the client trusts
		Path keys = work.resolve("keys.p12");
		Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
				"-genkeypair", "-keystore", keys.toString(), "-storetype", "PKCS12", "-storepass", "secret", "-alias",
				"server", "-keyalg", "EC", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1")
			.redirectErrorStream(true)
			.start();
		String told = new String(keytool.getInputStream().readAllBytes());
		assertThat(keytool.waitFor()).as(told).isZero();
		KeyStore store = KeyStore.getInstance(keys.toFile(), "secret".toCharArray());
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(store, "secret".toCharArray());
		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(store);
		SSLContext tls = SSLContext.getInstance("TLS");
		tls.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
		List<String> logged = new CopyOnWriteArrayList<>();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch leave = new CountDownLatch(1);
		AtomicReference<jakarta.servlet.AsyncContext> held = new AtomicReference<>();
		CountDownLatch holding = new CountDownLatch(1);
		// one thread, which an asynchronous request in hand does not hold
		ExecutorService runs = Executors.newSingleThreadExecutor();
		ServletHttpServer server = ServletHttpServer.builder()
			.address(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
			.https(tls)
			.log((message, thrown) -> logged.add(message + (thrown == null ? "" : " " + thrown.getMessage())))
			.maxFormSize(8)
			.errorPage("/sorry")
			.executor(runs)
			.servlet("secure", (request, response) -> {
				request.getSession();
				response.getWriter()
					.print(request.getScheme() + " " + request.isSecure() + " " + request.getServerPort() + " "
							+ request.getRequestURL() + " " + request.getServletConnection().isSecure());
			}, servlet -> servlet.urlPatterns("/secure"))
			.servlet("form", (request, response) -> response.getWriter().print(request.getParameter("a")),
					servlet -> servlet.urlPatterns("/form"))
			.servlet("held", (request, response) -> {
				jakarta.servlet.AsyncContext async = request.startAsync();
				async.setTimeout(0);
				held.set(async);
				holding.countDown();
			}, servlet -> servlet.urlPatterns("/held").asyncSupported())
			.servlet("slow", (request, response) -> {
				entered.countDown();
				try {
					leave.await();
				}
				catch (InterruptedException ex) {
					throw new ServletException(ex);
				}
				response.getWriter().print("slow");
			}, servlet -> servlet.urlPatterns("/slow"))
			.servlet("sorry",
					(request, response) -> response.getWriter()
						.print("sorry: " + request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE)),
					servlet -> servlet.urlPatterns("/sorry"))
			.onShutdown(context -> logged.add("destroyed"))
			.start();
		// A JDK's server sends a response of more than one write at once.
		assertThat(System.getProperty("sun.net.httpserver.nodelay")).isEqualTo("true");
		assertAcceptBacklog(server.port());
		HttpClient client = HttpClient.newBuilder().sslContext(tls).build();
		String base = "https://localhost:" + server.port();
		try {
			// An asynchronous request in hand holds no thread: the executor's one thread
			// serves the others meanwhile, and the thread that completes it answers it.
			CompletableFuture<HttpResponse<String>> later = client.sendAsync(
					HttpRequest.newBuilder(URI.create(base + "/held")).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
			HttpResponse<String> secure = client.send(
					HttpRequest.newBuilder(URI.create(base + "/secure")).timeout(Duration.ofSeconds(10)).build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(secure.body())
				.isEqualTo("https true " + server.port() + " https://localhost:" + server.port() + "/secure true");
			// a session begun over HTTPS is not sent over HTTP
			assertThat(secure.headers().firstValue("Set-Cookie").orElseThrow()).endsWith("; HttpOnly; Path=/; Secure");
			// the default error page answers what no other page does, the form past its
			// limit an IllegalStateException, logged where the builder said
			HttpResponse<String> form = client.send(HttpRequest.newBuilder(URI.create(base + "/form"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("a=123456789"))
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(form.statusCode() + " " + form.body()).isEqualTo("500 sorry: 500");
			assertThat(client
				.send(HttpRequest.newBuilder(URI.create(base + "/none")).build(), HttpResponse.BodyHandlers.ofString())
				.body()).isEqualTo("sorry: 404");
			assertThat(logged).containsExactly("a request failed the form is longer than 8 bytes");
			held.get().getResponse().getWriter().print("completed by the test's thread");
			held.get().complete();
			assertThat(later.get(10, TimeUnit.SECONDS).body()).isEqualTo("completed by the test's thread");
			// A stop waits for a request in hand, on an executor of the caller's too,
			// before the application is destroyed.
			CompletableFuture<HttpResponse<String>> slow = client.sendAsync(
					HttpRequest.newBuilder(URI.create(base + "/slow")).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<Void> stopped = CompletableFuture.runAsync(() -> server.stop(Duration.ofSeconds(30)));
			Thread.sleep(200);
			assertThat(stopped).isNotDone();
			leave.countDown();
			stopped.get(30, TimeUnit.SECONDS);
			assertThat(slow.get(30, TimeUnit.SECONDS).body()).isEqualTo("slow");
			assertThat(logged).endsWith("destroyed");
		}
		finally {
			leave.countDown();
			server.stop();
			runs.shutdownNow();
		}
	}

	@Test
	void aStartThatFailsDestroysWhatItInitialized() {
		List<String> events = new ArrayList<>();
		ServletHttpServer.Definition definition = loopback().listener(listener(events))
			.filter("fine", filter(events, "fine", false), filter -> filter.urlPatterns("/*"))
			.filter("refusing", filter(events, "refusing", true), filter -> filter.urlPatterns("/*"))
			.build();
		List<String> once = List.of("context initialized", "init fine", "init refusing", "destroy fine",
				"context destroyed");
		assertThatThrownBy(definition::start).isInstanceOf(ServletException.class).hasMessage("refusing");
		assertThat(events).isEqualTo(once);
		// Each start is a server of its own: the definition fails the same way again.
		assertThatThrownBy(definition::start).isInstanceOf(ServletException.class).hasMessage("refusing");
		assertThat(events.subList(once.size(), events.size())).isEqualTo(once);
	}

	private static ServletContextListener listener(List<String> events) {
		return new ServletContextListener() {
			@Override
			public void contextInitialized(ServletContextEvent event) {
				events.add("context initialized");
			}

			@Override
			public void contextDestroyed(ServletContextEvent event) {
				events.add("context destroyed");
			}
		};
	}

	private static Filter filter(List<String> events, String name, boolean refuses) {
		return new Filter() {
			@Override
			public void init(FilterConfig config) throws ServletException {
				events.add("init " + name);
				if (refuses) {
					throw new ServletException(name);
				}
			}

			@Override
			public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
					throws IOException, ServletException {
				chain.doFilter(request, response);
			}

			@Override
			public void destroy() {
				events.add("destroy " + name);
			}
		};
	}

}
