package com.hurma.mcpbuilder.client;

import com.hurma.mcpbuilder.config.McpConfig;
import com.hurma.mcpbuilder.mcp.McpServerManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Settings screen, opened from Mod Menu. Built from vanilla widgets, no extra dependencies. */
public class McpConfigScreen extends Screen {
    private record Label(Text text, int x, int y) {}

    private static final int COL_WIDTH = 150;

    private final Screen parent;
    private final List<Label> labels = new ArrayList<>();
    private TextFieldWidget hostField;
    private TextFieldWidget portField;
    private TextFieldWidget tokenField;
    private TextFieldWidget playerField;
    private TextFieldWidget maxBlocksField;
    private TextFieldWidget undoField;
    private boolean enabled;
    private boolean allowCommands;
    private boolean chatNotifications;
    private boolean autoConnect;
    private Text message;
    private boolean messageIsError;

    public McpConfigScreen(Screen parent) {
        super(Text.translatable("mcpbuilder.config.title"));
        this.parent = parent;
        McpConfig c = McpConfig.get();
        this.enabled = c.enabled;
        this.allowCommands = c.allowCommands;
        this.chatNotifications = c.chatNotifications;
        this.autoConnect = c.autoConnectBlocks;
    }

    @Override
    protected void init() {
        labels.clear();
        McpConfig c = McpConfig.get();
        int left = this.width / 2 - COL_WIDTH - 5;
        int right = this.width / 2 + 5;
        int y = 30;

        hostField = field("mcpbuilder.config.host", left, y, keep(hostField, c.host), 253);
        portField = field("mcpbuilder.config.port", right, y, keep(portField, String.valueOf(c.port)), 5);
        y += 34;
        tokenField = field("mcpbuilder.config.token", left, y, keep(tokenField, c.authToken), 128);
        playerField = field("mcpbuilder.config.target_player", right, y, keep(playerField, c.targetPlayer), 32);
        y += 34;
        maxBlocksField = field("mcpbuilder.config.max_blocks", left, y, keep(maxBlocksField, String.valueOf(c.maxBlocksPerRequest)), 9);
        undoField = field("mcpbuilder.config.undo_history", right, y, keep(undoField, String.valueOf(c.undoHistorySize)), 4);
        y += 36;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(enabled)
                .build(left, y, COL_WIDTH, 20, Text.translatable("mcpbuilder.config.enabled"), (button, value) -> enabled = value));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(allowCommands)
                .build(right, y, COL_WIDTH, 20, Text.translatable("mcpbuilder.config.allow_commands"), (button, value) -> allowCommands = value));
        y += 24;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(chatNotifications)
                .build(left, y, COL_WIDTH, 20, Text.translatable("mcpbuilder.config.chat_notifications"), (button, value) -> chatNotifications = value));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(autoConnect)
                .build(right, y, COL_WIDTH, 20, Text.translatable("mcpbuilder.config.auto_connect"), (button, value) -> autoConnect = value));
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.translatable("mcpbuilder.config.generate_token"), b -> tokenField.setText(randomToken()))
                .dimensions(left, y, COL_WIDTH, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("mcpbuilder.config.copy_endpoint"), b -> copyEndpoint())
                .dimensions(right, y, COL_WIDTH, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("mcpbuilder.config.save"), b -> save())
                .dimensions(this.width / 2 - COL_WIDTH - 5, this.height - 28, COL_WIDTH, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
                .dimensions(this.width / 2 + 5, this.height - 28, COL_WIDTH, 20).build());
    }

    private static String keep(TextFieldWidget old, String fallback) {
        return old != null ? old.getText() : fallback;
    }

    private TextFieldWidget field(String labelKey, int x, int y, String value, int maxLength) {
        labels.add(new Label(Text.translatable(labelKey), x, y));
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y + 11, COL_WIDTH, 18, Text.translatable(labelKey));
        f.setMaxLength(maxLength);
        f.setText(value);
        addDrawableChild(f);
        return f;
    }

    private static String randomToken() {
        SecureRandom random = new SecureRandom();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private String currentEndpoint() {
        String host = hostField.getText().trim();
        if (host.isEmpty() || host.equals("0.0.0.0")) host = "127.0.0.1";
        String url = "http://" + host + ":" + portField.getText().trim() + "/mcp";
        String token = tokenField.getText().trim();
        return token.isEmpty() ? url : url + "?token=" + token;
    }

    private void copyEndpoint() {
        String url = currentEndpoint();
        if (this.client != null) this.client.keyboard.setClipboard(url);
        message = Text.translatable("mcpbuilder.config.copied", url);
        messageIsError = false;
    }

    private void save() {
        int port;
        int maxBlocks;
        int undo;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            message = Text.translatable("mcpbuilder.config.error.port");
            messageIsError = true;
            return;
        }
        try {
            maxBlocks = Integer.parseInt(maxBlocksField.getText().trim());
            undo = Integer.parseInt(undoField.getText().trim());
            if (maxBlocks < 1 || undo < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            message = Text.translatable("mcpbuilder.config.error.number");
            messageIsError = true;
            return;
        }

        McpConfig c = McpConfig.get();
        c.enabled = enabled;
        c.host = hostField.getText().trim();
        c.port = port;
        c.authToken = tokenField.getText().trim();
        c.targetPlayer = playerField.getText().trim();
        c.maxBlocksPerRequest = maxBlocks;
        c.undoHistorySize = undo;
        c.allowCommands = allowCommands;
        c.chatNotifications = chatNotifications;
        c.autoConnectBlocks = autoConnect;
        c.sanitize();
        McpConfig.save();
        McpServerManager.applySettings();
        close();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
        for (Label label : labels) {
            context.drawTextWithShadow(this.textRenderer, label.text(), label.x(), label.y(), 0xA0A0A0);
        }
        Text status;
        int color;
        if (message != null) {
            status = message;
            color = messageIsError ? 0xFF5555 : 0x55FF55;
        } else if (McpServerManager.isRunning()) {
            status = Text.translatable("mcpbuilder.config.status.running", McpServerManager.endpoint());
            color = 0x55FF55;
        } else {
            status = Text.translatable("mcpbuilder.config.status.stopped", McpServerManager.endpoint());
            color = 0xAAAAAA;
        }
        context.drawCenteredTextWithShadow(this.textRenderer, status, this.width / 2, this.height - 42, color);
    }
}
