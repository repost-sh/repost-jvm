package certification.spring;

import sh.repost.client.GeneratedRepostClientFactory;
import sh.repost.client.RepostRuntime;

public enum CertifiedClientFactory implements GeneratedRepostClientFactory<CertifiedClient> {
    INSTANCE;

    @Override
    public Class<CertifiedClient> clientType() {
        return CertifiedClient.class;
    }

    @Override
    public CertifiedClient create(RepostRuntime runtime) {
        return new CertifiedClient(runtime);
    }
}
