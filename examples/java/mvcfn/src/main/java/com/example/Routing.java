// The routes of a small task list, written against Spring MVC's functional endpoints
// (WebMvc.fn): a greeting, and a JSON API under /tasks with a nested path, path
// variables, a query parameter, a request body, a filter and an error handler. The JSON
// is tiny-json's (TinyJsonHttpMessageConverter).
package com.example;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.json.JsonArray;
import am.ik.json.JsonNode;
import am.ik.json.JsonObject;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RequestPredicates.contentType;

public class Routing {

    // A task is a value: a change makes a new one, put in the place of the old.
    record Task(int id, String title, boolean done) {

        // The title and the done flag a JSON body names, the others as they were.
        Task changedBy(JsonObject changes) {
            String text = text(changes, "title");
            String title = (text != null && !text.isBlank()) ? text.strip() : this.title;
            JsonNode flag = changes.get("done");
            boolean done = flag.isBoolean() ? flag.asBoolean() : this.done;
            return new Task(this.id, title, done);
        }

        JsonObject toJson() {
            return new JsonObject().put("id", this.id).put("title", this.title).put("done", this.done);
        }
    }

    // The tasks by id, shared by the requests: no lock, a map that takes concurrent
    // updates, and the ids from a counter.
    private final Map<Integer, Task> tasks = new ConcurrentHashMap<>();

    private final AtomicInteger lastId = new AtomicInteger();

    public Routing() {
        add("Write the routes");
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
            .GET("/", request -> ServerResponse.ok().body("Hello World!"))
            .path("/tasks", tasks -> tasks
                .GET("", this::list)
                .POST("", contentType(MediaType.APPLICATION_JSON), this::create)
                .GET("/{id}", request -> ServerResponse.ok().body(find(request).toJson()))
                .PUT("/{id}", contentType(MediaType.APPLICATION_JSON), this::update)
                .DELETE("/{id}", this::remove))
            .filter((request, next) -> {
                ServerResponse response = next.handle(request);
                System.out.println(request.method() + " " + request.path() + " -> " + response.statusCode().value());
                return response;
            })
            .onError(IllegalArgumentException.class, (ex, request) -> ServerResponse.badRequest()
                .body(new JsonObject().put("error", ex.getMessage()).put("path", request.path())))
            .build();
    }

    // GET /tasks, GET /tasks?done=true: in the order of their ids
    private ServerResponse list(ServerRequest request) {
        List<Task> sorted = new ArrayList<>(this.tasks.values());
        sorted.sort(Comparator.comparingInt(Task::id));
        JsonArray listed = new JsonArray();
        for (Task task : sorted) {
            if (request.param("done").map(done -> task.done() == Boolean.parseBoolean(done)).orElse(true)) {
                listed.add(task.toJson().toNode());
            }
        }
        return ServerResponse.ok().body(listed);
    }

    // POST /tasks {"title": ".."}: 201 with the task and where it is
    private ServerResponse create(ServerRequest request) throws Exception {
        JsonObject body = request.body(JsonObject.class);
        String title = text(body, "title");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("a title, please");
        }
        Task task = add(title.strip());
        return ServerResponse.created(URI.create("/tasks/" + task.id())).body(task.toJson());
    }

    // PUT /tasks/1 {"done": true}
    private ServerResponse update(ServerRequest request) throws Exception {
        int id = find(request).id();
        JsonObject body = request.body(JsonObject.class);
        Task task = this.tasks.computeIfPresent(id, (key, old) -> old.changedBy(body));
        if (task == null) {
            // removed since it was found
            throw noTask(String.valueOf(id));
        }
        return ServerResponse.ok().body(task.toJson());
    }

    // DELETE /tasks/1: 204
    private ServerResponse remove(ServerRequest request) {
        this.tasks.remove(find(request).id());
        return ServerResponse.noContent().build();
    }

    // The task a path names: 400 for an id that is no number, 404 for one no task has.
    private Task find(ServerRequest request) {
        String id = request.pathVariable("id");
        Task task;
        try {
            task = this.tasks.get(Integer.valueOf(id));
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("no number: " + id);
        }
        if (task == null) {
            throw noTask(id);
        }
        return task;
    }

    // A member's text, null when it is no string. The is- and as- accessors, not value(): a
    // number the body holds is then never made a BigDecimal, and java.math stays out.
    private static String text(JsonObject json, String name) {
        JsonNode node = json.get(name);
        return node.isString() ? node.asString() : null;
    }

    private static ResponseStatusException noTask(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + id);
    }

    private Task add(String title) {
        Task task = new Task(this.lastId.incrementAndGet(), title, false);
        this.tasks.put(task.id(), task);
        return task;
    }
}
