package com.pacvue.mcpgty.tool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTools {

    @McpTool(name = "add", description = "Add two numbers together")
    public int add(
            @McpToolParam (description = "First number", required = true) int a,
            @McpToolParam (description = "Second number", required = true) int b
            ) {
        return a + b;
    }
}
