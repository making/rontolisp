package conformance;

import java.util.List;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

/**
 * The application on embedded Tomcat: at the root with every converter named, and at
 * /ctx under a path prefix with a context that holds the routes alone. The same source
 * is compiled against Spring Framework and against this module.
 */
public class Main {

	public static void main(String[] args) throws Exception {
		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(args[0]);
		tomcat.getConnector().setPort(0);
		Context root = tomcat.addContext("", args[0]);
		Tomcat.addServlet(root, "dispatcherServlet", new DispatcherServlet(named(Routes.routes()))).addMapping("/");
		Context prefixed = tomcat.addContext("/ctx", args[0]);
		Tomcat.addServlet(prefixed, "dispatcherServlet", new DispatcherServlet(detected(Routes.routes())))
			.addMapping("/sub/*");
		tomcat.start();
		System.out.println("listening " + tomcat.getConnector().getLocalPort());
		tomcat.getServer().await();
	}

	static StaticWebApplicationContext named(RouterFunction<ServerResponse> routes) {
		StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
		applicationContext.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class, () -> {
			RouterFunctionMapping mapping = new RouterFunctionMapping(routes);
			mapping.setMessageConverters(List.of(new StringHttpMessageConverter(), new ByteArrayHttpMessageConverter(),
					new JacksonJsonHttpMessageConverter(), new OwnConverter()));
			return mapping;
		});
		return applicationContext;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static StaticWebApplicationContext detected(RouterFunction<ServerResponse> routes) {
		StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
		applicationContext.registerBean("routes", RouterFunction.class, () -> (RouterFunction) routes);
		return applicationContext;
	}

}
