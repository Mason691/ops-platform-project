package com.aip.ops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Collections;

@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);

        RestTemplate restTemplate = new RestTemplate(factory);

        // 添加日志拦截器：记录每次代理请求的 URL、方法和耗时。
        // 不读取响应体（避免消费 InputStream），错误详情由各 Controller 的
        // catch 块通过异常对象（HttpClientErrorException 等）输出。
        restTemplate.setInterceptors(Collections.singletonList(new LoggingInterceptor()));

        return restTemplate;
    }

    /**
     * 轻量级日志拦截器。仅记录请求元数据和耗时，不读取响应体。
     */
    private static class LoggingInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            long start = System.currentTimeMillis();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                long elapsed = System.currentTimeMillis() - start;
                logStatus(request, response, elapsed);
                return response;
            } catch (IOException e) {
                long elapsed = System.currentTimeMillis() - start;
                log.error("RestTemplate {} {} — {} ({}ms)",
                        request.getMethod(), request.getURI(), e.getMessage(), elapsed);
                throw e;
            }
        }

        private void logStatus(HttpRequest request, ClientHttpResponse response, long elapsed) {
            try {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.debug("RestTemplate {} {} → {} ({}ms)",
                            request.getMethod(), request.getURI(), status, elapsed);
                } else {
                    log.warn("RestTemplate {} {} → {} ({}ms)",
                            request.getMethod(), request.getURI(), status, elapsed);
                }
            } catch (Exception ignored) {
                // 日志不应影响业务流程
            }
        }
    }
}
