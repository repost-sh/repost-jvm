package certification.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import sh.repost.client.RepostRuntime;
import sh.repost.client.spring.RepostClientAutoConfiguration;
import sh.repost.client.spring.RepostRuntimeHealthIndicator;

final class CertifiedApplicationTest {
    @Test
    void injectsGeneratedClientWithActuatorAndBothBridges() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RepostClientAutoConfiguration.class,
                        CertifiedClientAutoConfiguration.class))
                .withPropertyValues("repost.client.api-key=certification-key")
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .run(context -> {
                    RepostRuntime runtime = context.getBean(RepostRuntime.class);
                    assertSame(runtime, context.getBean(CertifiedClient.class).runtime());
                    assertFalse(context.getBean(RepostRuntimeHealthIndicator.class)
                            .health().getDetails().containsValue("certification-key"));
                });
    }
}
