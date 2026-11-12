package com.aip.ops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    /** 通用 RestTemplate：短超时，用于普通 API 调用。标记为 @Primary 作为默认注入。 */
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        return buildRestTemplate(5_000, 10_000);
    }

    /**
     * 微信 OAuth 专用 RestTemplate：不挂日志拦截器。
     * <p>
     * 换取 openid 的 URL 含 appSecret 与一次性 code，若走通用 RestTemplate 的
     * LoggingInterceptor，异常/非 2xx 时会打印完整 URI，造成密钥泄漏。
     */
    @Bean
    @Qualifier("wechatRestTemplate")
    public RestTemplate wechatRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    /**
     * AI 专用 RestTemplate：长超时，用于 AI 对话代理（ReAct 循环可能多轮 LLM 调用）。
     * 员工 AI 助手尤其耗时：至少 2-3 轮 DeepSeek 调用 + execute_python 工具执行。
     */
    @Bean
    @Qualifier("aiRestTemplate")
    public RestTemplate aiRestTemplate() {
        return buildRestTemplate(10_000, 120_000);
    }

    /**
     * ASR 专用 RestTemplate：超长超时，用于语音识别代理。
     * 音频可达 50MB，第三方 ASR（SiliconFlow）处理大文件可能需要 2-5 分钟。
     */
    @Bean
    @Qualifier("asrRestTemplate")
    public RestTemplate asrRestTemplate() {
        return buildRestTemplate(15_000, 300_000);
    }

    private RestTemplate buildRestTemplate(int connectTimeout, int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(List.of(new LoggingInterceptor()));
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
