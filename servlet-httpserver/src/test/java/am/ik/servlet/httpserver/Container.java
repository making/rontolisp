package am.ik.servlet.httpserver;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.function.Consumer;

import jakarta.servlet.ServletContext;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * The containers one application is asked in: this module's, and the two it is measured
 * against where the specification leaves room.
 */
enum Container {

	HTTPSERVER {
		@Override
		Running start(String contextPath, Consumer<ServletContext> application) throws Exception {
			ServletHttpServer server = new ServletHttpServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
					contextPath);
			application.accept(server.getServletContext());
			server.start();
			return new Running(server.getPort(), server::stop);
		}
	},

	TOMCAT {
		@Override
		Running start(String contextPath, Consumer<ServletContext> application) throws Exception {
			File base = Files.createTempDirectory("servlet-httpserver-tomcat").toFile();
			Tomcat tomcat = new Tomcat();
			tomcat.setBaseDir(base.getAbsolutePath());
			tomcat.setHostname("127.0.0.1");
			tomcat.setPort(0);
			tomcat.getConnector().setProperty("address", "127.0.0.1");
			Context context = tomcat.addContext(contextPath, base.getAbsolutePath());
			context.addServletContainerInitializer((classes, servletContext) -> application.accept(servletContext),
					null);
			tomcat.start();
			return new Running(tomcat.getConnector().getLocalPort(), () -> {
				tomcat.stop();
				tomcat.destroy();
			});
		}
	},

	JETTY {
		@Override
		Running start(String contextPath, Consumer<ServletContext> application) throws Exception {
			Server server = new Server();
			ServerConnector connector = new ServerConnector(server);
			connector.setHost("127.0.0.1");
			connector.setPort(0);
			server.addConnector(connector);
			ServletContextHandler context = new ServletContextHandler(contextPath.isEmpty() ? "/" : contextPath);
			context.addServletContainerInitializer((classes, servletContext) -> application.accept(servletContext));
			server.setHandler(context);
			server.start();
			return new Running(connector.getLocalPort(), server::stop);
		}
	};

	abstract Running start(String contextPath, Consumer<ServletContext> application) throws Exception;

	interface Stop {

		void stop() throws Exception;

	}

	/** A started container: its port, and how it is stopped. */
	static final class Running {

		final int port;

		private final Stop stop;

		Running(int port, Stop stop) {
			this.port = port;
			this.stop = stop;
		}

		void stop() throws Exception {
			this.stop.stop();
		}

	}

}
