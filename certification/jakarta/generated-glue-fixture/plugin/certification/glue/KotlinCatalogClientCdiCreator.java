package certification.glue;

import certification.custom.KotlinCatalogClient;
import certification.custom.KotlinCatalogClientFactory;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import sh.repost.client.RepostRuntime;

public final class KotlinCatalogClientCdiCreator
        implements SyntheticBeanCreator<KotlinCatalogClient> {
    public KotlinCatalogClientCdiCreator() { }

    @Override
    public KotlinCatalogClient create(Instance<Object> lookup, Parameters params) {
        return KotlinCatalogClientFactory.INSTANCE.create(
                lookup.select(RepostRuntime.class).get());
    }
}
