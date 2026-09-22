package com.pacvue.mcpgty;

import com.pacvue.mcpgty.config.GatewayProperties;
import com.pacvue.mcpgty.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, SecurityProperties.class})
public class McpGatewayJdkFx1Application {

    public static void main(String[] args) {
        SpringApplication.run(McpGatewayJdkFx1Application.class, args);
    }

}
