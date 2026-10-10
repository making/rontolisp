(ns rontolisp.http-urls
  "Makes clojure.java.io read http: and https: URLs -- slurp, reader,
  input-stream and a URL's openStream -- through rontolisp:fetch, as
  java.net.HttpURLConnection reads one: a GET, a redirect followed to a URL of
  the same protocol, a reply of status 400 or more refused. It defines nothing:
  a program requiring it names fetch, installed before the program runs, so its
  target must carry fetch -- the JDK's client on the interpreter and the JVM,
  wasi:http in a --component, the host's fetch under --no-wasi --host-fetch and
  the runner of a --native executable. A program that writes such a URL as a
  string literal in the form that reads it reads them without the require.")
