package certification.generated;

import sh.repost.client.RepostRuntime;

public final class OrdersClientFactory {
    public static final OrdersClientFactory INSTANCE = new OrdersClientFactory();

    private OrdersClientFactory() { }

    public OrdersClient create(RepostRuntime runtime) {
        return new OrdersClient(runtime);
    }
}
