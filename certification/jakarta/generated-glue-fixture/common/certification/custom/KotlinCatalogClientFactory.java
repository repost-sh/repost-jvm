package certification.custom;

import sh.repost.client.RepostRuntime;

public final class KotlinCatalogClientFactory {
    public static final KotlinCatalogClientFactory INSTANCE = new KotlinCatalogClientFactory();

    private KotlinCatalogClientFactory() { }

    public KotlinCatalogClient create(RepostRuntime runtime) {
        return new KotlinCatalogClient(runtime);
    }
}
