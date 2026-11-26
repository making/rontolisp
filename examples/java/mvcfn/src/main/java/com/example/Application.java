// The bootstrap of a WebMvc.fn application on servlet-httpserver: a DispatcherServlet
// over a context that holds the handler mapping of Routing's routes.
// Spring Framework's classes are those of webmvc-fn-lite, which stand for them where the
// application is compiled from source (README.md).
package com.example;

import java.util.List;
import java.util.Optional;

import am.ik.servlet.httpserver.ServletHttpServer;

import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

public class Application {

    public static void main(String[] args) throws Exception {
        int port = Optional.ofNullable(System.getenv("PORT")).map(Integer::parseInt).orElse(8080);
        DispatcherServlet dispatcherServlet = new DispatcherServlet(applicationContext(new Routing().routes()));
        ServletHttpServer server = ServletHttpServer.builder()
            .port(port)
            .servlet("dispatcherServlet", dispatcherServlet, servlet -> servlet.urlPatterns("/"))
            .start();
        System.out.println("Listening on http://localhost:" + server.port() + "/");
    }

    static StaticWebApplicationContext applicationContext(RouterFunction<ServerResponse> routes) {
        StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
        applicationContext.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class, () -> {
            RouterFunctionMapping mapping = new RouterFunctionMapping(routes);
            mapping.setMessageConverters(List.of(new StringHttpMessageConverter(),
                    new ByteArrayHttpMessageConverter(), new TinyJsonHttpMessageConverter()));
            return mapping;
        });
        return applicationContext;
    }
}
