package certification.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import sh.repost.client.RepostRuntime;
import sh.repost.client.spring.RepostClientAutoConfiguration;

@AutoConfiguration(after = RepostClientAutoConfiguration.class)
public class CertifiedClientAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(CertifiedClient.class)
    CertifiedClient certifiedClient(RepostRuntime runtime) {
        return CertifiedClientFactory.INSTANCE.create(runtime);
    }
}
