package certification.glue;

import certification.custom.KotlinCatalogClient;
import certification.custom.KotlinCatalogClientFactory;
import certification.generated.OrdersClient;
import certification.generated.OrdersClientFactory;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import sh.repost.client.RepostRuntime;

@Dependent
public final class ExplicitClientProducers {
    @Produces
    @Singleton
    OrdersClient ordersClient(RepostRuntime runtime) {
        return OrdersClientFactory.INSTANCE.create(runtime);
    }

    @Produces
    @Singleton
    KotlinCatalogClient kotlinCatalogClient(RepostRuntime runtime) {
        return KotlinCatalogClientFactory.INSTANCE.create(runtime);
    }
}
