package sh.repost.client.spring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

final class RepostActuatorRedactionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RepostClientAutoConfiguration.class));

    @Test
    void configPropsEnvAndHealthNeverExposeApiKey() {
        String secret = "sentinel-actuator-api-key";
        runner.withPropertyValues("repost.client.api-key=" + secret).run(context -> {
            ConfigurationPropertiesReportEndpoint configProps =
                    new ConfigurationPropertiesReportEndpoint(Collections.emptyList(), Show.NEVER);
            configProps.setApplicationContext(context.getSourceApplicationContext());
            var report = configProps.configurationProperties();
            var repost = report.getContexts().values().stream()
                    .flatMap(application -> application.getBeans().values().stream())
                    .filter(bean -> "repost.client".equals(bean.getPrefix()))
                    .findFirst()
                    .orElseThrow();
            String configSurface = repost.getProperties() + " " + repost.getInputs();

            EnvironmentEndpoint environment = new EnvironmentEndpoint(
                    context.getSourceApplicationContext().getEnvironment(),
                    Collections.emptyList(),
                    Show.NEVER);
            var environmentEntry = environment.environmentEntry("repost.client.api-key");
            assertNotNull(environmentEntry);
            String environmentSurface = environmentEntry.getPropertySources().toString();
            String healthSurface =
                    context.getBean(RepostRuntimeHealthIndicator.class).health().toString();

            assertFalse(configSurface.contains(secret), configSurface);
            assertFalse(environmentSurface.contains(secret), environmentSurface);
            assertFalse(healthSurface.contains(secret), healthSurface);
        });
    }
}
