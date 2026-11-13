package org.wavvon.sdk;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A scripted hub on loopback: each request goes to {@code route}. */
final class TestHub implements AutoCloseable {
  record Req(String method, String path, String query, Headers headers, String body) {}

  record Res(int status, String body) {
    static Res json(String body) {
      return new Res(200, body);
    }
  }

  interface Route {
    Res handle(Req req) throws Exception;
  }

  final List<Req> log = new CopyOnWriteArrayList<>();
  private final HttpServer server;

  TestHub(Route route) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          try (ex) {
            Req req =
                new Req(
                    ex.getRequestMethod(),
                    ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(),
                    ex.getRequestHeaders(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            log.add(req);
            Res res;
            try {
              res = route.handle(req);
            } catch (Exception e) {
              res = new Res(500, e.toString());
            }
            byte[] b =
                res.body() == null ? new byte[0] : res.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(res.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
          }
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  long count(String method, String path) {
    return log.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).count();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
