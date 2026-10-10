package com.hurma.mcpbuilder.mcp;

public record ToolResult(String text, boolean error) {
    public static ToolResult ok(String text) { return new ToolResult(text, false); }
    public static ToolResult err(String text) { return new ToolResult(text, true); }
}
