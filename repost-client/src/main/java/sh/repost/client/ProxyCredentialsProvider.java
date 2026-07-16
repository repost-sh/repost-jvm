package sh.repost.client;

import java.net.PasswordAuthentication;

/** Supplies proxy credentials only when an explicitly configured proxy requests them. */
@FunctionalInterface
public interface ProxyCredentialsProvider {
    /**
     * Returns proxy credentials for the current connection attempt.
     *
     * @return proxy credentials for the current connection attempt
     */
    PasswordAuthentication getCredentials();
}
