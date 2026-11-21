package am.ik.servlet.httpserver;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is this server's own: its start and stop, and what it answers for the parts of the
 * specification it does not have.
 */
class ServletHttpServerTest {

	public static final class Hello extends HttpServlet {

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
				if ("session".equals(asked)) {
					request.getSession();
				}
				else if ("async".equals(asked)) {
					request.startAsync();
				}
				else if ("parts".equals(asked)) {
					request.getParts();
				}
				response.getWriter().print("hello on " + Thread.currentThread().getName());
			}
			catch (Exception ex) {
				response.getWriter().print(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			}
		}

	}

	private static ServletHttpServer server() {
		return new ServletHttpServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), "");
	}

	@Test
	void registrationIsOpenUntilTheStart() throws Exception {
		try (ServletHttpServer server = server()) {
			ServletContext context = server.getServletContext();
			ServletRegistration.Dynamic hello = context.addServlet("hello", Hello.class);
			assertThat(hello.addMapping("/hello", "/again")).isEmpty();
			assertThat(hello.setInitParameter("refuse", "once")).isTrue();
			assertThat(hello.setInitParameter("refuse", "never")).isFalse();
			// A name is registered once; a pattern another servlet has maps nothing.
			assertThat(context.addServlet("hello", new Hello())).isNull();
			ServletRegistration.Dynamic other = context.addServlet("other", new Hello());
			assertThat(other.addMapping("/other", "/hello")).containsExactly("/hello");
			assertThat(other.getMappings()).isEmpty();
			assertThat(context.getServletRegistrations()).containsOnlyKeys("hello", "other");
			assertThat(context.getServletRegistration("hello").getMappings()).containsExactly("/hello", "/again");
			assertThatThrownBy(() -> other.addMapping("no-slash")).isInstanceOf(IllegalArgumentException.class);
			// What this container does not have is refused where it is asked for.
			assertThatThrownBy(() -> other.setServletSecurity(new jakarta.servlet.ServletSecurityElement()))
				.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> other.setMultipartConfig(new jakarta.servlet.MultipartConfigElement("/tmp")))
				.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(server::getPort).isInstanceOf(IllegalStateException.class);
			server.setExecutor(task -> new Thread(task, "own-thread").start());
			server.start();
			assertThatThrownBy(() -> context.addServlet("late", new Hello())).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> hello.addMapping("/late")).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);
			int port = server.getPort();
			// A servlet that refused to initialize is asked again by the next request.
			assertThat(Http.get(port, "/hello").status).isEqualTo(500);
			assertThat(Http.get(port, "/again").text()).isEqualTo("hello on own-thread");
			assertThat(Http.get(port, "/hello?ask=session").text())
				.isEqualTo("UnsupportedOperationException: sessions are not supported");
			assertThat(Http.get(port, "/hello?ask=async").text())
				.isEqualTo("IllegalStateException: asynchronous requests are not supported");
			assertThat(Http.get(port, "/hello?ask=parts").text())
				.isEqualTo("ServletException: the request is not multipart/form-data");
			// Another server on the port: the address is refused before anything is
			// initialized.
			List<String> events = new ArrayList<>();
			ServletHttpServer second = new ServletHttpServer(
					new InetSocketAddress(InetAddress.getLoopbackAddress(), port), "");
			second.getServletContext().addListener(listener(events));
			assertThatThrownBy(second::start).isInstanceOf(BindException.class);
			assertThat(events).isEmpty();
			server.stop();
			server.stop();
			assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void aStartThatFailsDestroysWhatItInitialized() {
		List<String> events = new ArrayList<>();
		ServletHttpServer server = server();
		ServletContext context = server.getServletContext();
		context.addListener(listener(events));
		context.addFilter("fine", filter(events, "fine", false)).addMappingForUrlPatterns(null, false, "/*");
		context.addFilter("refusing", filter(events, "refusing", true)).addMappingForUrlPatterns(null, false, "/*");
		assertThatThrownBy(server::start).isInstanceOf(ServletException.class).hasMessage("refusing");
		assertThat(events).containsExactly("context initialized", "init fine", "init refusing", "destroy fine",
				"context destroyed");
		assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);
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
