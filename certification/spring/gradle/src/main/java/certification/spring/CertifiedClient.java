package certification.spring;

import sh.repost.client.RepostRuntime;

public final class CertifiedClient {
    private final RepostRuntime runtime;

    CertifiedClient(RepostRuntime runtime) {
        this.runtime = runtime;
    }

    public RepostRuntime runtime() {
        return runtime;
    }
}
