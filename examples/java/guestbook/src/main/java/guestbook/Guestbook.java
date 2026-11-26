// A guestbook on the Jakarta Servlet API: a page that lists the entries and takes new ones
// through a form, and a JSON API over the same entries.
//
//   GET  /guestbook/                  -> 302 to /guestbook/entries
//   GET  /guestbook/entries           -> the HTML page: the entries and a form
//   POST /guestbook/entries           -> a new entry from the form, then 302 back to the
//                                        page; 400 when the name or the message is blank
//   GET  /guestbook/api/entries       -> the entries as JSON, ?name=.. keeps one author's
//   GET  /guestbook/api/entries/{id}  -> one entry as JSON; 404 when there is none
//
// What a request goes through: a filter on every path (the character encoding of the
// request and the response, a header, a line in the context's log), then the servlet
// mapped to its path. Nothing is registered: the servlets, the filter and the listener
// carry the annotations of the Servlet API (@WebServlet, @WebFilter, @WebListener) and a
// container finds them. This file's main starts servlet-httpserver, asked to discover
// them; a container the classes are deployed to as a WAR finds them too, with no web.xml.
//
// The JSON is built with tiny-json (https://github.com/making/tiny-json).
//
// The entries are in memory: they last as long as the program, which under
// `wasmtime serve` is as long as the host keeps the instance. The page fills the form
// with the name a visitor signed with last, kept in the visitor's session: in memory
// too, or in a host's wasi:keyvalue store where servlet-httpserver-keyvalue is on the
// source path, which outlives the instance and the host's restart.
package guestbook;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.json.Json;
import am.ik.json.JsonArray;
import am.ik.json.JsonObject;
import am.ik.servlet.httpserver.ServletHttpServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class Guestbook {

    // The name the entries are kept under in the context, for every servlet to find.
    static final String ENTRIES = "guestbook.entries";

    // The name a visitor's own name is kept under in the visitor's session.
    static final String NAME = "guestbook.name";

    record Entry(int id, String name, String message) {

        JsonObject toJson() {
            return new JsonObject().put("id", this.id).put("name", this.name).put("message", this.message);
        }

    }

    // The entries, shared by the requests with no lock: a map that takes concurrent
    // updates, by ids a counter hands out one after the other, 1, 2, 3 ..
    static class Entries {

        private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

        private final AtomicInteger lastId = new AtomicInteger();

        Entry add(String name, String message) {
            Entry entry = new Entry(this.lastId.incrementAndGet(), name, message);
            this.entries.put(entry.id(), entry);
            return entry;
        }

        // In the order of their ids: an id handed out and not yet put is passed over.
        List<Entry> by(String name) {
            List<Entry> found = new ArrayList<>();
            for (int id = 1; id <= this.lastId.get(); id++) {
                Entry entry = this.entries.get(id);
                if (entry != null && (name == null || entry.name().equals(name))) {
                    found.add(entry);
                }
            }
            return found;
        }

        Entry get(int id) {
            return this.entries.get(id);
        }

        static Entries of(ServletContext context) {
            return (Entries) context.getAttribute(ENTRIES);
        }

    }

    // The page: the entries and a form posting a new one back to the same path.
    @WebServlet("/entries")
    public static class Page extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/html");
            PrintWriter out = response.getWriter();
            out.println("<!doctype html>");
            out.println("<title>Guestbook</title>");
            out.println("<h1>Guestbook</h1>");
            out.println("<ul>");
            for (Entry entry : Entries.of(getServletContext()).by(null)) {
                out.println("<li><b>" + html(entry.name()) + "</b>: " + html(entry.message()) + "</li>");
            }
            out.println("</ul>");
            // the name this visitor signed with last, kept in the session
            HttpSession session = request.getSession(false);
            String name = (session == null) ? null : (String) session.getAttribute(NAME);
            out.println("<form method=\"post\" action=\"entries\">");
            out.println("<input name=\"name\" placeholder=\"Name\"" + (name == null ? "" : " value=\"" + html(name) + "\"")
                    + "> <input name=\"message\" placeholder=\"Message\">");
            out.println("<button>Sign</button>");
            out.println("</form>");
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String name = request.getParameter("name");
            String message = request.getParameter("message");
            if (name == null || name.isBlank() || message == null || message.isBlank()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "a name and a message, please");
                return;
            }
            Entries.of(getServletContext()).add(name.strip(), message.strip());
            request.getSession().setAttribute(NAME, name.strip());
            // relative to this path: the page again (Post/Redirect/Get)
            response.sendRedirect("entries");
        }

    }

    // The JSON API: /api/entries is every entry, /api/entries/{id} one.
    @WebServlet({ "/api/entries", "/api/entries/*" })
    public static class Api extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            Entries entries = Entries.of(getServletContext());
            String id = request.getPathInfo();
            String json;
            if (id == null || id.equals("/")) {
                JsonArray all = new JsonArray();
                for (Entry entry : entries.by(request.getParameter("name"))) {
                    all.add(entry.toJson().toNode());
                }
                json = Json.stringify(all);
            }
            else {
                Entry entry = null;
                try {
                    entry = entries.get(Integer.parseInt(id.substring(1)));
                }
                catch (NumberFormatException ex) {
                    // not a number: no such entry either
                }
                if (entry == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "no entry " + id.substring(1));
                    return;
                }
                json = Json.stringify(entry.toJson());
            }
            response.setContentType("application/json");
            response.getWriter().println(json);
        }

    }

    // The context root: the page is at entries. The location is a path from the root, not
    // one relative to the request's: a container may hand this servlet the context path
    // with no slash after it, which a relative "entries" would leave.
    @WebServlet("")
    public static class Home extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.sendRedirect(request.getContextPath() + "/entries");
        }

    }

    // Every request: UTF-8 both ways (a form with no charset is not read as UTF-8 by every
    // container), a header, and a line in the context's log once the servlet answered.
    @WebFilter("/*")
    public static class Requests extends HttpFilter {

        @Override
        protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            request.setCharacterEncoding("UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("X-Content-Type-Options", "nosniff");
            chain.doFilter(request, response);
            getServletContext().log(request.getMethod() + " " + request.getRequestURI() + " " + response.getStatus());
        }

    }

    // The start of the application: a container calls it once the context is made, before
    // any request. The entries are the context's, for every servlet to find.
    @WebListener
    public static class Application implements ServletContextListener {

        @Override
        public void contextInitialized(ServletContextEvent event) {
            ServletContext context = event.getServletContext();
            Entries entries = new Entries();
            entries.add("rontolisp", "Hello from the guestbook!");
            context.setAttribute(ENTRIES, entries);
            context.log("guestbook started");
        }

        @Override
        public void contextDestroyed(ServletContextEvent event) {
            event.getServletContext().log("guestbook stopped");
        }

    }

    static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // Serves the application on servlet-httpserver, at the port of the PORT environment
    // variable (8080 without one): the server discovers the annotated classes itself.
    public static void main(String[] args) throws Exception {
        String port = System.getenv("PORT");
        ServletHttpServer server = ServletHttpServer.builder()
            .port(port == null ? 8080 : Integer.parseInt(port))
            .contextPath("/guestbook")
            .discover()
            .start();
        System.out.println("Listening on http://localhost:" + server.port() + "/guestbook/");
    }

}
