package com.hurma.mcpbuilder.mcp;

/** Thrown by tools for user-facing errors (bad parameters, limits, ...). */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }
}
