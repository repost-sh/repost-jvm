package sh.repost.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Resolves an already validated ASCII endpoint host. */
@FunctionalInterface
public interface DnsResolver {
    /**
     * Resolves a host without consulting hidden proxy or system configuration.
     *
     * @param asciiHost validated ASCII host
     * @return resolved addresses in preferred connection order
     * @throws UnknownHostException when the host cannot be resolved
     */
    List<InetAddress> resolve(String asciiHost) throws UnknownHostException;
}
