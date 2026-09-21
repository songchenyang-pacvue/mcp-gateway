package com.pacvue.mcpgty;

import com.pacvue.mcpgty.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class McpGatewayJdkFx1Application {

    public static void main(String[] args) {
        SpringApplication.run(McpGatewayJdkFx1Application.class, args);
    }

}
