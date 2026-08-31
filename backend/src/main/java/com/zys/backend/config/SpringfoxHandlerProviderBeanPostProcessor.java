package com.zys.backend.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 兼容 Spring Boot 2.6+ 与 springfox 2.x 的临时修复：
 * 过滤掉 WebMvcRequestHandlerProvider.handlerMappings 中带有 PatternParser 的映射，避免 springfox NPE。
 */
@Component
public class SpringfoxHandlerProviderBeanPostProcessor implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof WebMvcRequestHandlerProvider) {
            Field field = ReflectionUtils.findField(bean.getClass(), "handlerMappings");
            if (field != null) {
                field.setAccessible(true);
                try {
                    @SuppressWarnings("unchecked")
                    List<RequestMappingInfoHandlerMapping> handlerMappings =
                            (List<RequestMappingInfoHandlerMapping>) field.get(bean);
                    List<RequestMappingInfoHandlerMapping> filtered = handlerMappings.stream()
                            // 只保留没有 patternParser 的项（老式 Ant 风格）
                            .filter(mapping -> mapping.getPatternParser() == null)
                            .collect(Collectors.toList());
                    // 替换回去
                    field.set(bean, filtered);
                } catch (IllegalAccessException e) {
                    throw new BeansException("Failed to set handlerMappings on springfox bean", e) {};
                }
            }
        }
        return bean;
    }
}