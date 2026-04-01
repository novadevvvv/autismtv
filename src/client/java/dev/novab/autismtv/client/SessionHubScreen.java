package dev.novab.autismtv.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

public final class SessionHubScreen extends Screen {
    private static final int CARD_PADDING = 18;
    private static final int ROW_HEIGHT = 42;
    private static final int VIEWER_ROW_HEIGHT = 28;
    private static final int CARD_GAP = 16;
    private static final int TAB_GAP = 8;
    private static final long ENTRANCE_ANIMATION_MS = 220L;

    private final Screen parent;
    private HubTab activeTab;
    private long openedAtMs;

    private List<PeerShareTransport.SessionInfo> sessions = List.of();
    private List<PeerShareTransport.ViewerInfo> hostedViewers = List.of();
    private int selectedSessionIndex = -1;
    private int selectedViewerIndex = -1;
    private long lastRefreshMs;

    private String passwordText = "";
    private String sessionName = "AutismTV Session";
    private String sessionDescription = "";
    private String sessionPassword = "";
    private boolean defaultAllowClicks = true;
    private boolean defaultAllowTyping = true;
    private int creationStep;

    private TextFieldWidget passwordField;
    private TextFieldWidget nameField;
    private TextFieldWidget descriptionField;
    private TextFieldWidget createPasswordField;

    public SessionHubScreen(Screen parent) {
        this(parent, HubTab.ACTIVE_SESSIONS);
    }

    public SessionHubScreen(Screen parent, HubTab initialTab) {
        super(Text.literal("AutismTV Sessions"));
        this.parent = parent;
        this.activeTab = initialTab;
    }

    @Override
    protected void init() {
        if (this.openedAtMs == 0L) {
            this.openedAtMs = System.currentTimeMillis();
        }

        this.refreshSessions(true);
        this.refreshViewers();
        Layout layout = this.getLayout();

        this.addNavigationButtons(layout);

        if (this.activeTab == HubTab.ACTIVE_SESSIONS) {
            this.initActiveSessions(layout);
        } else if (this.activeTab == HubTab.CREATION) {
            this.initCreation(layout);
        } else {
            this.initMySessions(layout);
        }
    }

