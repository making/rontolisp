(ns rontolisp.http-client
  "An HTTP client built into rontolisp, with the API of babashka.http-client:
  request over an options map, and get, post, put, delete, head and patch over
  a URL and the options. Its transport is rontolisp:fetch on every target -- the
  JDK's client on the interpreter and the JVM, wasi:http in a --component, the
  host's fetch under --no-wasi --host-fetch and the runner of a --native
  executable. Written for this front end from babashka.http-client's documented
  behaviour."
  (:refer-clojure :exclude [get])
  (:require [rontolisp.internal.http :as kernel]))

(defn request
  "The response of the request opts describes, a map of :status, :headers,
  :body, :uri and :request; with :async true, a future of it. Options: :uri (or
  :url) and :method (or :request-method), :headers, :query-params,
  :form-params, :body, :basic-auth, :oauth-token, :accept, :as (:string or
  :stream), :throw, :async, :async-then and :async-catch."
  [opts]
  (kernel/request opts (fn [url options] (kernel/fetch url options))))

(defn get
  "request with :uri uri and :method :get."
  ([uri] (get uri nil))
  ([uri opts] (request (assoc opts :uri uri :method :get))))

(defn delete
  "request with :uri uri and :method :delete."
  ([uri] (delete uri nil))
  ([uri opts] (request (assoc opts :uri uri :method :delete))))

(defn head
  "request with :uri uri and :method :head."
  ([uri] (head uri nil))
  ([uri opts] (request (assoc opts :uri uri :method :head))))

(defn post
  "request with :uri uri and :method :post."
  ([uri] (post uri nil))
  ([uri opts] (request (assoc opts :uri uri :method :post))))

(defn patch
  "request with :uri uri and :method :patch."
  ([uri] (patch uri nil))
  ([uri opts] (request (assoc opts :uri uri :method :patch))))

(defn put
  "request with :uri uri and :method :put."
  ([uri] (put uri nil))
  ([uri opts] (request (assoc opts :uri uri :method :put))))
