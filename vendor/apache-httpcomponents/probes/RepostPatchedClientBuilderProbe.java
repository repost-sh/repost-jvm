import sh.repost.internal.apache.org.apache.hc.client5.http.config.RequestConfig;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import sh.repost.internal.apache.org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import sh.repost.internal.apache.org.apache.hc.core5.io.CloseMode;

/** Minimal Java 11 compile and startup probe for the relocated production client closure. */
public final class RepostPatchedClientBuilderProbe {
    private RepostPatchedClientBuilderProbe() {}

    public static void main(String[] arguments) throws Exception {
        RequestConfig requestConfig = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setExpectContinueEnabled(false)
                .setHardCancellationEnabled(false)
                .build();
        try (CloseableHttpAsyncClient client = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .disableRequestPriority()
                .build()) {
            client.start();
            client.close(CloseMode.IMMEDIATE);
        }
        System.out.println("repost-patched-client-builder-probe:ok");
    }
}
