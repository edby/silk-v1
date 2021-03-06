package com.spark.bitrade.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * @author Zhang Jinwei
 * @date 2018年02月27日
 */
@Configuration
public class RestTemplateConfig {
    @Bean
    @LoadBalanced
    RestTemplate restTemplate() {

        // add|edit|del by  shenzucai 时间： 2018.05.29  原因：添加超时 start
        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(-1);
        requestFactory.setReadTimeout(-1);
        restTemplate.setRequestFactory(requestFactory);
        // add|edit|del by  shenzucai 时间： 2018.05.29  原因：添加超时 end
        return restTemplate;
    }

}
