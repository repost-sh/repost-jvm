package certification.custom;

import sh.repost.client.RepostRuntime;

public final class KotlinCatalogClient {
    private final RepostRuntime runtime;

    KotlinCatalogClient(RepostRuntime runtime) {
        this.runtime = runtime;
    }

    public RepostRuntime runtime() {
        return runtime;
    }
}
