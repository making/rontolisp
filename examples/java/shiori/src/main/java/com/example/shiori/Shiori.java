// Shiori (栞) -- a reading journal: a bookshelf, reading progress, quotes and notes, tags,
// and a dashboard that charts the books finished per month. The Java version of
// examples/db/shiori, on
//
//   jdbc-postgresql   the PostgreSQL wire protocol behind JDBC; every value a parameter
//   jmustache         the HTML: web/templates/*.mustache, a layout each page inherits
//                     and a piece per part a page shares
//   webmvc-fn-lite    the routes, as Spring MVC's functional endpoints, on
//                     servlet-httpserver
//   YAVI              every form read into a typed value by a schema
//   tiny-json         VCAP_SERVICES, and the JSON a page's script is handed
//   htmx 4            in the browser (from a CDN): page navigation, incremental search
//                     and in-place updates
//
// htmx is an enhancement, not a requirement. Every form posts and every link navigates
// without JavaScript; when the HX-Request header says htmx sent the request, the same
// route answers with a fragment -- a single piece -- or with an HX-Location instead of a
// 303. See README.md.
//
// It needs a server, and reads where to find it out of DATABASE_URL, or on Cloud
// Foundry out of the credentials' uri of a service tagged postgresql in VCAP_SERVICES.
// The first start makes the schema and shelves eighteen books.
//
// A package a feature (book, note, home), each with its domain records, their schemas
// and its repository, and a web package of its handlers and the view models its
// templates read; web holds what every page shares, jdbc the connections. This package
// starts the application and maps the URLs (ShioriRouter).
package com.example.shiori;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import am.ik.servlet.httpserver.ServletHttpServer;
import com.example.shiori.book.BookRepository;
import com.example.shiori.jdbc.Database;
import com.example.shiori.note.NoteRepository;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

// The application starts with its servlet context, wherever that is: the container main
// starts, which is given this listener, or another one the classes are deployed to as a
// WAR, which finds it by its annotation.
@WebListener
public class Shiori implements ServletContextListener {

    public static void main(String[] args) throws Exception {
        int port = Optional.ofNullable(System.getenv("PORT")).map(Integer::parseInt).orElse(8080);
        ServletHttpServer server = ServletHttpServer.builder().port(port).listener(new Shiori()).start();
        System.out.println("Listening on http://localhost:" + server.port() + "/");
    }

    // The database and its schema first, then the routes behind a DispatcherServlet.
    @Override
    public void contextInitialized(ServletContextEvent event) {
        Database database = Database.of(DatabaseUrl.fromEnvironment());
        try {
            Schema.prepare(database);
        }
        catch (SQLException ex) {
            throw new IllegalStateException("the schema could not be made: " + ex, ex);
        }
        ShioriRouter router = new ShioriRouter(new BookRepository(database), new NoteRepository(database));
        DispatcherServlet dispatcherServlet = new DispatcherServlet(applicationContext(router.routes()));
        event.getServletContext().addServlet("dispatcherServlet", dispatcherServlet).addMapping("/");
    }

    // Every body is text: the pages, the pieces of them htmx swaps in, the assets.
    private static StaticWebApplicationContext applicationContext(RouterFunction<ServerResponse> routes) {
        StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
        applicationContext.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class, () -> {
            RouterFunctionMapping mapping = new RouterFunctionMapping(routes);
            mapping.setMessageConverters(List.of(new StringHttpMessageConverter()));
            return mapping;
        });
        return applicationContext;
    }

}
