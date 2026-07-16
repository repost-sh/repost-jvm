package certification.generated;

import sh.repost.client.RepostRuntime;

public final class OrdersClient {
    private final RepostRuntime runtime;

    OrdersClient(RepostRuntime runtime) {
        this.runtime = runtime;
    }

    public RepostRuntime runtime() {
        return runtime;
    }
}
