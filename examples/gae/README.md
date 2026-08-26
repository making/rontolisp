# rontolisp on Google App Engine

Common Lisp compiled for App Engine standard, second-generation Java runtime.
There is **no `.lisp` file in this directory**, and that is the point: both
projects here compile [`net/httpbin-clack.lisp`](../net/httpbin-clack.lisp)
*itself* — the file that binds a socket locally, deploys into a Servlet
container and runs under `wasmtime serve` — for the two shapes App Engine
accepts.

```bash
cd jar
./build.sh                                              # -> app.jar
gcloud app deploy app.yaml --project YOUR_PROJECT
curl 'https://YOUR_PROJECT.REGION.r.appspot.com/get?a=1&b=two'
```

| Directory | Output | Who owns the port | Configuration |
| --- | --- | --- | --- |
| [`jar/`](jar) | **Start here.** `-o app.jar`, the JDK HTTP server | the program, on `$PORT` | `app.yaml`, four lines |
| [`war/`](war) | `-o app.war`, the Servlet transport | App Engine's Jetty | `app.yaml` + `WEB-INF/appengine-web.xml` + one system property |

## Use the jar

App Engine runs a Servlet container, so the war looks like the native fit and
is not. **The jar is between two and five times faster under load and starts
in less than half the time**, on the identical program:

| Offered rate | war served | war mean | war p99 | jar served | jar mean | jar p99 |
| --- | --- | --- | --- | --- | --- | --- |
| 10 /s | 10.0 | 29.5 ms | 106 ms | 10.0 | 30.1 ms | 112 ms |
| 25 /s | 25.0 | 31.8 ms | 124 ms | 25.0 | 30.8 ms | 111 ms |
| 50 /s | 50.0 | 37.9 ms | 145 ms | 50.0 | 26.8 ms | 106 ms |
| 75 /s | **73.2** | **139 ms** | **759 ms** | 75.0 | 33.6 ms | 125 ms |
| 100 /s | **77.5** | **2.15 s** | **5.70 s** | 98.9 | 53.7 ms | 351 ms |
| 150 /s | — | — | — | 149.2 | 207 ms | 459 ms |
| 200 /s | — | — | — | **167.1** | **1.97 s** | **3.91 s** |

Cold start, measured as the first request after a fresh deploy: **war 3.12 s
and 3.50 s, jar 1.38 s and 1.52 s.**

Below 50 req/s the two are indistinguishable — the difference is entirely a
saturation point, one F1 instance giving out at about 75 req/s as a war and
about 150 req/s as a jar. The cause is in the log, once per instance:

```
HTTP-HANDLER: a filter in the chain does not support asynchronous operations;
serving synchronously.
```

The Servlet transport is [async by
design](../../.kb/concurrent-served-requests.md) — `startAsync`, one virtual
thread per request — and App Engine's own filter chain refuses async, so the
war falls back to serving on Jetty's pool. That fallback is the whole gap. It
is not something the program can opt out of: the filters are the runtime's.

The jar has no container in front of it and keeps its own concurrency.

### How the numbers were taken

Both versions of the same `net/httpbin-clack.lisp`, deployed to one project in
`asia-northeast1`, `instance_class: F1`, `max_instances: 1`, each measured from
Tokyo after a 15-second warm-up, 20 seconds per rate:

```bash
echo "GET https://VERSION-dot-PROJECT.REGION.r.appspot.com/get?a=1&b=two" > get.txt
vegeta attack -targets=get.txt -rate=75 -duration=20s -timeout=30s | vegeta report
```

The jar was read through a version-specific hostname, which adds several
`x-appengine-*` request headers; since the endpoint echoes headers back, its
replies were 1087 bytes against the war's 659. The faster side was doing more
work per request, not less.

## One-time project setup

```bash
gcloud app create --region=asia-northeast1 --project YOUR_PROJECT
```

The region **cannot be changed afterwards**, and an App Engine application is
one per project.

A first deploy into a freshly created application can fail with

```
Failed to create cloud build: ... invalid bucket "staging.PROJECT.appspot.com";
service account PROJECT@appspot.gserviceaccount.com does not have access
```

even though that account holds `roles/editor`. Granting it explicitly on the
two buckets `gcloud app create` made clears it:

```bash
for b in staging.PROJECT.appspot.com PROJECT.appspot.com; do
  gcloud storage buckets add-iam-policy-binding gs://$b \
    --member=serviceAccount:PROJECT@appspot.gserviceaccount.com \
    --role=roles/storage.admin --project YOUR_PROJECT
done
```

## What the buildpack accepts

Deploying a Java application to the second-generation runtime means handing
`gcloud app deploy` a **directory**, and the buildpack decides what to do with
it by what it finds:

| In the directory | Result |
| --- | --- |
| exactly one jar with a `Main-Class` manifest entry | `java -jar` on it — [`jar/`](jar) |
| `WEB-INF/appengine-web.xml`, unpacked war around it | Jetty over the web application — [`war/`](war) |
| a `.war` **file** | `did not find any jar files with a Main-Class manifest entry` |

That third row is the one worth stating: `-o app.war` produces an archive every
Servlet 6 container deploys as-is, and App Engine is not one of them. `war/`'s
`build.sh` unpacks it in place for that reason.

## Free tier

App Engine standard includes 28 instance-hours per day, counted in F1 units, so
`instance_class: F1` and `min_instances: 0` are what keep an idle application
at zero. Both `app.yaml` files here are written that way, with
`max_instances: 1` as a ceiling to raise deliberately rather than a target.

