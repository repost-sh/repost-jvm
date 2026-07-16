package sh.repost.conformance.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free Java 11 control foundation for the executable JVM raw fixture. */
public final class JvmRawFixtureRunner {
  private static final String CANARY_GROUP = "__internal-h1-canary";
  private static final String CANARY_BARRIER = "h1-canary-request";
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Set<String> ACTIONS = Set.of(
      "reset", "load", "start", "awaitBarrier", "releaseBarrier",
      "advanceMonotonicTime", "snapshot", "close");

  private final String groupId;
  private final CountDownLatch closedLatch = new CountDownLatch(1);
  private final List<String> controls = new ArrayList<>();
  private final EventLedger events;
  private final ExecutorService controlExecutor;
  private HttpServer controlServer;
  private Scenario scenario = Scenario.empty();
  private PeerSupervisor peers;
  private String lifecycle = "NEW";
  private long monotonicTimeMs;
  private boolean loaded;
  private boolean started;
  private boolean closed;

  public JvmRawFixtureRunner(String groupId) {
    if (!CANARY_GROUP.equals(groupId)) {
      throw new UnsupportedOperationException(
          "UNSUPPORTED JVM raw fixture production group: " + groupId
              + "; foundation implements only " + CANARY_GROUP);
    }
    this.groupId = groupId;
    this.events = new EventLedger(System.getenv("REPOST_RAW_FIXTURE_TEST_SUPPRESS_EVENT"));
    this.controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "repost-jvm-raw-control");
      thread.setDaemon(false);
      thread.setContextClassLoader(null);
      return thread;
    });
  }

  public void start() throws IOException {
    controlServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
    controlServer.createContext("/control", this::handle);
    controlServer.setExecutor(controlExecutor);
    controlServer.start();
    String endpoint = "http://127.0.0.1:" + controlServer.getAddress().getPort() + "/control";
    System.out.println("{\"protocolVersion\":1,\"groupId\":" + quote(groupId)
        + ",\"controlEndpoint\":" + quote(endpoint) + ",\"pid\":"
        + ProcessHandle.current().pid() + "}");
    System.out.flush();
  }

  public void awaitClosed() throws InterruptedException {
    closedLatch.await();
  }

  private void handle(HttpExchange exchange) throws IOException {
    boolean closeAfterResponse = false;
    try {
      if (!"POST".equals(exchange.getRequestMethod())
          || !"/control".equals(exchange.getRequestURI().getRawPath())
          || exchange.getRequestURI().getRawQuery() != null) {
        respond(exchange, 404, "{\"error\":\"not-found\"}");
        return;
      }
      byte[] bytes = exchange.getRequestBody().readNBytes(1025);
      if (bytes.length > 1024 || exchange.getRequestBody().read() != -1) {
        respond(exchange, 400, "{\"error\":\"request-too-large\"}");
        return;
      }
      Map<String, Object> request = new FlatJsonParser(decodeUtf8(bytes)).parse();
      String action = request.get("action") instanceof String ? (String) request.get("action") : null;
      if (action == null || !ACTIONS.contains(action)) throw new IllegalArgumentException("unknown action");
      requireExactControlFields(action, request);
      String response;
      synchronized (this) {
        response = apply(action, request);
        closeAfterResponse = "close".equals(action);
      }
      respond(exchange, 200, response);
    } catch (CharacterCodingException | IllegalArgumentException error) {
      respond(exchange, 400, "{\"error\":" + quote(safeError(error)) + "}");
    } finally {
      if (closeAfterResponse) finishCloseAsync();
    }
  }

  private synchronized String apply(String action, Map<String, Object> request) {
    if (closed) throw new IllegalArgumentException("fixture closed");
    switch (action) {
      case "reset":
        closePeers();
        controls.clear();
        controls.add(action);
        events.clear();
        scenario = Scenario.empty();
        monotonicTimeMs = 0;
        loaded = false;
        started = false;
        lifecycle = "RESET";
        return stateJson();
      case "load":
        if (!"RESET".equals(lifecycle) || loaded || started) throw new IllegalArgumentException("load order");
        try {
          peers = PeerSupervisor.h1Canary(events);
        } catch (IOException error) {
          throw new IllegalArgumentException("peer bind failed", error);
        }
        scenario = Scenario.h1Canary(peers.port());
        loaded = true;
        lifecycle = "LOADED";
        controls.add(action);
        return stateJson();
      case "start":
        if (!loaded || started || peers == null) throw new IllegalArgumentException("start order");
        peers.start();
        started = true;
        lifecycle = "STARTED";
        controls.add(action);
        return stateJson();
      case "awaitBarrier":
        requireStarted();
        requireBarrier(request);
        controls.add(action);
        return barrierJson(peers.barrier().status());
      case "releaseBarrier":
        requireStarted();
        requireBarrier(request);
        peers.barrier().release();
        controls.add(action);
        return barrierJson("RELEASED");
      case "advanceMonotonicTime":
        requireStarted();
        Object value = request.get("deltaMs");
        if (!(value instanceof Long)) throw new IllegalArgumentException("deltaMs type");
        long delta = (Long) value;
        if (delta <= 0 || delta > MAX_SAFE_INTEGER - monotonicTimeMs) {
          throw new IllegalArgumentException("deltaMs range");
        }
        monotonicTimeMs += delta;
        controls.add(action);
        return stateJson();
      case "snapshot":
        requireStarted();
        controls.add(action);
        return stateJson();
      case "close":
        controls.add(action);
        closePeers();
        loaded = false;
        started = false;
        closed = true;
        lifecycle = "CLOSED";
        return stateJson();
      default:
        throw new IllegalArgumentException("unknown action");
    }
  }

  private void requireStarted() {
    if (!started || peers == null) throw new IllegalArgumentException("fixture not started");
  }

  private static void requireExactControlFields(String action, Map<String, Object> request) {
    int expected = 1;
    if ("awaitBarrier".equals(action) || "releaseBarrier".equals(action)
        || "advanceMonotonicTime".equals(action)) expected = 2;
    if (request.size() != expected) throw new IllegalArgumentException("unknown or missing control field");
    if (("awaitBarrier".equals(action) || "releaseBarrier".equals(action))
        && !request.containsKey("barrier")) throw new IllegalArgumentException("missing barrier");
    if ("advanceMonotonicTime".equals(action) && !request.containsKey("deltaMs")) {
      throw new IllegalArgumentException("missing deltaMs");
    }
  }

  private static void requireBarrier(Map<String, Object> request) {
    if (!CANARY_BARRIER.equals(request.get("barrier"))) {
      throw new IllegalArgumentException("unknown barrier");
    }
  }

  private synchronized String stateJson() {
    List<String> waiting = new ArrayList<>();
    List<String> released = new ArrayList<>();
    if (peers != null && started) {
      if (peers.barrier().isReleased()) released.add(CANARY_BARRIER);
      else waiting.add(CANARY_BARRIER);
    }
    return "{\"schema\":\"jvm-raw-fixture-state-v1\",\"protocolVersion\":1,\"groupId\":"
        + quote(groupId) + ",\"lifecycle\":" + quote(lifecycle)
        + ",\"monotonicTimeMs\":" + monotonicTimeMs
        + ",\"controls\":" + stringArray(controls)
        + ",\"observedCounters\":" + events.json()
        + ",\"scenario\":" + scenario.json()
        + ",\"loaded\":" + loaded + ",\"started\":" + started
        + ",\"waitingBarriers\":" + stringArray(waiting)
        + ",\"releasedBarriers\":" + stringArray(released)
        + ",\"closed\":" + closed + "}";
  }

  private String barrierJson(String status) {
    return "{\"schema\":\"jvm-raw-fixture-barrier-v1\",\"protocolVersion\":1,\"groupId\":"
        + quote(groupId) + ",\"barrier\":" + quote(CANARY_BARRIER)
        + ",\"status\":" + quote(status) + ",\"monotonicTimeMs\":"
        + monotonicTimeMs + "}";
  }

  private void closePeers() {
    if (peers != null) {
      peers.close();
      peers = null;
    }
  }

  private void finishCloseAsync() {
    Thread closer = new Thread(() -> {
      if (controlServer != null) controlServer.stop(0);
      controlExecutor.shutdown();
      try {
        if (!controlExecutor.awaitTermination(2, TimeUnit.SECONDS)) controlExecutor.shutdownNow();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        controlExecutor.shutdownNow();
      } finally {
        closedLatch.countDown();
      }
    }, "repost-jvm-raw-control-close");
    closer.setContextClassLoader(null);
    closer.start();
  }

  private static final class Scenario {
    enum Role { ORIGIN, PROXY, TRAP, ALTERNATE }
    enum Scheme { http, https, tcp }
    enum AssetKind {
      CA_CERTIFICATE, SERVER_CERTIFICATE, CLIENT_CERTIFICATE, CLIENT_PRIVATE_KEY,
      CLIENT_PRIVATE_KEY_PASSWORD, KEYSTORE, TRUSTSTORE
    }

    final List<EndpointDescriptor> endpoints;
    final List<AssetDescriptor> assets;

    Scenario(List<EndpointDescriptor> endpoints, List<AssetDescriptor> assets) {
      this.endpoints = List.copyOf(endpoints);
      this.assets = List.copyOf(assets);
    }

    static Scenario empty() { return new Scenario(List.of(), List.of()); }

    static Scenario h1Canary(int port) {
      return new Scenario(List.of(new EndpointDescriptor(
          "canary-origin", Role.ORIGIN, Scheme.http, "127.0.0.1", port,
          "127.0.0.1", "/canary", List.of("http/1.1"))), List.of());
    }

    String json() {
      StringBuilder result = new StringBuilder("{\"endpoints\":[");
      for (int index = 0; index < endpoints.size(); index++) {
        if (index > 0) result.append(',');
        result.append(endpoints.get(index).json());
      }
      result.append("],\"assets\":[");
      for (int index = 0; index < assets.size(); index++) {
        if (index > 0) result.append(',');
        result.append(assets.get(index).json());
      }
      return result.append("]}").toString();
    }
  }

  private static final class EndpointDescriptor {
    final String id;
    final Scenario.Role role;
    final Scenario.Scheme scheme;
    final String connectHost;
    final int port;
    final String authorityHost;
    final String basePath;
    final List<String> alpnProtocols;

    EndpointDescriptor(String id, Scenario.Role role, Scenario.Scheme scheme, String connectHost,
        int port, String authorityHost, String basePath, List<String> alpnProtocols) {
      this.id = id;
      this.role = role;
      this.scheme = scheme;
      this.connectHost = connectHost;
      this.port = port;
      this.authorityHost = authorityHost;
      this.basePath = basePath;
      this.alpnProtocols = List.copyOf(alpnProtocols);
    }

    String json() {
      return "{\"id\":" + quote(id) + ",\"role\":" + quote(role.name())
          + ",\"scheme\":" + quote(scheme.name()) + ",\"connectHost\":"
          + quote(connectHost) + ",\"port\":" + port + ",\"authorityHost\":"
          + quote(authorityHost) + ",\"basePath\":" + quote(basePath)
          + ",\"alpnProtocols\":" + stringArray(alpnProtocols) + "}";
    }
  }

  private static final class AssetDescriptor {
    final String id;
    final Scenario.AssetKind kind;
    final String path;

    AssetDescriptor(String id, Scenario.AssetKind kind, String path) {
      this.id = id;
      this.kind = kind;
      this.path = path;
    }

    String json() {
      return "{\"id\":" + quote(id) + ",\"kind\":" + quote(kind.name())
          + ",\"path\":" + quote(path) + "}";
    }
  }

  private static final class EventLedger {
    enum Event {
      H1_CANARY_CONNECTIONS_ACCEPTED("h1CanaryConnectionsAccepted"),
      H1_CANARY_REQUESTS_OBSERVED("h1CanaryRequestsObserved"),
      H1_CANARY_RESPONSES_WRITTEN("h1CanaryResponsesWritten"),
      H1_CANARY_PEER_FAILURES("h1CanaryPeerFailures"),
      PEER_CLOSE_FAILURES("peerCloseFailures");

      final String wireName;
      Event(String wireName) { this.wireName = wireName; }
    }

    private final Map<Event, Long> counters = new EnumMap<>(Event.class);
    private final Event suppressed;

    EventLedger(String suppressedWireName) {
      Event found = null;
      if (suppressedWireName != null) {
        for (Event event : Event.values()) if (event.wireName.equals(suppressedWireName)) found = event;
        if (found == null) throw new IllegalArgumentException("unknown suppressed event");
      }
      this.suppressed = found;
    }

    synchronized void increment(Event event) {
      if (event == suppressed) return;
      counters.put(event, counters.getOrDefault(event, 0L) + 1L);
    }

    synchronized void clear() { counters.clear(); }

    synchronized String json() {
      StringBuilder result = new StringBuilder("{");
      int index = 0;
      for (Map.Entry<Event, Long> entry : counters.entrySet()) {
        if (index++ > 0) result.append(',');
        result.append(quote(entry.getKey().wireName)).append(':').append(entry.getValue());
      }
      return result.append('}').toString();
    }
  }

  private static final class TwoPhaseBarrier {
    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean released = new AtomicBoolean();

    void arriveAndAwaitRelease() throws InterruptedException {
      arrived.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("barrier release timeout");
    }

    void release() {
      if (arrived.getCount() != 0) throw new IllegalArgumentException("barrier has not arrived");
      if (!released.compareAndSet(false, true)) throw new IllegalArgumentException("barrier already released");
      release.countDown();
    }

    String status() {
      if (released.get()) return "RELEASED";
      return arrived.getCount() == 0 ? "ARRIVED" : "WAITING";
    }

    boolean isReleased() { return released.get(); }
    void forceRelease() { released.set(true); release.countDown(); }
  }

  private static final class PeerSupervisor implements AutoCloseable {
    private final EventLedger events;
    private final ServerSocket server;
    private final TwoPhaseBarrier barrier = new TwoPhaseBarrier();
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private Thread peerThread;
    private volatile boolean closed;

    static PeerSupervisor h1Canary(EventLedger events) throws IOException {
      return new PeerSupervisor(events, new ServerSocket(
          0, 1, InetAddress.getByName("127.0.0.1")));
    }

    private PeerSupervisor(EventLedger events, ServerSocket server) {
      this.events = events;
      this.server = server;
    }

    int port() { return server.getLocalPort(); }
    TwoPhaseBarrier barrier() { return barrier; }

    void start() {
      if (peerThread != null) throw new IllegalStateException("peer already started");
      peerThread = new Thread(this::serve, "repost-jvm-raw-h1-canary");
      peerThread.setContextClassLoader(null);
      peerThread.start();
    }

    private void serve() {
      try (Socket socket = server.accept()) {
        synchronized (sockets) {
          if (closed) {
            socket.close();
            return;
          }
          sockets.add(socket);
        }
        events.increment(EventLedger.Event.H1_CANARY_CONNECTIONS_ACCEPTED);
        byte[] request = readHeaders(socket.getInputStream(), 8192);
        String text = new String(request, StandardCharsets.US_ASCII);
        if (!text.startsWith("GET /canary HTTP/1.1\r\n") || !text.endsWith("\r\n\r\n")) {
          throw new IOException("invalid canary request");
        }
        events.increment(EventLedger.Event.H1_CANARY_REQUESTS_OBSERVED);
        barrier.arriveAndAwaitRelease();
        byte[] response = ("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n"
            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(response);
        socket.getOutputStream().flush();
        events.increment(EventLedger.Event.H1_CANARY_RESPONSES_WRITTEN);
      } catch (IOException error) {
        if (!closed) events.increment(EventLedger.Event.H1_CANARY_PEER_FAILURES);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        if (!closed) events.increment(EventLedger.Event.H1_CANARY_PEER_FAILURES);
      }
    }

    private static byte[] readHeaders(InputStream input, int limit) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      int matched = 0;
      while (output.size() < limit) {
        int value = input.read();
        if (value < 0) throw new IOException("premature EOF");
        output.write(value);
        int expected = matched == 0 || matched == 2 ? '\r' : '\n';
        if (value == expected) matched++;
        else matched = value == '\r' ? 1 : 0;
        if (matched == 4) return output.toByteArray();
      }
      throw new IOException("headers too large");
    }

    @Override public void close() {
      barrier.forceRelease();
      synchronized (sockets) {
        closed = true;
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear();
      }
      try { server.close(); } catch (IOException ignored) { }
      if (peerThread != null && peerThread != Thread.currentThread()) {
        try { peerThread.join(2000); } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
        if (peerThread.isAlive()) {
          events.increment(EventLedger.Event.PEER_CLOSE_FAILURES);
          throw new IllegalArgumentException("peer thread did not terminate");
        }
      }
    }
  }

  private static final class FlatJsonParser {
    private final String text;
    private int index;
    FlatJsonParser(String text) { this.text = text; }

    Map<String, Object> parse() {
      whitespace(); expect('{');
      Map<String, Object> result = new LinkedHashMap<>();
      whitespace();
      if (take('}')) return result;
      while (true) {
        String key = string();
        if (result.containsKey(key)) throw new IllegalArgumentException("duplicate field");
        whitespace(); expect(':'); whitespace();
        if (index >= text.length()) throw new IllegalArgumentException("missing value");
        Object value;
        if (text.charAt(index) == '"') value = string();
        else if (text.startsWith("true", index)) { index += 4; value = Boolean.TRUE; }
        else if (text.startsWith("false", index)) { index += 5; value = Boolean.FALSE; }
        else if (text.startsWith("null", index)) { index += 4; value = null; }
        else value = integer();
        result.put(key, value);
        whitespace();
        if (take('}')) break;
        expect(','); whitespace();
      }
      whitespace();
      if (index != text.length()) throw new IllegalArgumentException("trailing input");
      return result;
    }

    private String string() {
      expect('"');
      StringBuilder result = new StringBuilder();
      while (index < text.length()) {
        char character = text.charAt(index++);
        if (character == '"') return result.toString();
        if (character == '\\') {
          if (index >= text.length()) throw new IllegalArgumentException("escape");
          char escaped = text.charAt(index++);
          if (escaped == '"' || escaped == '\\' || escaped == '/') result.append(escaped);
          else if (escaped == 'b') result.append('\b');
          else if (escaped == 'f') result.append('\f');
          else if (escaped == 'n') result.append('\n');
          else if (escaped == 'r') result.append('\r');
          else if (escaped == 't') result.append('\t');
          else if (escaped == 'u') {
            int first = hex4();
            if (first >= 0xd800 && first <= 0xdbff) {
              if (index + 2 > text.length() || text.charAt(index) != '\\'
                  || text.charAt(index + 1) != 'u') throw new IllegalArgumentException("unpaired surrogate");
              index += 2;
              int second = hex4();
              if (second < 0xdc00 || second > 0xdfff) throw new IllegalArgumentException("unpaired surrogate");
              result.appendCodePoint(0x10000 + ((first - 0xd800) << 10) + second - 0xdc00);
            } else {
              if (first >= 0xdc00 && first <= 0xdfff) throw new IllegalArgumentException("unpaired surrogate");
              result.append((char) first);
            }
          } else throw new IllegalArgumentException("unsupported escape");
        } else {
          if (character < 0x20 || Character.isSurrogate(character)) {
            throw new IllegalArgumentException("invalid string");
          }
          result.append(character);
        }
      }
      throw new IllegalArgumentException("unterminated string");
    }

    private Long integer() {
      int start = index;
      if (index < text.length() && text.charAt(index) == '-') index++;
      int digitsStart = index;
      while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
      if (digitsStart == index || (index - digitsStart > 1 && text.charAt(digitsStart) == '0')) {
        throw new IllegalArgumentException("integer");
      }
      try {
        long value = Long.parseLong(text.substring(start, index));
        if (value > MAX_SAFE_INTEGER) throw new IllegalArgumentException("unsafe integer");
        return value;
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("integer", error);
      }
    }

    private int hex4() {
      if (index + 4 > text.length()) throw new IllegalArgumentException("unicode escape");
      int value = 0;
      for (int offset = 0; offset < 4; offset++) {
        int digit = Character.digit(text.charAt(index + offset), 16);
        if (digit < 0) throw new IllegalArgumentException("unicode escape");
        value = value * 16 + digit;
      }
      index += 4;
      return value;
    }

    private void whitespace() {
      while (index < text.length() && " \t\r\n".indexOf(text.charAt(index)) >= 0) index++;
    }
    private boolean take(char expected) {
      if (index < text.length() && text.charAt(index) == expected) { index++; return true; }
      return false;
    }
    private void expect(char expected) {
      if (!take(expected)) throw new IllegalArgumentException("expected " + expected);
    }
  }

  private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString();
  }

  private static String safeError(Exception error) {
    String message = error.getMessage();
    if (message == null || message.isEmpty()) return "invalid-control";
    return message.replaceAll("[^A-Za-z0-9 _-]", "_");
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }

  private static String stringArray(List<String> values) {
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) result.append(',');
      result.append(quote(values.get(index)));
    }
    return result.append(']').toString();
  }

  private static String quote(String value) {
    StringBuilder result = new StringBuilder("\"");
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\' || character == '"') result.append('\\').append(character);
      else if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
      else result.append(character);
    }
    return result.append('"').toString();
  }
}
