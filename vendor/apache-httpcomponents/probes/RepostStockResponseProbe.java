import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityConsumer;
import org.apache.hc.core5.util.ByteArrayBuffer;

/** Frozen stock-baseline side of the Patch 0001 response ownership regression. */
public final class RepostStockResponseProbe {
    private static final int BODY_BYTES = 1_048_576;
    private static final int EXPECTED_STOCK_FAILURE_EXIT = 17;

    private RepostStockResponseProbe() {}

    public static void main(String[] arguments) throws Exception {
        BasicAsyncEntityConsumer consumer = new BasicAsyncEntityConsumer();
        AtomicReference<byte[]> completed = new AtomicReference<>();
        consumer.streamStart(null, new FutureCallback<byte[]>() {
            @Override
            public void completed(byte[] result) {
                completed.set(result);
            }

            @Override
            public void failed(Exception failure) {
                throw new AssertionError("stock consumer failed before the frozen boundary", failure);
            }

            @Override
            public void cancelled() {
                throw new AssertionError("stock consumer cancelled without a configured limit");
            }
        });

        ByteBuffer source = ByteBuffer.wrap(new byte[BODY_BYTES + 1]);
        consumer.consume(source);
        consumer.streamEnd(Collections.emptyList());
        byte[] result = completed.get();
        ByteArrayBuffer retained = retainedBuffer(consumer);

        if (source.remaining() == 0
                && result != null
                && result.length == BODY_BYTES + 1
                && retained.array() != result
                && retained.capacity() >= BODY_BYTES + 1) {
            System.out.println(
                    "repost-stock-response-probe:FAIL:plus-one-accepted-with-full-duplicate");
            System.exit(EXPECTED_STOCK_FAILURE_EXIT);
        }
        System.out.println("repost-stock-response-probe:PASS");
    }

    private static ByteArrayBuffer retainedBuffer(BasicAsyncEntityConsumer consumer)
            throws Exception {
        java.lang.reflect.Field field = BasicAsyncEntityConsumer.class.getDeclaredField("buffer");
        field.setAccessible(true);
        return (ByteArrayBuffer) field.get(consumer);
    }
}
