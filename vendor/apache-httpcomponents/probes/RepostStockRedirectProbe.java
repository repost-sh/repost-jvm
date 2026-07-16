import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.impl.bootstrap.AsyncRequesterBootstrap;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.protocol.DefaultHttpProcessor;
import org.apache.hc.core5.http.protocol.RequestConnControl;
import org.apache.hc.core5.http.protocol.RequestContent;
import org.apache.hc.core5.http.protocol.RequestTargetHost;
import org.apache.hc.core5.http.protocol.RequestUserAgent;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityProducer;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.nio.support.BasicResponseConsumer;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/** Scenario-specific stock-client proof for portable case configuration-redirect-refused. */
public final class RepostStockRedirectProbe {
    private RepostStockRedirectProbe() {}

    public static void main(String[] arguments) throws Exception {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger redirectTargetHits = new AtomicInteger();
        try (ServerSocket peer = new ServerSocket()) {
            peer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            ExecutorService peerExecutor = Executors.newSingleThreadExecutor();
            Future<?> peerResult = peerExecutor.submit(
                    () -> serveRedirectOnce(peer, primaryHits, redirectTargetHits));
            try (HttpAsyncRequester client = AsyncRequesterBootstrap.bootstrap()
                    .setHttpProcessor(new DefaultHttpProcessor(
                            RequestTargetHost.INSTANCE,
                            RequestContent.INSTANCE,
                            RequestConnControl.INSTANCE,
                            new RequestUserAgent("repost-java/stock-certification")))
                    .create()) {
                client.start();
                String uri = "http://127.0.0.1:" + peer.getLocalPort() + "/v1/messages";
                BasicRequestProducer request = new BasicRequestProducer(
                        "POST",
                        java.net.URI.create(uri),
                        new BasicAsyncEntityProducer(
                                new byte[] {'{', '}'}, ContentType.APPLICATION_JSON));
                BasicResponseConsumer<byte[]> response =
                        new BasicResponseConsumer<>(new BasicAsyncEntityConsumer());
                Message<HttpResponse, byte[]> result = client.execute(
                                request, response, Timeout.ofSeconds(5), null)
                        .get(5, TimeUnit.SECONDS);
                check(result.getHead().getCode() == 302,
                        "configured client must expose the redirect response");
                client.close(CloseMode.IMMEDIATE);
            } finally {
                try {
                    peerResult.get(5, TimeUnit.SECONDS);
                } finally {
                    peerExecutor.shutdownNow();
                    check(peerExecutor.awaitTermination(5, TimeUnit.SECONDS),
                            "redirect peer terminated");
                }
            }
        }
        check(primaryHits.get() == 1, "one primary request observed");
        check(redirectTargetHits.get() == 0, "redirect target must remain untouched");
        System.out.println(
                "repost-stock-redirect-probe:ok:configuration-redirect-refused:primary=1:target=0");
    }

    private static void serveRedirectOnce(
            ServerSocket peer,
            AtomicInteger primaryHits,
            AtomicInteger redirectTargetHits) {
        try {
            peer.setSoTimeout(5_000);
            serve(peer.accept(), primaryHits, redirectTargetHits);
            peer.setSoTimeout(500);
            try {
                serve(peer.accept(), primaryHits, redirectTargetHits);
            } catch (SocketTimeoutException expected) {
                // The bounded quiet period proves no redirect follow-up was attempted.
            }
        } catch (IOException failure) {
            throw new AssertionError("redirect peer failed", failure);
        }
    }

    private static void serve(
            Socket socket,
            AtomicInteger primaryHits,
            AtomicInteger redirectTargetHits) throws IOException {
        try (Socket owned = socket) {
            owned.setSoTimeout(5_000);
            InputStream input = owned.getInputStream();
            String headers = readHeaders(input);
            String requestLine = headers.substring(0, headers.indexOf("\r\n"));
            if (requestLine.startsWith("POST /v1/messages ")) {
                check(headerCount(headers, "Expect") == 0, "expect-continue disabled");
                check(headerCount(headers, "Transfer-Encoding") == 0,
                        "fixed request cannot use transfer encoding");
                check(contentLength(headers) == 2, "primary request content length");
                byte[] body = input.readNBytes(2);
                check(body.length == 2 && body[0] == '{' && body[1] == '}',
                        "primary request body");
                primaryHits.incrementAndGet();
                writeResponse(
                        owned.getOutputStream(),
                        "HTTP/1.1 302 Found\r\n"
                                + "Location: /__fixture/redirect-target\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n");
            } else if (requestLine.startsWith("POST /__fixture/redirect-target ")
                    || requestLine.startsWith("GET /__fixture/redirect-target ")) {
                redirectTargetHits.incrementAndGet();
                writeResponse(
                        owned.getOutputStream(),
                        "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            } else {
                throw new AssertionError("unexpected request line: " + requestLine);
            }
        }
    }

    private static int contentLength(String headers) {
        int value = -1;
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                check(value == -1, "duplicate content length");
                value = Integer.parseInt(line.substring(15).trim());
            }
        }
        return value;
    }

    private static int headerCount(String headers, String name) {
        int count = 0;
        String prefix = name + ':';
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) count++;
        }
        return count;
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16_384) {
            int value = input.read();
            if (value == -1) throw new IOException("request ended before headers");
            bytes.write(value);
            matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : 0;
            if (matched == 4) {
                return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("request headers exceeded scenario bound");
    }

    private static void writeResponse(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
