package certification.glue;

import certification.generated.OrdersClient;
import certification.generated.OrdersClientFactory;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import sh.repost.client.RepostRuntime;

public final class OrdersClientCdiCreator implements SyntheticBeanCreator<OrdersClient> {
    public OrdersClientCdiCreator() { }

    @Override
    public OrdersClient create(Instance<Object> lookup, Parameters params) {
        return OrdersClientFactory.INSTANCE.create(lookup.select(RepostRuntime.class).get());
    }
}
