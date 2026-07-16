package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import sh.repost.client.RepostRuntime;

final class RepostClientAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RepostClientAutoConfiguration.class))
            .withPropertyValues("repost.client.api-key=test-key");

    @Test
    void createsOneDefaultRuntime() {
        contextRunner.run(context -> assertEquals(1, context.getBeansOfType(RepostRuntime.class).size()));
    }
}