The deploy itself is not free of quota: it runs a Cloud Build (120 build-minutes
a day are free) and leaves an image in Artifact Registry (0.5 GB free). Deleting
versions you are done with reclaims both:

```bash
gcloud app versions list --project YOUR_PROJECT
gcloud app versions delete v1 v2 --project YOUR_PROJECT --service default
```

## Deploying the war anyway

If the Servlet transport is what you want — a filter chain, a context path, a
container you already run elsewhere — [`war/`](war) is a complete, working
project. Three things it needs that the jar does not:

1. **The war must be unpacked.** `build.sh` does it; see the table above.
2. **`WEB-INF/appengine-web.xml` must exist.** Nothing in it is load-bearing;
   its presence is what selects the web-application path at all.
3. **Annotation scanning must be turned on**, with
   `JAVA_TOOL_OPTIONS: "-Duse.annotationscanning=true"` in `app.yaml`.

The third one costs the most to discover. App Engine's Jetty 12.1 EE11 adds
`AnnotationConfiguration` only when that system property is set or when a
`WEB-INF/quickstart-web.xml` is present
([`EE11AppVersionHandlerFactory`](https://github.com/GoogleCloudPlatform/appengine-java-standard/blob/main/runtime/runtime_impl_jetty121/src/main/java/com/google/apphosting/runtime/jetty/ee11/EE11AppVersionHandlerFactory.java)).
A rontolisp war carries no `web.xml` — it registers through a
`ServletContainerInitializer` with `@HandlesTypes`, which is exactly what
annotation scanning discovers — so with scanning off the deployment **succeeds**,
starts cleanly, logs nothing, and answers 404 to every request.

`appengine-web.xml`'s `<system-properties>` cannot carry it: only names
beginning with `appengine.` are propagated to the JVM
([`AppEngineWebXmlInitialParse`](https://github.com/GoogleCloudPlatform/appengine-java-standard/blob/main/appengine_init/src/main/java/com/google/appengine/init/AppEngineWebXmlInitialParse.java)).
`JAVA_TOOL_OPTIONS` is the short way in; generating a `quickstart-web.xml` at
staging time (what `appengine-maven-plugin` does) is the other.

## The program, and what it does not say

Nothing in `net/httpbin-clack.lisp` names App Engine. `:server :rontolisp`
means "serve on **this target's** native inbound transport", chosen at compile
time, so the same file is every host's program:

```bash
PORT=3000 rontolisp ../net/httpbin-clack.lisp                    # :3000
rontolisp ../net/httpbin-clack.lisp -o app.jar                   # jar/, and java -jar locally
rontolisp ../net/httpbin-clack.lisp -o app.war                   # war/, and any Servlet 6 container
rontolisp ../net/httpbin-clack.lisp -o app.wasm --component      # wasmtime serve
rontolisp ../net/httpbin-clack.lisp -o worker.wasm --no-wasi     # Cloudflare Workers
```

`:port (uiop:getenvp "PORT")` is read on the row that owns its socket and
ignored on the rows that do not — App Engine sets `PORT=8081`, and the jar
binds it. The Worker of the same file is
[`cloudflare-workers/httpbin-clack-one-source/`](../cloudflare-workers/httpbin-clack-one-source).

Two things worth changing before real traffic, neither specific to App Engine:

- `clack:clackup` defaults to `:debug t`, which logs `NOTICE: Running in debug
  mode` on startup and invokes the debugger on errors. Pass `:debug nil`.
- The endpoints echo every request header back, which is what makes this
  httpbin and not an application.

## Checking it

```console
$ curl 'https://PROJECT.REGION.r.appspot.com/get?a=1&b=two'
{"args":{"a":"1","b":"two"},"headers":{...},"method":"GET","path":"/get"}

$ curl -X POST -d '{"name":"rontolisp"}' https://PROJECT.REGION.r.appspot.com/post
{...,"method":"POST","path":"/post","data":"{\"name\":\"rontolisp\"}","json":{"name":"rontolisp"}}

$ curl -s -o /dev/null -w '%{http_code}\n' https://PROJECT.REGION.r.appspot.com/post   # GET on /post
405
$ curl -s -o /dev/null -w '%{http_code}\n' https://PROJECT.REGION.r.appspot.com/nope
404
```

Every endpoint above was checked on the real service, on both the jar and the
war. As elsewhere, **the order of keys inside a JSON object follows hash-table
iteration order** and differs between backends.

## What's in here

| File | Purpose |
| --- | --- |
| [`../net/httpbin-clack.lisp`](../net/httpbin-clack.lisp) | **The whole program** — not in this directory, deliberately |
| [`jar/build.sh`](jar/build.sh) | That file `-o app.jar` |
| [`jar/app.yaml`](jar/app.yaml) | The entire configuration of the recommended path |
| [`war/build.sh`](war/build.sh) | That file `-o app.war`, unpacked in place |
| [`war/app.yaml`](war/app.yaml) | Plus the annotation-scanning property |
| [`war/WEB-INF/appengine-web.xml`](war/WEB-INF/appengine-web.xml) | Present so the buildpack starts Jetty |

`app.jar` and `war/WEB-INF/classes/` are build products and are not checked in.
Both `build.sh` need the compiler jar, built once from the repository root with
`./mvnw clean package -DskipTests`.