    private void initActiveSessions(Layout layout) {
        int sideX = layout.rightCardX + CARD_PADDING;
        int fieldY = layout.rightCardY + 206;
        int fullWidth = layout.rightCardWidth - (CARD_PADDING * 2);
        int splitWidth = (fullWidth - 10) / 2;

        this.passwordField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, sideX, fieldY, fullWidth, 20,
                Text.literal("Password")));
        this.passwordField.setText(this.passwordText);
        this.passwordField.setMaxLength(64);
        this.passwordField.setPlaceholder(Text.literal("Only needed for locked sessions"));
        this.passwordField.setChangedListener(value -> this.passwordText = value);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> {
            this.refreshSessions(true);
            SessionNotificationCenter.info("Session list refreshed");
        }).dimensions(sideX, layout.rightCardY + layout.rightCardHeight - 70, splitWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Join Selected"), button -> this.joinSelected())
            .dimensions(sideX + splitWidth + 10, layout.rightCardY + layout.rightCardHeight - 70, splitWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Go To Creation"), button -> this.switchTab(HubTab.CREATION))
            .dimensions(sideX, layout.rightCardY + layout.rightCardHeight - 42, splitWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("My Session View"), button -> this.switchTab(HubTab.MY_SESSIONS))
            .dimensions(sideX + splitWidth + 10, layout.rightCardY + layout.rightCardHeight - 42, splitWidth, 20).build());
    }

    private void initCreation(Layout layout) {
        int fieldX = layout.contentLeft + CARD_PADDING;
        int fieldY = layout.bodyTop + 96;
        int fieldWidth = layout.contentWidth - (CARD_PADDING * 2);
        int buttonY = layout.bodyTop + layout.bodyHeight - 42;

        if (this.creationStep == 0) {
            this.nameField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 22, Text.literal("Session name")));
            this.nameField.setMaxLength(48);
            this.nameField.setText(this.sessionName);
            this.nameField.setChangedListener(value -> this.sessionName = value);
        } else if (this.creationStep == 1) {
            this.descriptionField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 22,
                    Text.literal("Session description")));
            this.descriptionField.setMaxLength(96);
            this.descriptionField.setText(this.sessionDescription);
            this.descriptionField.setPlaceholder(Text.literal("What are you sharing right now?"));
            this.descriptionField.setChangedListener(value -> this.sessionDescription = value);
        } else if (this.creationStep == 2) {
            this.createPasswordField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, fieldX, fieldY, fieldWidth, 22,
                    Text.literal("Password")));
            this.createPasswordField.setMaxLength(64);
            this.createPasswordField.setText(this.sessionPassword);
            this.createPasswordField.setPlaceholder(Text.literal("Leave blank if anyone nearby can join"));
            this.createPasswordField.setChangedListener(value -> this.sessionPassword = value);
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal(this.creationStep == 0 ? "Back To Browse" : "Back"), button -> {
            if (this.creationStep == 0) {
                this.switchTab(HubTab.ACTIVE_SESSIONS);
            } else {
                this.creationStep--;
                this.clearAndInit();
            }
        }).dimensions(fieldX, buttonY, 164, 20).build());

        if (this.creationStep < 3) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Next"), button -> {
                this.creationStep++;
                this.clearAndInit();
            }).dimensions(fieldX + 174, buttonY, 164, 20).build());
        } else {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Create Session"), button -> this.createSession())
                    .dimensions(fieldX + 174, buttonY, 164, 20).build());
        }

        if (this.creationStep == 3) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal(defaultAllowClicks ? "Default Clicks: Allowed" : "Default Clicks: Blocked"), button -> {
                this.defaultAllowClicks = !this.defaultAllowClicks;
                this.clearAndInit();
            }).dimensions(fieldX, fieldY + 18, fieldWidth, 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(defaultAllowTyping ? "Default Typing: Allowed" : "Default Typing: Blocked"), button -> {
                this.defaultAllowTyping = !this.defaultAllowTyping;
                this.clearAndInit();
            }).dimensions(fieldX, fieldY + 50, fieldWidth, 20).build());
        }
    }

    private void initMySessions(Layout layout) {
        PeerShareTransport.SessionInfo hosted = PeerShareTransport.getHostedSessionInfo();
        PeerShareTransport.SessionInfo remote = PeerShareTransport.getCurrentRemoteSession();
        int contentX = layout.rightCardX + CARD_PADDING;

        if (hosted != null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Feed Settings"), button -> this.client.setScreen(new SharescreenSettingsScreen(this)))
                    .dimensions(contentX, layout.rightCardY + 112, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(hosted.defaultAllowClicks() ? "Default Clicks: Allowed" : "Default Clicks: Blocked"), button -> {
                PeerShareTransport.updateDefaultPermissions(!hosted.defaultAllowClicks(), hosted.defaultAllowTyping());
                SessionNotificationCenter.success("Default click permission updated");
                this.refreshSessions(true);
                this.clearAndInit();
            }).dimensions(contentX, layout.rightCardY + 142, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal(hosted.defaultAllowTyping() ? "Default Typing: Allowed" : "Default Typing: Blocked"), button -> {
                PeerShareTransport.updateDefaultPermissions(hosted.defaultAllowClicks(), !hosted.defaultAllowTyping());
                SessionNotificationCenter.success("Default typing permission updated");
                this.refreshSessions(true);
                this.clearAndInit();
            }).dimensions(contentX, layout.rightCardY + 170, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Stop Session"), button -> {
                PeerShareTransport.stopHosting();
                this.refreshSessions(true);
                this.refreshViewers();
                SessionNotificationCenter.info("Your hosted session was stopped");
                this.clearAndInit();
            }).dimensions(contentX, layout.rightCardY + layout.rightCardHeight - 42, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());

            if (this.selectedViewerIndex >= 0 && this.selectedViewerIndex < this.hostedViewers.size()) {
                PeerShareTransport.ViewerInfo viewer = this.hostedViewers.get(this.selectedViewerIndex);
                this.addDrawableChild(ButtonWidget.builder(Text.literal(viewer.allowClicks() ? "Selected Viewer Clicks: Allowed" : "Selected Viewer Clicks: Blocked"), button -> {
                    PeerShareTransport.updateViewerPermissions(viewer.viewerId(), !viewer.allowClicks(), viewer.allowTyping());
                    this.refreshViewers();
                    SessionNotificationCenter.success("Updated click permission for " + viewer.viewerName());
                    this.clearAndInit();
                }).dimensions(layout.leftCardX + CARD_PADDING, layout.leftCardY + layout.leftCardHeight - 70, layout.leftCardWidth - (CARD_PADDING * 2), 20).build());

                this.addDrawableChild(ButtonWidget.builder(Text.literal(viewer.allowTyping() ? "Selected Viewer Typing: Allowed" : "Selected Viewer Typing: Blocked"), button -> {
                    PeerShareTransport.updateViewerPermissions(viewer.viewerId(), viewer.allowClicks(), !viewer.allowTyping());
                    this.refreshViewers();
                    SessionNotificationCenter.success("Updated typing permission for " + viewer.viewerName());
                    this.clearAndInit();
                }).dimensions(layout.leftCardX + CARD_PADDING, layout.leftCardY + layout.leftCardHeight - 42, layout.leftCardWidth - (CARD_PADDING * 2), 20).build());
            }
        } else if (remote != null) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), button -> {
                PeerShareTransport.disconnect();
                this.refreshSessions(true);
                SessionNotificationCenter.info("Disconnected from session");
                this.clearAndInit();
            }).dimensions(contentX, layout.rightCardY + layout.rightCardHeight - 42, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());
        } else {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Create A Session"), button -> this.switchTab(HubTab.CREATION))
                    .dimensions(contentX, layout.rightCardY + layout.rightCardHeight - 70, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Browse Active Sessions"), button -> this.switchTab(HubTab.ACTIVE_SESSIONS))
                    .dimensions(contentX, layout.rightCardY + layout.rightCardHeight - 42, layout.rightCardWidth - (CARD_PADDING * 2), 20).build());
        }
    }

    private void switchTab(HubTab tab) {
        if (this.activeTab == tab) {
            return;
        }

        this.activeTab = tab;
        this.clearAndInit();
    }

    private void refreshSessions(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - this.lastRefreshMs < 800L) {
            return;
        }

        this.lastRefreshMs = now;
        this.sessions = PeerShareTransport.getDiscoveredSessions();

        if (this.sessions.isEmpty()) {
            this.selectedSessionIndex = -1;
        } else if (this.selectedSessionIndex < 0 || this.selectedSessionIndex >= this.sessions.size()) {
            this.selectedSessionIndex = 0;
        }
    }

    private void refreshViewers() {
        this.hostedViewers = PeerShareTransport.getConnectedViewers();
        if (this.hostedViewers.isEmpty()) {
            this.selectedViewerIndex = -1;
        } else if (this.selectedViewerIndex < 0 || this.selectedViewerIndex >= this.hostedViewers.size()) {
            this.selectedViewerIndex = 0;
        }
    }

    private void joinSelected() {
        if (this.selectedSessionIndex < 0 || this.selectedSessionIndex >= this.sessions.size()) {
            SessionNotificationCenter.error("Select a session first");
            return;
        }

        PeerShareTransport.SessionInfo session = this.sessions.get(this.selectedSessionIndex);
        if (session.localHost()) {
            SessionNotificationCenter.info("That session is already yours");
            return;
        }

        try {
            PeerShareTransport.join(session, this.passwordText);
            this.activeTab = HubTab.MY_SESSIONS;
            this.refreshSessions(true);
            SessionNotificationCenter.success("Connected to " + session.name());
            this.clearAndInit();
        } catch (Exception exception) {
            SessionNotificationCenter.error("Join failed: " + exception.getMessage());
            if (this.passwordField != null) {
                this.passwordField.setSuggestion("Join failed: " + exception.getMessage());
            }
        }
    }

    private void createSession() {
        String trimmedName = this.sessionName == null ? "" : this.sessionName.trim();
        if (trimmedName.isEmpty()) {
            SessionNotificationCenter.error("Session name is required");
            return;
        }

        try {
            PeerShareTransport.startHosting(trimmedName, this.sessionDescription, this.sessionPassword,
                    PeerShareTransport.getDefaultPort(), this.defaultAllowClicks, this.defaultAllowTyping);
            LocalPanelController.onSettingsChanged();
            this.activeTab = HubTab.MY_SESSIONS;
            this.creationStep = 0;
            this.refreshSessions(true);
            this.refreshViewers();
            SessionNotificationCenter.success("Session created: " + trimmedName);
            this.clearAndInit();
        } catch (Exception exception) {
            SessionNotificationCenter.error("Create failed: " + exception.getMessage());
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }

        Layout layout = this.getLayout();
        if (this.activeTab == HubTab.ACTIVE_SESSIONS) {
            int rowX = layout.leftCardX + CARD_PADDING;
            int rowY = layout.leftCardY + 44;
            int rowWidth = layout.leftCardWidth - (CARD_PADDING * 2);

            for (int index = 0; index < Math.min(7, this.sessions.size()); index++) {
                int topY = rowY + (index * (ROW_HEIGHT + 8));
                if (click.x() >= rowX && click.x() <= rowX + rowWidth && click.y() >= topY && click.y() <= topY + ROW_HEIGHT) {
                    this.selectedSessionIndex = index;
                    return true;
                }
            }
        } else if (this.activeTab == HubTab.MY_SESSIONS && PeerShareTransport.getHostedSessionInfo() != null) {
            int rowX = layout.leftCardX + CARD_PADDING;
            int rowY = layout.leftCardY + 44;
            int rowWidth = layout.leftCardWidth - (CARD_PADDING * 2);

            for (int index = 0; index < Math.min(8, this.hostedViewers.size()); index++) {
                int topY = rowY + (index * (VIEWER_ROW_HEIGHT + 6));
                if (click.x() >= rowX && click.x() <= rowX + rowWidth && click.y() >= topY && click.y() <= topY + VIEWER_ROW_HEIGHT) {
                    this.selectedViewerIndex = index;
                    this.clearAndInit();
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.refreshSessions(false);
        this.refreshViewers();
        Layout layout = this.getLayout();

        context.fillGradient(0, 0, this.width, this.height, 0xFF0B1016, 0xFF07090D);
        context.fillGradient(0, 0, this.width, this.height / 2, 0x2F3DAA7A, 0x00000000);
        context.fill(layout.contentLeft - 40, 0, layout.contentLeft + 360, 140, 0x0FD6B16F);
        context.fill(this.width - 340, this.height - 260, this.width + 20, this.height + 20, 0x123A4F80);

        drawPanel(context, layout.contentLeft, 18, layout.contentWidth, 58, 0xD711151C, 0xFF45636D);
        context.fill(layout.contentLeft + 16, 30, layout.contentLeft + 22, 64, 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, this.title, layout.contentLeft + 34, 30, 0xFFF3F5F7);
        this.drawWrappedText(context, Text.literal(this.getHeaderSubtitle()), layout.contentLeft + 34, 48, layout.contentWidth - 220, 0xFFB8C3CC, 10);

        if (this.activeTab == HubTab.ACTIVE_SESSIONS) {
            this.renderActiveSessions(context, layout);
        } else if (this.activeTab == HubTab.CREATION) {
            this.renderCreation(context, layout);
        } else {
            this.renderMySessions(context, layout);
        }

        SessionNotificationCenter.render(context, this.textRenderer, this.width, this.height);
    this.updateWidgetAnimations();
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderActiveSessions(DrawContext context, Layout layout) {
        drawPanel(context, layout.leftCardX, layout.leftCardY, layout.leftCardWidth, layout.leftCardHeight, 0xD70F141A, 0xFF33424B);
        drawPanel(context, layout.rightCardX, layout.rightCardY, layout.rightCardWidth, layout.rightCardHeight, 0xD70E1319, 0xFF3D505D);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Nearby Sessions"), layout.leftCardX + CARD_PADDING, layout.leftCardY + 12, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Owner, players, and access defaults are visible before you join."),
                layout.leftCardX + CARD_PADDING, layout.leftCardY + 28, 0xFF90A0AA);

        int rowX = layout.leftCardX + CARD_PADDING;
        int rowY = layout.leftCardY + 44;
        int rowWidth = layout.leftCardWidth - (CARD_PADDING * 2);

        if (this.sessions.isEmpty()) {
            fillRect(context, rowX, rowY, rowWidth, 72, 0x6612181F);
            drawRectBorder(context, rowX, rowY, rowWidth, 72, 0x664F6874);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No active sessions found"), rowX + rowWidth / 2, rowY + 18, 0xFFD0D5DA);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Use Refresh or create a new one on another client."), rowX + rowWidth / 2, rowY + 36, 0xFF8FA0AB);
        } else {
            for (int index = 0; index < Math.min(7, this.sessions.size()); index++) {
                PeerShareTransport.SessionInfo session = this.sessions.get(index);
                int topY = rowY + (index * (ROW_HEIGHT + 8));
                int fill = this.selectedSessionIndex == index ? 0xAA26343D : 0x88303A43;
                fillRect(context, rowX, topY, rowWidth, ROW_HEIGHT, fill);
                drawRectBorder(context, rowX, topY, rowWidth, ROW_HEIGHT, this.selectedSessionIndex == index ? 0xFFD6B16F : 0x664F6874);
                context.drawTextWithShadow(this.textRenderer, Text.literal(session.name()), rowX + 10, topY + 7, 0xFFF2F4F5);
                context.drawTextWithShadow(this.textRenderer, Text.literal(this.trimLine(session.description(), layout.stacked ? 46 : 54)), rowX + 10, topY + 22, 0xFF8FA0AB);
                String detail = "Lead: " + session.leaderName() + "  |  Players: " + session.participantCount() + "  |  "
                    + (session.passwordProtected() ? "Locked" : "Open");
                int detailX = layout.stacked ? rowX + 10 : rowX + 230;
                int detailY = layout.stacked ? topY + 7 : topY + 7;
                context.drawTextWithShadow(this.textRenderer, Text.literal(this.trimLine(detail, layout.stacked ? 44 : 34)), detailX, detailY, 0xFFB4C0C9);
                if (session.localHost()) {
                    this.drawStatusChip(context, rowX + rowWidth - 108, topY + 22, 96, "Owned by you", 0xFF1F7A5C);
                }
            }
        }

        context.drawTextWithShadow(this.textRenderer, Text.literal("Session Detail"), layout.rightCardX + CARD_PADDING, layout.rightCardY + 12, 0xFFF2F4F5);
        PeerShareTransport.SessionInfo selected = this.getSelectedSession();
        if (selected == null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Select a session to inspect it."), layout.rightCardX + CARD_PADDING, layout.rightCardY + 42, 0xFF90A0AA);
            return;
        }

        int detailX = layout.rightCardX + CARD_PADDING;
        int detailY = layout.rightCardY + 46;
        fillRect(context, detailX, detailY, layout.rightCardWidth - (CARD_PADDING * 2), 116, 0x66080A0E);
        drawRectBorder(context, detailX, detailY, layout.rightCardWidth - (CARD_PADDING * 2), 116, 0x664F6874);
        context.drawTextWithShadow(this.textRenderer, Text.literal(selected.name()), detailX + 12, detailY + 12, 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Owner: " + selected.leaderName()), detailX + 12, detailY + 30, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Players: " + selected.participantCount()), detailX + 12, detailY + 46, 0xFFF2F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Default clicks: " + (selected.defaultAllowClicks() ? "allowed" : "blocked")), detailX + 12, detailY + 62, 0xFFB8C3CC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Default typing: " + (selected.defaultAllowTyping() ? "allowed" : "blocked")), detailX + 12, detailY + 78, 0xFFB8C3CC);
        this.drawWrappedText(context, Text.literal(selected.description().isBlank() ? "No description provided." : selected.description()),
            detailX + 12, detailY + 94, layout.rightCardWidth - (CARD_PADDING * 2) - 24, 0xFF90A0AA, 10);

        if (selected.localHost()) {
            this.drawWrappedText(context, Text.literal("Owner controls live under My Sessions."), detailX, detailY + 150,
                layout.rightCardWidth - (CARD_PADDING * 2), 0xFF90A0AA, 10);
        } else {
            this.drawWrappedText(context, Text.literal(selected.passwordProtected()
                ? "This session is locked. Enter the password below before joining."
                : "This session is open. You can join immediately."), detailX, detailY + 150,
                layout.rightCardWidth - (CARD_PADDING * 2), 0xFF90A0AA, 10);
        }
    }

    private void renderCreation(DrawContext context, Layout layout) {
        drawPanel(context, layout.contentLeft, layout.bodyTop, layout.contentWidth, layout.bodyHeight, 0xD70F141A, 0xFF33424B);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Creation Wizard"), layout.contentLeft + CARD_PADDING, layout.bodyTop + 12, 0xFFF2F4F5);
        this.drawWrappedText(context, Text.literal("Answer a few questions, then the session goes live automatically."),
            layout.contentLeft + CARD_PADDING, layout.bodyTop + 28, layout.contentWidth - 220, 0xFF90A0AA, 10);
        this.drawStatusChip(context, layout.contentLeft + layout.contentWidth - 146, layout.bodyTop + 12, 128,
                "Step " + (this.creationStep + 1) + " / 4", 0xFFD6B16F);

        int questionY = layout.bodyTop + 74;
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.getCreationQuestion()), layout.contentLeft + CARD_PADDING, questionY, 0xFFD6B16F);
        this.drawWrappedText(context, Text.literal(this.getCreationHint()), layout.contentLeft + CARD_PADDING, questionY + 18,
            layout.contentWidth - (CARD_PADDING * 2), 0xFFB8C3CC, 10);

        if (this.creationStep == 3) {
            fillRect(context, layout.contentLeft + CARD_PADDING, questionY + 52, layout.contentWidth - (CARD_PADDING * 2), 96, 0x66080A0E);
            drawRectBorder(context, layout.contentLeft + CARD_PADDING, questionY + 52, layout.contentWidth - (CARD_PADDING * 2), 96, 0x664F6874);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Session name: " + this.sessionName), layout.contentLeft + CARD_PADDING + 12, questionY + 64, 0xFFF2F4F5);
                this.drawWrappedText(context, Text.literal("Description: " + (this.sessionDescription.isBlank() ? "None" : this.sessionDescription)),
                    layout.contentLeft + CARD_PADDING + 12, questionY + 82, layout.contentWidth - (CARD_PADDING * 2) - 24, 0xFFF2F4F5, 10);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Password: " + (this.sessionPassword.isBlank() ? "Open session" : "Protected")),
                    layout.contentLeft + CARD_PADDING + 12, questionY + 100, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Defaults: clicks " + (this.defaultAllowClicks ? "on" : "off") + ", typing "
                    + (this.defaultAllowTyping ? "on" : "off")), layout.contentLeft + CARD_PADDING + 12, questionY + 118, 0xFFB8C3CC);
        }
    }

    private void renderMySessions(DrawContext context, Layout layout) {
        drawPanel(context, layout.leftCardX, layout.leftCardY, layout.leftCardWidth, layout.leftCardHeight, 0xD70F141A, 0xFF33424B);
        drawPanel(context, layout.rightCardX, layout.rightCardY, layout.rightCardWidth, layout.rightCardHeight, 0xD70E1319, 0xFF3D505D);
        PeerShareTransport.SessionInfo hosted = PeerShareTransport.getHostedSessionInfo();
        PeerShareTransport.SessionInfo remote = PeerShareTransport.getCurrentRemoteSession();

        if (hosted != null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Live Viewers"), layout.leftCardX + CARD_PADDING, layout.leftCardY + 12, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Owner-only permission controls stay here."), layout.leftCardX + CARD_PADDING, layout.leftCardY + 28,
                    0xFF90A0AA);
            this.renderViewerList(context, layout);

            context.drawTextWithShadow(this.textRenderer, Text.literal("My Hosted Session"), layout.rightCardX + CARD_PADDING, layout.rightCardY + 12, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Lead: " + hosted.leaderName()), layout.rightCardX + CARD_PADDING, layout.rightCardY + 32,
                    0xFFD6B16F);
                this.drawWrappedText(context, Text.literal(hosted.description().isBlank() ? "No description provided." : hosted.description()),
                    layout.rightCardX + CARD_PADDING, layout.rightCardY + 50, layout.rightCardWidth - (CARD_PADDING * 2), 0xFFB8C3CC, 10);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Players connected: " + hosted.participantCount()), layout.rightCardX + CARD_PADDING,
                    layout.rightCardY + 78, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Defaults: click " + (hosted.defaultAllowClicks() ? "on" : "off") + "  |  type "
                    + (hosted.defaultAllowTyping() ? "on" : "off")), layout.rightCardX + CARD_PADDING, layout.rightCardY + 96, 0xFF90A0AA);
        } else if (remote != null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("No owner controls"), layout.leftCardX + CARD_PADDING, layout.leftCardY + 12, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("You are a viewer in someone else's session, so management buttons stay hidden."),
                    layout.leftCardX + CARD_PADDING, layout.leftCardY + 28, 0xFF90A0AA);
            fillRect(context, layout.leftCardX + CARD_PADDING, layout.leftCardY + 54, layout.leftCardWidth - (CARD_PADDING * 2), 76, 0x6612181F);
            drawRectBorder(context, layout.leftCardX + CARD_PADDING, layout.leftCardY + 54, layout.leftCardWidth - (CARD_PADDING * 2), 76, 0x664F6874);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Remote clicks: " + (PeerShareTransport.canRemoteClick() ? "allowed" : "blocked")),
                    layout.leftCardX + CARD_PADDING + 12, layout.leftCardY + 74, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Remote typing: " + (PeerShareTransport.canRemoteType() ? "allowed" : "blocked")),
                    layout.leftCardX + CARD_PADDING + 12, layout.leftCardY + 94, 0xFFF2F4F5);

            context.drawTextWithShadow(this.textRenderer, Text.literal("Joined Session"), layout.rightCardX + CARD_PADDING, layout.rightCardY + 12, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Lead: " + remote.leaderName()), layout.rightCardX + CARD_PADDING, layout.rightCardY + 32,
                    0xFFD6B16F);
            context.drawTextWithShadow(this.textRenderer, Text.literal(remote.name()), layout.rightCardX + CARD_PADDING, layout.rightCardY + 50, 0xFFF2F4F5);
                this.drawWrappedText(context, Text.literal(remote.description().isBlank() ? "No description provided." : remote.description()),
                    layout.rightCardX + CARD_PADDING, layout.rightCardY + 68, layout.rightCardWidth - (CARD_PADDING * 2), 0xFFB8C3CC, 10);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Players in session: " + remote.participantCount()), layout.rightCardX + CARD_PADDING,
                    layout.rightCardY + 96, 0xFF90A0AA);
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Nothing active yet"), layout.leftCardX + CARD_PADDING, layout.leftCardY + 12, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Create a session or join an active one to manage it here."), layout.leftCardX + CARD_PADDING,
                    layout.leftCardY + 28, 0xFF90A0AA);
        }
    }

    private void renderViewerList(DrawContext context, Layout layout) {
        int rowX = layout.leftCardX + CARD_PADDING;
        int rowY = layout.leftCardY + 44;
        int rowWidth = layout.leftCardWidth - (CARD_PADDING * 2);
        int maxRows = 8;

        if (this.hostedViewers.isEmpty()) {
            fillRect(context, rowX, rowY, rowWidth, 64, 0x6612181F);
            drawRectBorder(context, rowX, rowY, rowWidth, 64, 0x664F6874);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No viewers connected"), rowX + rowWidth / 2, rowY + 18, 0xFFD0D5DA);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Per-viewer controls will appear as people join."), rowX + rowWidth / 2, rowY + 36,
                    0xFF8FA0AB);
            return;
        }

        for (int index = 0; index < Math.min(maxRows, this.hostedViewers.size()); index++) {
            PeerShareTransport.ViewerInfo viewer = this.hostedViewers.get(index);
            int topY = rowY + (index * (VIEWER_ROW_HEIGHT + 6));
            int fill = this.selectedViewerIndex == index ? 0xAA26343D : 0x88303A43;
            fillRect(context, rowX, topY, rowWidth, VIEWER_ROW_HEIGHT, fill);
            drawRectBorder(context, rowX, topY, rowWidth, VIEWER_ROW_HEIGHT, this.selectedViewerIndex == index ? 0xFFD6B16F : 0x664F6874);
            context.drawTextWithShadow(this.textRenderer, Text.literal(viewer.viewerName()), rowX + 10, topY + 8, 0xFFF2F4F5);
            context.drawTextWithShadow(this.textRenderer, Text.literal("click " + (viewer.allowClicks() ? "on" : "off") + "  |  type "
                    + (viewer.allowTyping() ? "on" : "off")), rowX + 150, topY + 8, 0xFF8FA0AB);
        }
    }

    private String getHeaderSubtitle() {
        return switch (this.activeTab) {
            case ACTIVE_SESSIONS -> "Browse active sessions, inspect the owner, and refresh discovery manually.";
            case CREATION -> "Walk through session setup question by question before you publish it.";
            case MY_SESSIONS -> "Hosted settings and viewer permissions live here, not in the old options entry.";
        };
    }

    private String getCreationQuestion() {
        return switch (this.creationStep) {
            case 0 -> "1. What should the session be called?";
            case 1 -> "2. Add a short description.";
            case 2 -> "3. Does it need a password?";
            default -> "4. Choose the default permissions.";
        };
    }

    private String getCreationHint() {
        return switch (this.creationStep) {
            case 0 -> "Keep it short. This name is what nearby players see in the session browser.";
            case 1 -> "Describe the stream or the task so people know what they are joining.";
            case 2 -> "Leave it blank for an open session. Fill it in if you want controlled access.";
            default -> "These defaults apply when a new viewer joins. You can still change each viewer later.";
        };
    }

    private PeerShareTransport.SessionInfo getSelectedSession() {
        if (this.selectedSessionIndex < 0 || this.selectedSessionIndex >= this.sessions.size()) {
            return null;
        }

        return this.sessions.get(this.selectedSessionIndex);
    }

    private String trimLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "...";
    }

    private Layout getLayout() {
        int contentWidth = Math.min(this.width - 24, 980);
        int contentLeft = (this.width - contentWidth) / 2;
        int navTop = 88;
        int navRows = contentWidth < 760 ? 2 : 1;
        int bodyTop = navTop + (navRows == 1 ? 32 : 60);
        int bodyHeight = Math.max(240, this.height - bodyTop - 26);
        boolean stacked = contentWidth < 880 || this.height < 700;
        int leftCardWidth = stacked ? contentWidth : (contentWidth - CARD_GAP) / 2;
        int rightCardWidth = stacked ? contentWidth : contentWidth - leftCardWidth - CARD_GAP;
        int leftCardX = contentLeft;
        int leftCardHeight = stacked ? Math.max(184, (bodyHeight - CARD_GAP) / 2) : bodyHeight;
        int rightCardX = stacked ? contentLeft : contentLeft + leftCardWidth + CARD_GAP;
        int rightCardY = stacked ? bodyTop + leftCardHeight + CARD_GAP : bodyTop;
        int rightCardHeight = stacked ? Math.max(184, bodyHeight - leftCardHeight - CARD_GAP) : bodyHeight;
        return new Layout(contentLeft, contentWidth, navTop, navRows, bodyTop, bodyHeight, stacked, leftCardX, bodyTop, leftCardWidth, leftCardHeight,
            rightCardX, rightCardY, rightCardWidth, rightCardHeight);
    }

        private void addNavigationButtons(Layout layout) {
        if (layout.navRows == 1) {
            int width = (layout.contentWidth - (TAB_GAP * 3)) / 4;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Active Sessions"), button -> this.switchTab(HubTab.ACTIVE_SESSIONS))
                .dimensions(layout.contentLeft, layout.navTop, width, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Creation"), button -> this.switchTab(HubTab.CREATION))
                .dimensions(layout.contentLeft + width + TAB_GAP, layout.navTop, width, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("My Sessions"), button -> this.switchTab(HubTab.MY_SESSIONS))
                .dimensions(layout.contentLeft + (width + TAB_GAP) * 2, layout.navTop, width, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> this.close())
                .dimensions(layout.contentLeft + (width + TAB_GAP) * 3, layout.navTop, width, 20).build());
            return;
        }

        int width = (layout.contentWidth - TAB_GAP) / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Active Sessions"), button -> this.switchTab(HubTab.ACTIVE_SESSIONS))
            .dimensions(layout.contentLeft, layout.navTop, width, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Creation"), button -> this.switchTab(HubTab.CREATION))
            .dimensions(layout.contentLeft + width + TAB_GAP, layout.navTop, width, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("My Sessions"), button -> this.switchTab(HubTab.MY_SESSIONS))
            .dimensions(layout.contentLeft, layout.navTop + 28, width, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> this.close())
            .dimensions(layout.contentLeft + width + TAB_GAP, layout.navTop + 28, width, 20).build());
        }

        private void updateWidgetAnimations() {
        float entrance = getEntranceProgress();
        for (ClickableWidget widget : this.children().stream().filter(ClickableWidget.class::isInstance).map(ClickableWidget.class::cast).toList()) {
            float hoverBoost = widget.isHovered() ? 0.12F : 0.0F;
            widget.setAlpha(Math.min(0.96F, 0.54F + (entrance * 0.26F) + hoverBoost));
        }
        }

        private void drawWrappedText(DrawContext context, Text text, int x, int y, int maxWidth, int color, int lineHeight) {
        List<OrderedText> wrapped = this.textRenderer.wrapLines(text, Math.max(40, maxWidth));
        int drawY = y;
        for (OrderedText line : wrapped) {
            context.drawTextWithShadow(this.textRenderer, line, x, drawY, color);
            drawY += lineHeight;
        }
        }

        private float getEntranceProgress() {
        long elapsed = System.currentTimeMillis() - this.openedAtMs;
        return Math.max(0.0F, Math.min(1.0F, elapsed / (float) ENTRANCE_ANIMATION_MS));
        }

    private void drawStatusChip(DrawContext context, int x, int y, int width, String label, int accentColor) {
        fillRect(context, x, y, width, 18, 0xCC0B0E12);
        drawRectBorder(context, x, y, width, 18, accentColor);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + width / 2, y + 5, 0xFFF2F4F5);
    }

    private static void drawPanel(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor) {
        fillRect(context, x, y, width, height, fillColor);
        drawRectBorder(context, x, y, width, height, borderColor);
    }

    private static void fillRect(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + height, color);
    }

    private static void drawRectBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    public enum HubTab {
        ACTIVE_SESSIONS,
        CREATION,
        MY_SESSIONS
    }

    private record Layout(int contentLeft, int contentWidth, int navTop, int navRows, int bodyTop, int bodyHeight, boolean stacked,
                          int leftCardX, int leftCardY, int leftCardWidth, int leftCardHeight, int rightCardX, int rightCardY,
                          int rightCardWidth, int rightCardHeight) {
    }
}