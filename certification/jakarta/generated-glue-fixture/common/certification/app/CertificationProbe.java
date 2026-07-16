package certification.app;

import certification.custom.KotlinCatalogClient;
import certification.generated.OrdersClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public final class CertificationProbe {
    @Inject OrdersClient orders;
    @Inject KotlinCatalogClient catalog;
    @Inject Instance<OrdersClient> programmaticOrders;

    void ready(@Observes @Initialized(ApplicationScoped.class) Object event) {
        if (orders.runtime() != catalog.runtime()
                || orders != programmaticOrders.get()
                || !"container".equals(ConfigProvider.getConfig()
                        .getValue("repost.certification", String.class))) {
            throw new IllegalStateException("Jakarta certification invariant failed");
        }
        System.out.println("REPOST_JAKARTA_READY clients=2 lookup=pass config=pass");
    }

    void stopped(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
        System.out.println("REPOST_JAKARTA_STOPPED");
    }
}
