package certification.glue;

import certification.custom.KotlinCatalogClient;
import certification.generated.OrdersClient;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.inject.Singleton;

public final class GeneratedClientsExtension implements BuildCompatibleExtension {
    public GeneratedClientsExtension() { }

    @Synthesis
    public void synthesize(SyntheticComponents components) {
        components.addBean(OrdersClient.class)
                .type(OrdersClient.class)
                .scope(Singleton.class)
                .createWith(OrdersClientCdiCreator.class);
        components.addBean(KotlinCatalogClient.class)
                .type(KotlinCatalogClient.class)
                .scope(Singleton.class)
                .createWith(KotlinCatalogClientCdiCreator.class);
    }
}
