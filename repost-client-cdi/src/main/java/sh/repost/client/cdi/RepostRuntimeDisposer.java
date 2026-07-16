package sh.repost.client.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import sh.repost.client.RepostRuntime;

/** Closes the adapter-owned runtime exactly once when its application scope ends. */
public final class RepostRuntimeDisposer implements SyntheticBeanDisposer<RepostRuntime> {
    /** Creates the CDI-instantiated runtime disposer. */
    public RepostRuntimeDisposer() {}

    @Override
    public void dispose(RepostRuntime instance, Instance<Object> lookup, Parameters params) {
        instance.close();
    }
}
