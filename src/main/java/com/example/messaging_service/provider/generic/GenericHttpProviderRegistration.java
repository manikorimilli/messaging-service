package com.example.messaging_service.provider.generic;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Creates one {@link GenericHttpProvider} bean per block under {@code messaging.http-providers}.
 * They are ordinary {@code MessageProvider} beans, so {@code ProviderRegistry} picks them up next
 * to any hand-written provider class.
 */
@Configuration(proxyBeanMethods = false)
class GenericHttpProviderRegistration {

	// static: post-processors are created before other beans, so they must not need this config's instance.
	@Bean
	static BeanDefinitionRegistryPostProcessor genericHttpProviders(Environment environment) {
		return new Registrar(environment);
	}

	private static final class Registrar implements BeanDefinitionRegistryPostProcessor {

		private final Environment environment;

		Registrar(Environment environment) {
			this.environment = environment;
		}

		@Override
		public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
			GenericHttpProvidersProperties properties = Binder.get(environment)
				.bind("messaging", GenericHttpProvidersProperties.class)
				.orElseGet(() -> new GenericHttpProvidersProperties(null));

			properties.httpProviders().forEach((key, description) -> {
				// key and description are given; the RestClient.Builder is autowired from Spring Boot.
				AbstractBeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(GenericHttpProvider.class)
					.addConstructorArgValue(key)
					.addConstructorArgValue(description)
					.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
					.getBeanDefinition();
				registry.registerBeanDefinition("genericHttpProvider." + key, definition);
			});
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		}

	}

}
