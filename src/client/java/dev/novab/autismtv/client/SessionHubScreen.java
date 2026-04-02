package dev.novab.autismtv.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class SessionHubScreen extends Screen {
    private static final int OUTER_MARGIN = 18;
    private static final int SURFACE_GAP = 14;
    private static final int CARD_PADDING = 18;
    private static final int HEADER_HEIGHT = 74;
    private static final int TAB_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 22;
    private static final int ROW_HEIGHT = 52;
    private static final int VIEWER_ROW_HEIGHT = 32;
    private static final int ROW_GAP = 8;
    private static final int SMALL_GAP = 10;
    private static final long ENTRANCE_ANIMATION_MS = 180L;

    private final Screen parent;
    private final List<DecoratedButton> chromeButtons = new ArrayList<>();
    private HubTab activeTab;
    private HubTab previousTab;
    private long openedAtMs;
    private long tabTransitionStartedMs;
    private Integer previousGuiScale;
    private boolean forcedGuiScaleApplied;

    private List<PeerShareTransport.SessionInfo> sessions = List.of();
    private List<PeerShareTransport.ViewerInfo> hostedViewers = List.of();
    private int selectedSessionIndex = -1;
    private int selectedViewerIndex = -1;
    private int sessionScrollOffset;
    private int viewerScrollOffset;
    private long lastRefreshMs;

    private String passwordText = "";
    private String sessionName = "AutismTV Session";
    private String sessionDescription = "";
    private String sessionPassword = "";
    private boolean defaultAllowClicks = true;
    private boolean defaultAllowTyping = true;
    private boolean passwordModalOpen;

    private TextFieldWidget nameField;
    private TextFieldWidget descriptionField;
    private TextFieldWidget createPasswordField;
    private TextFieldWidget passwordModalField;
    private PeerShareTransport.SessionInfo pendingJoinSession;

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

        if (this.applyTemporaryGuiScale()) {
            return;
        }

        this.chromeButtons.clear();
        this.refreshSessions(true);
        this.refreshViewers();

        Layout layout = this.getLayout();
        this.initTabs(layout);

        if (this.activeTab == HubTab.ACTIVE_SESSIONS) {
            this.initActiveSessions(layout);
        } else if (this.activeTab == HubTab.CREATION) {
            this.initCreation(layout);
        } else {
            this.initMySessions(layout);
        }

        if (this.passwordModalOpen) {
            this.initPasswordModal(layout);
        }
    }

    private void initPasswordModal(Layout layout) {
        int modalWidth = Math.min(this.ui(420), this.width - this.ui(80));
        int modalHeight = this.ui(138);
        int modalX = (this.width - modalWidth) / 2;
        int modalY = Math.max(layout.bodyTop + this.ui(24), (this.height - modalHeight) / 2);
        int fieldX = modalX + this.ui(18);
        int fieldWidth = modalWidth - this.ui(36);

        this.passwordModalField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, fieldX, modalY + this.ui(66), fieldWidth, this.ui(20),
                Text.literal("Password")));
        this.passwordModalField.setMaxLength(64);
        this.passwordModalField.setText(this.passwordText);
        this.passwordModalField.setPlaceholder(Text.literal("Enter session password"));
        this.passwordModalField.setChangedListener(value -> this.passwordText = value);

        int actionWidth = (fieldWidth - this.ui(SMALL_GAP)) / 2;
        int buttonHeight = this.ui(BUTTON_HEIGHT);
        this.addChromeButton("Cancel", fieldX, modalY + this.ui(98), actionWidth, buttonHeight, ButtonTone.SUBTLE, true, () -> {
            this.passwordModalOpen = false;
            this.pendingJoinSession = null;
            this.passwordText = "";
            this.clearAndInit();
        });
        this.addChromeButton("Join Locked Session", fieldX + actionWidth + this.ui(SMALL_GAP), modalY + this.ui(98), actionWidth, buttonHeight,
                ButtonTone.PRIMARY, true, this::confirmPasswordJoin);
    }

    private void initTabs(Layout layout) {
        int gap = this.ui(8);
        int buttonWidth = layout.tabRows == 1
                ? (layout.contentWidth - (gap * 3)) / 4
                : (layout.contentWidth - gap) / 2;

        int activeX = layout.contentLeft;
        int creationX = layout.contentLeft + (layout.tabRows == 1 ? buttonWidth + gap : buttonWidth + gap);
        int mySessionsX = layout.tabRows == 1 ? creationX + buttonWidth + gap : layout.contentLeft;
        int closeX = layout.tabRows == 1 ? mySessionsX + buttonWidth + gap : layout.contentLeft + buttonWidth + gap;

        int tabHeight = this.ui(TAB_HEIGHT);
        int rowTwoY = layout.tabTop + tabHeight + gap;
        this.addChromeButton("Active Sessions", activeX, layout.tabTop, buttonWidth, tabHeight,
                this.activeTab == HubTab.ACTIVE_SESSIONS ? ButtonTone.TAB_ACTIVE : ButtonTone.TAB,
                () -> this.switchTab(HubTab.ACTIVE_SESSIONS));
        this.addChromeButton("Creation", creationX, layout.tabTop, buttonWidth, tabHeight,
                this.activeTab == HubTab.CREATION ? ButtonTone.TAB_ACTIVE : ButtonTone.TAB,
                () -> this.switchTab(HubTab.CREATION));

        if (layout.tabRows == 1) {
            this.addChromeButton("My Sessions", mySessionsX, layout.tabTop, buttonWidth, tabHeight,
                    this.activeTab == HubTab.MY_SESSIONS ? ButtonTone.TAB_ACTIVE : ButtonTone.TAB,
                    () -> this.switchTab(HubTab.MY_SESSIONS));
            this.addChromeButton("Close", closeX, layout.tabTop, buttonWidth, tabHeight, ButtonTone.SUBTLE, this::close);
            return;
        }

        this.addChromeButton("My Sessions", mySessionsX, rowTwoY, buttonWidth, tabHeight,
                this.activeTab == HubTab.MY_SESSIONS ? ButtonTone.TAB_ACTIVE : ButtonTone.TAB,
                () -> this.switchTab(HubTab.MY_SESSIONS));
        this.addChromeButton("Close", closeX, rowTwoY, buttonWidth, tabHeight, ButtonTone.SUBTLE, this::close);
    }

    private void initActiveSessions(Layout layout) {
        int cardPadding = this.ui(CARD_PADDING);
        int buttonHeight = this.ui(BUTTON_HEIGHT);
        int smallGap = this.ui(SMALL_GAP);
        int rightInnerX = layout.rightCardX + cardPadding;
        int rightInnerWidth = layout.rightCardWidth - (cardPadding * 2);
        int actionY = layout.rightCardY + layout.rightCardHeight - this.ui(64);
        int splitWidth = (rightInnerWidth - smallGap) / 2;

        ButtonWidget refreshButton = this.addChromeButton("Refresh Nearby", rightInnerX, actionY, splitWidth, buttonHeight,
                ButtonTone.SUBTLE, () -> {
                    this.refreshSessions(true);
                    SessionNotificationCenter.info("Session list refreshed");
                });
        ButtonWidget joinButton = this.addChromeButton("Join Selected", rightInnerX + splitWidth + smallGap, actionY, splitWidth, buttonHeight,
                ButtonTone.PRIMARY, this::joinSelected);
        this.addChromeButton("Open Creation", rightInnerX, actionY + buttonHeight + this.ui(8), splitWidth, buttonHeight,
                ButtonTone.SUBTLE, () -> this.switchTab(HubTab.CREATION));
        this.addChromeButton("My Session View", rightInnerX + splitWidth + smallGap, actionY + buttonHeight + this.ui(8), splitWidth, buttonHeight,
                ButtonTone.SUBTLE, () -> this.switchTab(HubTab.MY_SESSIONS));

        PeerShareTransport.SessionInfo selected = this.getSelectedSession();
        refreshButton.active = true;
        joinButton.active = selected != null && !selected.localHost();
    }

    private void initCreation(Layout layout) {
        int cardPadding = this.ui(CARD_PADDING);
        int buttonHeight = this.ui(BUTTON_HEIGHT);
        int smallGap = this.ui(SMALL_GAP);
        int leftInnerX = layout.leftCardX + cardPadding;
        int leftInnerWidth = layout.leftCardWidth - (cardPadding * 2);
        int baseY = layout.leftCardY + this.ui(84);
        int rightInnerX = layout.rightCardX + cardPadding;
        int rightInnerWidth = layout.rightCardWidth - (cardPadding * 2);
        int rightSplitWidth = (rightInnerWidth - smallGap) / 2;

        this.nameField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, leftInnerX, baseY, leftInnerWidth, this.ui(22),
                Text.literal("Session name")));
        this.nameField.setMaxLength(48);
        this.nameField.setText(this.sessionName);
        this.nameField.setChangedListener(value -> this.sessionName = value);

        this.descriptionField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, leftInnerX, baseY + this.ui(54), leftInnerWidth, this.ui(22),
                Text.literal("Session description")));
        this.descriptionField.setMaxLength(96);
        this.descriptionField.setText(this.sessionDescription);
        this.descriptionField.setPlaceholder(Text.literal("Tell people what they are joining"));
        this.descriptionField.setChangedListener(value -> this.sessionDescription = value);

        this.createPasswordField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, leftInnerX, baseY + this.ui(108), leftInnerWidth, this.ui(22),
                Text.literal("Password")));
        this.createPasswordField.setMaxLength(64);
        this.createPasswordField.setText(this.sessionPassword);
        this.createPasswordField.setPlaceholder(Text.literal("Leave blank for open discovery"));
        this.createPasswordField.setChangedListener(value -> this.sessionPassword = value);

        this.addChromeButton(this.defaultAllowClicks ? "Default Clicks: Allowed" : "Default Clicks: Blocked",
            rightInnerX, layout.rightCardY + this.ui(74), rightSplitWidth, buttonHeight,
                this.defaultAllowClicks ? ButtonTone.TOGGLE_ON : ButtonTone.TOGGLE_OFF,
                () -> {
                    this.defaultAllowClicks = !this.defaultAllowClicks;
                    this.clearAndInit();
                });
        this.addChromeButton(this.defaultAllowTyping ? "Default Typing: Allowed" : "Default Typing: Blocked",
            rightInnerX + rightSplitWidth + smallGap, layout.rightCardY + this.ui(74), rightSplitWidth, buttonHeight,
                this.defaultAllowTyping ? ButtonTone.TOGGLE_ON : ButtonTone.TOGGLE_OFF,
                () -> {
                    this.defaultAllowTyping = !this.defaultAllowTyping;
                    this.clearAndInit();
                });

        int actionY = layout.rightCardY + layout.rightCardHeight - this.ui(40);
        this.addChromeButton("Browse Sessions", rightInnerX, actionY, rightSplitWidth, buttonHeight, ButtonTone.SUBTLE,
                () -> this.switchTab(HubTab.ACTIVE_SESSIONS));
        this.addChromeButton("Create Session", rightInnerX + rightSplitWidth + smallGap, actionY, rightSplitWidth, buttonHeight,
                ButtonTone.PRIMARY, this::createSession);

        this.addChromeButton("Open Feed Settings", rightInnerX, actionY - this.ui(30),
                rightInnerWidth, buttonHeight, ButtonTone.SUBTLE,
                () -> this.client.setScreen(new SharescreenSettingsScreen(this)));
    }

    private void initMySessions(Layout layout) {
        PeerShareTransport.SessionInfo hosted = PeerShareTransport.getHostedSessionInfo();
        PeerShareTransport.SessionInfo remote = PeerShareTransport.getCurrentRemoteSession();
        int cardPadding = this.ui(CARD_PADDING);
        int buttonHeight = this.ui(BUTTON_HEIGHT);
        int smallGap = this.ui(SMALL_GAP);

        if (hosted != null) {
            int rightInnerX = layout.rightCardX + cardPadding;
            int rightInnerWidth = layout.rightCardWidth - (cardPadding * 2);
            int leftInnerX = layout.leftCardX + cardPadding;
            int leftInnerWidth = layout.leftCardWidth - (cardPadding * 2);
            int splitWidth = (rightInnerWidth - smallGap) / 2;

            this.addChromeButton("Feed Settings", rightInnerX, layout.rightCardY + this.ui(132), rightInnerWidth, buttonHeight,
                    ButtonTone.SUBTLE, () -> this.client.setScreen(new SharescreenSettingsScreen(this)));
            this.addChromeButton(hosted.defaultAllowClicks() ? "Default Clicks: Allowed" : "Default Clicks: Blocked",
                rightInnerX, layout.rightCardY + this.ui(166), splitWidth, buttonHeight,
                    hosted.defaultAllowClicks() ? ButtonTone.TOGGLE_ON : ButtonTone.TOGGLE_OFF,
                    () -> {
                        PeerShareTransport.updateDefaultPermissions(!hosted.defaultAllowClicks(), hosted.defaultAllowTyping());
                        SessionNotificationCenter.success("Default click permission updated");
                        this.refreshSessions(true);
                        this.clearAndInit();
                    });
            this.addChromeButton(hosted.defaultAllowTyping() ? "Default Typing: Allowed" : "Default Typing: Blocked",
                    rightInnerX + splitWidth + smallGap, layout.rightCardY + this.ui(166), splitWidth, buttonHeight,
                    hosted.defaultAllowTyping() ? ButtonTone.TOGGLE_ON : ButtonTone.TOGGLE_OFF,
                    () -> {
                        PeerShareTransport.updateDefaultPermissions(hosted.defaultAllowClicks(), !hosted.defaultAllowTyping());
                        SessionNotificationCenter.success("Default typing permission updated");
                        this.refreshSessions(true);
                        this.clearAndInit();
                    });
            this.addChromeButton("Stop Session", rightInnerX, layout.rightCardY + layout.rightCardHeight - this.ui(40), rightInnerWidth, buttonHeight,
                    ButtonTone.DANGER, () -> {
                        PeerShareTransport.stopHosting();
                        this.refreshSessions(true);
                        this.refreshViewers();
                        SessionNotificationCenter.info("Your hosted session was stopped");
                        this.clearAndInit();
                    });

            if (this.selectedViewerIndex >= 0 && this.selectedViewerIndex < this.hostedViewers.size()) {
                PeerShareTransport.ViewerInfo viewer = this.hostedViewers.get(this.selectedViewerIndex);
                int viewerActionY = layout.leftCardY + layout.leftCardHeight - this.ui(40);
                int viewerSplitWidth = (leftInnerWidth - smallGap) / 2;
                this.addChromeButton(viewer.allowClicks() ? "Viewer Clicks: Allowed" : "Viewer Clicks: Blocked",
                        leftInnerX, viewerActionY, viewerSplitWidth, buttonHeight,
                        viewer.allowClicks() ? ButtonTone.TOGGLE_ON : ButtonTone.TOGGLE_OFF,
                        () -> {
                            PeerShareTransport.updateViewerPermissions(viewer.viewerId(), !viewer.allowClicks(), viewer.allowTyping());
                            this.refreshViewers();
                            SessionNotificationCenter.success("Updated click permission for " + viewer.viewerName());
                            this.clearAndInit();
                        });
                this.addChromeButton(viewer.allowTyping() ? "Viewer Typing: Allowed" : "Viewer Typing: Blocked",
                        leftInnerX + viewerSplitWidth + smallGap, viewerActionY, viewerSplitWidth, buttonHeight,
                        viewer.allowTyping() ? ButtonTone.TOGGLE_ON : ButtonTone.TOGGLE_OFF,
                        () -> {
                            PeerShareTransport.updateViewerPermissions(viewer.viewerId(), viewer.allowClicks(), !viewer.allowTyping());
                            this.refreshViewers();
                            SessionNotificationCenter.success("Updated typing permission for " + viewer.viewerName());
                            this.clearAndInit();
                        });
            }
            return;
        }

        int rightInnerX = layout.rightCardX + cardPadding;
        int rightInnerWidth = layout.rightCardWidth - (cardPadding * 2);

        if (remote != null) {
            int splitWidth = (rightInnerWidth - smallGap) / 2;
            this.addChromeButton("Browse Sessions", rightInnerX, layout.rightCardY + layout.rightCardHeight - this.ui(40),
                    splitWidth, buttonHeight, ButtonTone.SUBTLE, () -> this.switchTab(HubTab.ACTIVE_SESSIONS));
            this.addChromeButton("Disconnect", rightInnerX + splitWidth + smallGap, layout.rightCardY + layout.rightCardHeight - this.ui(40),
                    splitWidth, buttonHeight, ButtonTone.DANGER, () -> {
                        PeerShareTransport.disconnect();
                        this.refreshSessions(true);
                        SessionNotificationCenter.info("Disconnected from session");
                        this.clearAndInit();
                    });
            return;
        }

        int splitWidth = (rightInnerWidth - smallGap) / 2;
        this.addChromeButton("Create Session", rightInnerX, layout.rightCardY + layout.rightCardHeight - this.ui(40),
                splitWidth, buttonHeight, ButtonTone.PRIMARY, () -> this.switchTab(HubTab.CREATION));
        this.addChromeButton("Browse Sessions", rightInnerX + splitWidth + smallGap, layout.rightCardY + layout.rightCardHeight - this.ui(40),
                splitWidth, buttonHeight, ButtonTone.SUBTLE, () -> this.switchTab(HubTab.ACTIVE_SESSIONS));
    }

    private ButtonWidget addChromeButton(String label, int x, int y, int width, int height, ButtonTone tone, Runnable action) {
        return this.addChromeButton(label, x, y, width, height, tone, false, action);
    }

    private ButtonWidget addChromeButton(String label, int x, int y, int width, int height, ButtonTone tone, boolean modalOnly, Runnable action) {
        DecoratedButton decoratedButton = new DecoratedButton(tone, modalOnly);
        ButtonWidget widget = ButtonWidget.builder(Text.literal(label), button -> {
                    decoratedButton.markPressed();
                    action.run();
                })
                .dimensions(x, y, width, height)
                .build();
        decoratedButton.widget = widget;
        this.chromeButtons.add(decoratedButton);
        return this.addDrawableChild(widget);
    }

    private void switchTab(HubTab tab) {
        if (this.activeTab == tab) {
            return;
        }

        this.previousTab = this.activeTab;
        this.activeTab = tab;
        this.tabTransitionStartedMs = System.currentTimeMillis();
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

        this.sessionScrollOffset = clamp(this.sessionScrollOffset, 0, this.getMaxSessionScroll(this.getLayout()));
    }

    private void refreshViewers() {
        this.hostedViewers = PeerShareTransport.getConnectedViewers();
        if (this.hostedViewers.isEmpty()) {
            this.selectedViewerIndex = -1;
        } else if (this.selectedViewerIndex < 0 || this.selectedViewerIndex >= this.hostedViewers.size()) {
            this.selectedViewerIndex = 0;
        }

        this.viewerScrollOffset = clamp(this.viewerScrollOffset, 0, this.getMaxViewerScroll(this.getLayout()));
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

        if (session.passwordProtected()) {
            this.pendingJoinSession = session;
            this.passwordModalOpen = true;
            this.clearAndInit();
            return;
        }

        this.performJoin(session, "");
    }

    private void confirmPasswordJoin() {
        if (this.pendingJoinSession == null) {
            this.passwordModalOpen = false;
            this.clearAndInit();
            return;
        }

        this.performJoin(this.pendingJoinSession, this.passwordText);
    }

    private void performJoin(PeerShareTransport.SessionInfo session, String password) {
        try {
            PeerShareTransport.join(session, password);
            this.passwordModalOpen = false;
            this.pendingJoinSession = null;
            this.passwordText = "";
            this.activeTab = HubTab.MY_SESSIONS;
            this.tabTransitionStartedMs = System.currentTimeMillis();
            this.refreshSessions(true);
            SessionNotificationCenter.success("Connected to " + session.name());
            this.clearAndInit();
        } catch (Exception exception) {
            SessionNotificationCenter.error("Join failed: " + exception.getMessage());
            if (this.passwordModalField != null) {
                this.passwordModalField.setSuggestion("Join failed: " + exception.getMessage());
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
            int cardPadding = this.ui(CARD_PADDING);
            int rowX = layout.leftCardX + cardPadding;
            int rowY = layout.leftCardY + this.ui(70);
            int rowWidth = layout.leftCardWidth - (cardPadding * 2);
            int visibleRows = this.getVisibleSessionRows(layout);
            int startIndex = this.sessionScrollOffset;

            for (int index = 0; index < Math.min(visibleRows, this.sessions.size() - startIndex); index++) {
                int topY = rowY + (index * (this.ui(ROW_HEIGHT) + this.ui(ROW_GAP)));
                if (click.x() >= rowX && click.x() <= rowX + rowWidth && click.y() >= topY && click.y() <= topY + this.ui(ROW_HEIGHT)) {
                    this.selectedSessionIndex = startIndex + index;
                    this.clearAndInit();
                    return true;
                }
            }
        } else if (this.activeTab == HubTab.MY_SESSIONS && PeerShareTransport.getHostedSessionInfo() != null) {
            int cardPadding = this.ui(CARD_PADDING);
            int rowX = layout.leftCardX + cardPadding;
            int rowY = layout.leftCardY + this.ui(70);
            int rowWidth = layout.leftCardWidth - (cardPadding * 2);
            int visibleRows = this.getVisibleViewerRows(layout);
            int startIndex = this.viewerScrollOffset;

            for (int index = 0; index < Math.min(visibleRows, this.hostedViewers.size() - startIndex); index++) {
                int topY = rowY + (index * (this.ui(VIEWER_ROW_HEIGHT) + this.ui(6)));
                if (click.x() >= rowX && click.x() <= rowX + rowWidth && click.y() >= topY && click.y() <= topY + this.ui(VIEWER_ROW_HEIGHT)) {
                    this.selectedViewerIndex = startIndex + index;
                    this.clearAndInit();
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Layout layout = this.getLayout();
        int direction = verticalAmount > 0.0D ? -1 : verticalAmount < 0.0D ? 1 : 0;
        if (direction == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        if (this.activeTab == HubTab.ACTIVE_SESSIONS) {
            int cardPadding = this.ui(CARD_PADDING);
            int rowX = layout.leftCardX + cardPadding;
            int rowY = layout.leftCardY + this.ui(70);
            int rowWidth = layout.leftCardWidth - (cardPadding * 2);
            int visibleRows = this.getVisibleSessionRows(layout);
            int listHeight = Math.max(this.ui(ROW_HEIGHT), (visibleRows * (this.ui(ROW_HEIGHT) + this.ui(ROW_GAP))) - this.ui(ROW_GAP));
            if (this.isPointWithin(mouseX, mouseY, rowX, rowY, rowWidth, listHeight)) {
                int nextOffset = clamp(this.sessionScrollOffset + direction, 0, this.getMaxSessionScroll(layout));
                if (nextOffset != this.sessionScrollOffset) {
                    this.sessionScrollOffset = nextOffset;
                    return true;
                }
            }
        } else if (this.activeTab == HubTab.MY_SESSIONS && PeerShareTransport.getHostedSessionInfo() != null) {
            int cardPadding = this.ui(CARD_PADDING);
            int rowX = layout.leftCardX + cardPadding;
            int rowY = layout.leftCardY + this.ui(70);
            int rowWidth = layout.leftCardWidth - (cardPadding * 2);
            int visibleRows = this.getVisibleViewerRows(layout);
            int listHeight = Math.max(this.ui(VIEWER_ROW_HEIGHT), (visibleRows * (this.ui(VIEWER_ROW_HEIGHT) + this.ui(6))) - this.ui(6));
            if (this.isPointWithin(mouseX, mouseY, rowX, rowY, rowWidth, listHeight)) {
                int nextOffset = clamp(this.viewerScrollOffset + direction, 0, this.getMaxViewerScroll(layout));
                if (nextOffset != this.viewerScrollOffset) {
                    this.viewerScrollOffset = nextOffset;
                    return true;
                }
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        this.restorePreviousGuiScale();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (keyInput.getKeycode() == 256) {
            this.close();
            return true;
        }

        return super.keyPressed(keyInput);
    }

    @Override
    public void removed() {
        this.restorePreviousGuiScale();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.refreshSessions(false);
        this.refreshViewers();
        Layout layout = this.getLayout();
        float tabTransition = this.getTabTransitionProgress();

        context.fillGradient(0, 0, this.width, this.height, 0xFF080D12, 0xFF040608);
        context.fillGradient(0, 0, this.width, this.height / 2, 0x20366D63, 0x00000000);
        context.fill(layout.contentLeft - this.ui(30), 0, layout.contentLeft + this.ui(260), this.ui(140), 0x103F8B78);
        context.fill(layout.contentLeft + layout.contentWidth - this.ui(220), this.height - this.ui(220), layout.contentLeft + layout.contentWidth + this.ui(20), this.height + this.ui(20), 0x103A5488);

        this.renderHeader(context, layout);
        this.renderTabRail(context, layout);

        if (this.activeTab == HubTab.ACTIVE_SESSIONS) {
            this.renderActiveSessions(context, layout);
        } else if (this.activeTab == HubTab.CREATION) {
            this.renderCreation(context, layout);
        } else {
            this.renderMySessions(context, layout);
        }

        if (tabTransition < 1.0F) {
            int overlayAlpha = Math.round((1.0F - tabTransition) * 70.0F);
            context.fill(layout.contentLeft, layout.bodyTop, layout.contentLeft + layout.contentWidth, this.height - this.ui(20),
                    overlayAlpha << 24);
        }

        this.renderFieldFrames(context);
        if (this.passwordModalOpen) {
            this.renderPasswordModal(context, layout);
        }
        this.syncWidgetStates();
        super.render(context, mouseX, mouseY, delta);
        this.renderChromeButtons(context);
        SessionNotificationCenter.render(context, this.textRenderer, this.width, this.height);
    }

    private void renderHeader(DrawContext context, Layout layout) {
        int headerY = this.ui(18);
        drawPanel(context, layout.contentLeft, headerY, layout.contentWidth, this.ui(HEADER_HEIGHT), 0xDB0C1117, 0xFF34515A);
        context.fill(layout.contentLeft + this.ui(18), headerY + this.ui(14), layout.contentLeft + this.ui(24), headerY + this.ui(52), 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, this.title, layout.contentLeft + this.ui(38), headerY + this.ui(14), 0xFFF2F5F7);
        this.drawWrappedText(context, Text.literal(this.getHeaderSubtitle()), layout.contentLeft + this.ui(38), headerY + this.ui(30),
                layout.contentWidth - this.ui(270), 0xFFAAC0C9, this.ui(10));

        int chipY = headerY + this.ui(14);
        int chipRight = layout.contentLeft + layout.contentWidth - this.ui(18);
        for (String chip : this.getHeaderChips()) {
            int chipWidth = this.textRenderer.getWidth(chip) + this.ui(18);
            chipRight -= chipWidth;
            this.drawChip(context, chipRight, chipY, chipWidth, this.ui(18), chip, 0x99202A31, 0xFF45636D, 0xFFF2F5F7);
            chipRight -= this.ui(8);
        }
    }

    private void renderTabRail(DrawContext context, Layout layout) {
        int railHeight = layout.tabRows == 1 ? this.ui(TAB_HEIGHT + 10) : this.ui((TAB_HEIGHT * 2) + 18);
        drawPanel(context, layout.contentLeft, layout.tabTop - this.ui(5), layout.contentWidth, railHeight, 0xA90A0F14, 0x553B505C);
    }

    private void renderActiveSessions(DrawContext context, Layout layout) {
        int cardPadding = this.ui(CARD_PADDING);
        int rowHeight = this.ui(ROW_HEIGHT);
        int rowGap = this.ui(ROW_GAP);
        drawPanel(context, layout.leftCardX, layout.leftCardY, layout.leftCardWidth, layout.leftCardHeight, 0xD70C1117, 0xFF2F424A);
        drawPanel(context, layout.rightCardX, layout.rightCardY, layout.rightCardWidth, layout.rightCardHeight, 0xD70B1015, 0xFF334B58);
        this.drawSectionHeading(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(14),
                "Nearby Sessions", "Pick a session card to inspect who is hosting it.");
        this.drawSectionHeading(context, layout.rightCardX + cardPadding, layout.rightCardY + this.ui(14),
                "Session Detail", "Join from here after checking ownership and defaults.");

        int rowX = layout.leftCardX + cardPadding;
        int rowY = layout.leftCardY + this.ui(70);
        int rowWidth = layout.leftCardWidth - (cardPadding * 2);
        int visibleRows = this.getVisibleSessionRows(layout);

        if (this.sessions.isEmpty()) {
            this.drawEmptyState(context, rowX, rowY + 8, rowWidth, 90,
                    "No nearby sessions", "Use Refresh Nearby or create a new session from the Creation tab.");
        } else {
            int startIndex = this.sessionScrollOffset;
            for (int index = 0; index < Math.min(visibleRows, this.sessions.size() - startIndex); index++) {
                PeerShareTransport.SessionInfo session = this.sessions.get(startIndex + index);
                int topY = rowY + (index * (rowHeight + rowGap));
                boolean selected = this.selectedSessionIndex == startIndex + index;
                this.drawSessionRow(context, rowX, topY, rowWidth, session, selected);
            }

            if (this.getMaxSessionScroll(layout) > 0) {
                int listHeight = Math.max(rowHeight, (visibleRows * (rowHeight + rowGap)) - rowGap);
                this.drawScrollRail(context, rowX + rowWidth - 4, rowY, listHeight, visibleRows, this.sessions.size(), this.sessionScrollOffset);
            }
        }

        PeerShareTransport.SessionInfo selected = this.getSelectedSession();
        if (selected == null) {
            this.drawEmptyState(context, layout.rightCardX + CARD_PADDING, layout.rightCardY + 78,
                    layout.rightCardWidth - (CARD_PADDING * 2), 120,
                    "Nothing selected", "Choose a nearby session on the left to see access, owner, and join state.");
            return;
        }

        int detailX = layout.rightCardX + cardPadding;
        int detailWidth = layout.rightCardWidth - (cardPadding * 2);
        int detailY = layout.rightCardY + this.ui(74);
        drawInset(context, detailX, detailY, detailWidth, this.ui(122), 0x70090C10, 0x66476069);
        context.drawTextWithShadow(this.textRenderer, Text.literal(selected.name()), detailX + this.ui(14), detailY + this.ui(12), 0xFFD6B16F);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Hosted by " + selected.leaderName()), detailX + this.ui(14), detailY + this.ui(30), 0xFFF1F4F5);
        this.drawChip(context, detailX + this.ui(14), detailY + this.ui(50), this.ui(74), this.ui(18),
                selected.passwordProtected() ? "Locked" : "Open", 0x99202A31,
                selected.passwordProtected() ? 0xFF94605B : 0xFF2F7A66, 0xFFF2F5F7);
        this.drawChip(context, detailX + this.ui(96), detailY + this.ui(50), this.ui(88), this.ui(18),
                selected.participantCount() + " player" + (selected.participantCount() == 1 ? "" : "s"),
                0x99202A31, 0xFF45636D, 0xFFF2F5F7);
        if (selected.localHost()) {
            this.drawChip(context, detailX + this.ui(192), detailY + this.ui(50), this.ui(98), this.ui(18), "Owned by you", 0x99202A31, 0xFF9F7E42, 0xFFF2F5F7);
        }
        this.drawWrappedText(context,
                Text.literal(selected.description().isBlank() ? "No description provided yet." : selected.description()),
            detailX + this.ui(14), detailY + this.ui(78), detailWidth - this.ui(28), 0xFF9FB2BC, this.ui(10));

        int noteY = detailY + this.ui(138);
        String joinNote = selected.localHost()
                ? "This session is yours. Owner controls and viewer permissions live under My Sessions."
                : selected.passwordProtected()
            ? "This session is locked. Press join and a password prompt will open."
                : "This session is open. You can join immediately without entering a password.";
        this.drawWrappedText(context, Text.literal(joinNote), detailX, noteY, detailWidth, 0xFFB5C5CD, 10);
    }

    private void renderCreation(DrawContext context, Layout layout) {
        int cardPadding = this.ui(CARD_PADDING);
        drawPanel(context, layout.leftCardX, layout.leftCardY, layout.leftCardWidth, layout.leftCardHeight, 0xD70C1117, 0xFF2F424A);
        drawPanel(context, layout.rightCardX, layout.rightCardY, layout.rightCardWidth, layout.rightCardHeight, 0xD70B1015, 0xFF334B58);

        this.drawSectionHeading(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(14),
                "Session Setup", "Everything is configured on one page now so publishing is predictable.");
        this.drawSectionHeading(context, layout.rightCardX + cardPadding, layout.rightCardY + this.ui(14),
                "Launch Preview", "This is the state people will see when your session goes live.");

        int formX = layout.leftCardX + cardPadding;
        int labelColor = 0xFFD6B16F;
        if (this.nameField != null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Session name"), formX, this.nameField.getY() - this.ui(16), labelColor);
        }
        if (this.descriptionField != null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Short description"), formX, this.descriptionField.getY() - this.ui(16), labelColor);
        }
        if (this.createPasswordField != null) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Password"), formX, this.createPasswordField.getY() - this.ui(16), labelColor);
            this.drawWrappedText(context, Text.literal("Leave password empty for open LAN discovery."), formX,
                this.createPasswordField.getY() + this.ui(28), layout.leftCardWidth - (cardPadding * 2), 0xFF9FB2BC, this.ui(10));
        }

        int previewX = layout.rightCardX + cardPadding;
        int previewWidth = layout.rightCardWidth - (cardPadding * 2);
        int previewY = layout.rightCardY + this.ui(108);
        int previewActionY = layout.rightCardY + layout.rightCardHeight - this.ui(70);
        int previewHeight = Math.max(this.ui(96), Math.min(this.ui(144), previewActionY - previewY - this.ui(92)));
        drawInset(context, previewX, previewY, previewWidth, previewHeight, 0x70090C10, 0x66476069);

        String previewName = this.sessionName == null || this.sessionName.isBlank() ? "Untitled Session" : this.sessionName.trim();
        String previewDescription = this.sessionDescription == null || this.sessionDescription.isBlank()
                ? "No description yet. Add one so people understand what they are joining."
                : this.sessionDescription;
        context.drawTextWithShadow(this.textRenderer, Text.literal(previewName), previewX + this.ui(14), previewY + this.ui(12), 0xFFD6B16F);
        this.drawChip(context, previewX + this.ui(14), previewY + this.ui(34), this.ui(92), this.ui(18),
                this.sessionPassword.isBlank() ? "Open access" : "Password", 0x99202A31,
                this.sessionPassword.isBlank() ? 0xFF2F7A66 : 0xFF94605B, 0xFFF2F5F7);
        this.drawChip(context, previewX + this.ui(114), previewY + this.ui(34), this.ui(92), this.ui(18),
                this.defaultAllowClicks ? "Clicks on" : "Clicks off", 0x99202A31,
                this.defaultAllowClicks ? 0xFF2F7A66 : 0xFF6F5250, 0xFFF2F5F7);
        this.drawChip(context, previewX + this.ui(214), previewY + this.ui(34), this.ui(94), this.ui(18),
                this.defaultAllowTyping ? "Typing on" : "Typing off", 0x99202A31,
                this.defaultAllowTyping ? 0xFF2F7A66 : 0xFF6F5250, 0xFFF2F5F7);
        this.drawWrappedText(context, Text.literal(previewDescription), previewX + this.ui(14), previewY + this.ui(64), previewWidth - this.ui(28), 0xFFB8C6CD, this.ui(10));

        int checklistY = previewY + previewHeight + this.ui(12);
        int checklistHeight = Math.max(this.ui(60), previewActionY - checklistY - this.ui(12));
        drawInset(context, previewX, checklistY, previewWidth, checklistHeight, 0x5A081015, 0x55384C58);
        context.drawTextWithShadow(this.textRenderer, Text.literal("What happens next"), previewX + this.ui(14), checklistY + this.ui(12), 0xFFF1F4F5);
        context.drawTextWithShadow(this.textRenderer, Text.literal("1. Nearby clients can discover the session automatically."), previewX + this.ui(14), checklistY + this.ui(32), 0xFF9FB2BC);
        context.drawTextWithShadow(this.textRenderer, Text.literal("2. New viewers inherit the default click and typing rules."), previewX + this.ui(14), checklistY + this.ui(46), 0xFF9FB2BC);
        if (checklistHeight >= this.ui(74)) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("3. You can change feed settings and per-viewer permissions later."), previewX + this.ui(14), checklistY + this.ui(60), 0xFF9FB2BC);
        }
    }

    private void renderPasswordModal(DrawContext context, Layout layout) {
        int overlayAlpha = 150;
        context.fill(0, 0, this.width, this.height, overlayAlpha << 24);
        int modalWidth = Math.min(this.ui(420), this.width - this.ui(80));
        int modalHeight = this.ui(138);
        int modalX = (this.width - modalWidth) / 2;
        int modalY = Math.max(layout.bodyTop + this.ui(24), (this.height - modalHeight) / 2);
        drawPanel(context, modalX, modalY, modalWidth, modalHeight, 0xEE0B1015, 0xFF45636D);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Locked Session"), modalX + this.ui(18), modalY + this.ui(16), 0xFFF2F5F7);
        String name = this.pendingJoinSession == null ? "Selected session" : this.pendingJoinSession.name();
        this.drawWrappedText(context, Text.literal("Enter the password for " + name + " to continue joining."),
                modalX + this.ui(18), modalY + this.ui(34), modalWidth - this.ui(36), 0xFFAAC0C9, this.ui(10));
        if (this.passwordModalField != null) {
            this.drawFieldFrame(context, this.passwordModalField, 0xFF45636D);
        }
    }

    private void renderMySessions(DrawContext context, Layout layout) {
        int cardPadding = this.ui(CARD_PADDING);
        drawPanel(context, layout.leftCardX, layout.leftCardY, layout.leftCardWidth, layout.leftCardHeight, 0xD70C1117, 0xFF2F424A);
        drawPanel(context, layout.rightCardX, layout.rightCardY, layout.rightCardWidth, layout.rightCardHeight, 0xD70B1015, 0xFF334B58);

        PeerShareTransport.SessionInfo hosted = PeerShareTransport.getHostedSessionInfo();
        PeerShareTransport.SessionInfo remote = PeerShareTransport.getCurrentRemoteSession();

        if (hosted != null) {
            this.drawSectionHeading(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(14),
                    "Live Viewers", "Pick a viewer and change only their permissions when needed.");
            this.drawSectionHeading(context, layout.rightCardX + cardPadding, layout.rightCardY + this.ui(14),
                    "Hosted Session", "Global defaults and feed controls stay on the right side.");

            this.renderViewerList(context, layout);

            int infoX = layout.rightCardX + cardPadding;
            int infoWidth = layout.rightCardWidth - (cardPadding * 2);
            int infoY = layout.rightCardY + this.ui(74);
            drawInset(context, infoX, infoY, infoWidth, this.ui(112), 0x70090C10, 0x66476069);
            context.drawTextWithShadow(this.textRenderer, Text.literal(hosted.name()), infoX + this.ui(14), infoY + this.ui(12), 0xFFD6B16F);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Hosted by " + hosted.leaderName()), infoX + this.ui(14), infoY + this.ui(30), 0xFFF1F4F5);
            this.drawChip(context, infoX + this.ui(14), infoY + this.ui(50), this.ui(94), this.ui(18),
                    hosted.participantCount() + " connected", 0x99202A31, 0xFF45636D, 0xFFF2F5F7);
            this.drawChip(context, infoX + this.ui(116), infoY + this.ui(50), this.ui(98), this.ui(18),
                    hosted.defaultAllowClicks() ? "Clicks allowed" : "Clicks blocked",
                    0x99202A31, hosted.defaultAllowClicks() ? 0xFF2F7A66 : 0xFF6F5250, 0xFFF2F5F7);
            this.drawChip(context, infoX + this.ui(222), infoY + this.ui(50), this.ui(98), this.ui(18),
                    hosted.defaultAllowTyping() ? "Typing allowed" : "Typing blocked",
                    0x99202A31, hosted.defaultAllowTyping() ? 0xFF2F7A66 : 0xFF6F5250, 0xFFF2F5F7);
            this.drawWrappedText(context,
                    Text.literal(hosted.description().isBlank() ? "No description provided yet." : hosted.description()),
                infoX + this.ui(14), infoY + this.ui(78), infoWidth - this.ui(28), 0xFFB8C6CD, this.ui(10));
            return;
        }

        if (remote != null) {
            this.drawSectionHeading(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(14),
                    "Viewer State", "You are connected to someone else, so owner controls stay hidden.");
            this.drawSectionHeading(context, layout.rightCardX + cardPadding, layout.rightCardY + this.ui(14),
                    "Joined Session", "The remote session summary stays here until you disconnect.");

            this.drawEmptyState(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(78),
                layout.leftCardWidth - (cardPadding * 2), this.ui(92),
                    "No owner tools", "Remote clicks and typing permissions are controlled by the person hosting this session.");
            this.drawChip(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(182), this.ui(116), this.ui(18),
                    PeerShareTransport.canRemoteClick() ? "Clicks allowed" : "Clicks blocked",
                    0x99202A31, PeerShareTransport.canRemoteClick() ? 0xFF2F7A66 : 0xFF6F5250, 0xFFF2F5F7);
            this.drawChip(context, layout.leftCardX + cardPadding + this.ui(124), layout.leftCardY + this.ui(182), this.ui(118), this.ui(18),
                    PeerShareTransport.canRemoteType() ? "Typing allowed" : "Typing blocked",
                    0x99202A31, PeerShareTransport.canRemoteType() ? 0xFF2F7A66 : 0xFF6F5250, 0xFFF2F5F7);

            int infoX = layout.rightCardX + cardPadding;
            int infoWidth = layout.rightCardWidth - (cardPadding * 2);
            int infoY = layout.rightCardY + this.ui(74);
            drawInset(context, infoX, infoY, infoWidth, this.ui(118), 0x70090C10, 0x66476069);
            context.drawTextWithShadow(this.textRenderer, Text.literal(remote.name()), infoX + this.ui(14), infoY + this.ui(12), 0xFFD6B16F);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Hosted by " + remote.leaderName()), infoX + this.ui(14), infoY + this.ui(30), 0xFFF1F4F5);
            this.drawChip(context, infoX + this.ui(14), infoY + this.ui(50), this.ui(92), this.ui(18),
                    remote.passwordProtected() ? "Locked" : "Open", 0x99202A31,
                    remote.passwordProtected() ? 0xFF94605B : 0xFF2F7A66, 0xFFF2F5F7);
            this.drawChip(context, infoX + this.ui(114), infoY + this.ui(50), this.ui(90), this.ui(18),
                    remote.participantCount() + " players", 0x99202A31, 0xFF45636D, 0xFFF2F5F7);
            this.drawWrappedText(context,
                    Text.literal(remote.description().isBlank() ? "No description provided yet." : remote.description()),
                infoX + this.ui(14), infoY + this.ui(78), infoWidth - this.ui(28), 0xFFB8C6CD, this.ui(10));
            return;
        }

        this.drawSectionHeading(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(14),
                "Nothing Active", "Once you host or join a session, controls will show up here.");
        this.drawSectionHeading(context, layout.rightCardX + cardPadding, layout.rightCardY + this.ui(14),
                "Quick Start", "Use one of the actions below to get into a live session fast.");
        this.drawEmptyState(context, layout.leftCardX + cardPadding, layout.leftCardY + this.ui(78),
            layout.leftCardWidth - (cardPadding * 2), this.ui(112),
                "No active session", "This tab becomes the owner dashboard when you host, or the viewer summary when you join somebody else.");
        this.drawEmptyState(context, layout.rightCardX + cardPadding, layout.rightCardY + this.ui(78),
            layout.rightCardWidth - (cardPadding * 2), this.ui(112),
                "Start from here", "Create a session to publish your feed, or browse nearby sessions and join one that is already live.");
    }

    private void renderViewerList(DrawContext context, Layout layout) {
        int cardPadding = this.ui(CARD_PADDING);
        int viewerRowHeight = this.ui(VIEWER_ROW_HEIGHT);
        int viewerGap = this.ui(6);
        int rowX = layout.leftCardX + cardPadding;
        int rowY = layout.leftCardY + this.ui(70);
        int rowWidth = layout.leftCardWidth - (cardPadding * 2);
        int visibleRows = this.getVisibleViewerRows(layout);

        if (this.hostedViewers.isEmpty()) {
            this.drawEmptyState(context, rowX, rowY + 8, rowWidth, 88,
                    "No viewers connected", "Per-viewer controls show up here as soon as someone joins.");
            return;
        }

        int startIndex = this.viewerScrollOffset;
        for (int index = 0; index < Math.min(visibleRows, this.hostedViewers.size() - startIndex); index++) {
            PeerShareTransport.ViewerInfo viewer = this.hostedViewers.get(startIndex + index);
            int topY = rowY + (index * (viewerRowHeight + viewerGap));
            boolean selected = this.selectedViewerIndex == startIndex + index;
            this.drawViewerRow(context, rowX, topY, rowWidth, viewer, selected);
        }

        if (this.getMaxViewerScroll(layout) > 0) {
            int listHeight = Math.max(viewerRowHeight, (visibleRows * (viewerRowHeight + viewerGap)) - viewerGap);
            this.drawScrollRail(context, rowX + rowWidth - 4, rowY, listHeight, visibleRows, this.hostedViewers.size(), this.viewerScrollOffset);
        }
    }

    private void renderFieldFrames(DrawContext context) {
        if (this.activeTab == HubTab.CREATION) {
            if (this.nameField != null) {
                this.drawFieldFrame(context, this.nameField, 0xFF45636D);
            }
            if (this.descriptionField != null) {
                this.drawFieldFrame(context, this.descriptionField, 0xFF45636D);
            }
            if (this.createPasswordField != null) {
                this.drawFieldFrame(context, this.createPasswordField, 0xFF45636D);
            }
        }
    }

    private void syncWidgetStates() {
        float entrance = this.getEntranceProgress();
        for (Object child : this.children()) {
            if (!(child instanceof ClickableWidget widget)) {
                continue;
            }

            if (this.isChromeButton(widget)) {
                widget.setAlpha(0.0F);
                widget.active = this.isActiveChromeWidget(widget);
            } else {
                widget.setAlpha(Math.min(1.0F, 0.72F + (entrance * 0.28F)));
                widget.active = !this.passwordModalOpen || widget == this.passwordModalField;
            }
        }
    }

    private boolean isActiveChromeWidget(ClickableWidget widget) {
        for (DecoratedButton chromeButton : this.chromeButtons) {
            if (chromeButton.widget == widget) {
                return !this.passwordModalOpen || chromeButton.modalOnly;
            }
        }
        return true;
    }

    private boolean isChromeButton(ClickableWidget widget) {
        for (DecoratedButton chromeButton : this.chromeButtons) {
            if (chromeButton.widget == widget) {
                return true;
            }
        }
        return false;
    }

    private void renderChromeButtons(DrawContext context) {
        for (DecoratedButton chromeButton : this.chromeButtons) {
            ButtonWidget widget = chromeButton.widget;
            int x = widget.getX();
            int y = widget.getY();
            int width = widget.getWidth();
            int height = widget.getHeight();

            int fillColor = switch (chromeButton.tone) {
                case PRIMARY -> widget.active ? 0xCC173236 : 0x6621282B;
                case SUBTLE -> widget.active ? 0xA9151B21 : 0x6621282B;
                case DANGER -> widget.active ? 0xCC3A2325 : 0x662A1F22;
                case TOGGLE_ON -> widget.active ? 0xCC18322D : 0x6621282B;
                case TOGGLE_OFF -> widget.active ? 0xCC302325 : 0x6621282B;
                case TAB_ACTIVE -> 0xE11A2831;
                case TAB -> widget.active ? 0x9910151B : 0x660F1216;
            };
            int borderColor = switch (chromeButton.tone) {
                case PRIMARY -> 0xFFD6B16F;
                case SUBTLE -> 0xFF45636D;
                case DANGER -> 0xFFB27066;
                case TOGGLE_ON -> 0xFF49A082;
                case TOGGLE_OFF -> 0xFF9B7068;
                case TAB_ACTIVE -> 0xFFD6B16F;
                case TAB -> 0x664D6570;
            };
            int textColor = widget.active ? 0xFFF2F5F7 : 0xFF8FA1AA;
            if (this.passwordModalOpen && !chromeButton.modalOnly) {
                fillColor = 0x4421282B;
                borderColor = 0x444D6570;
                textColor = 0x667D8A91;
            }

            if (widget.isHovered()) {
                fillColor = lighten(fillColor, 18);
                borderColor = lighten(borderColor, 10);
            }

            float pressProgress = chromeButton.getPressProgress();
            if (pressProgress > 0.0F) {
                fillColor = lighten(fillColor, Math.round(24.0F * pressProgress));
            }

            fillRect(context, x, y, width, height, fillColor);
            drawRectBorder(context, x, y, width, height, borderColor);
            if (chromeButton.tone == ButtonTone.TAB_ACTIVE) {
                context.fill(x + 10, y + height - 3, x + width - 10, y + height - 1, 0xFFD6B16F);
            }
            if (pressProgress > 0.0F) {
                int pulseAlpha = Math.round(pressProgress * 90.0F);
                context.fill(x + 2, y + 2, x + width - 2, y + height - 2, pulseAlpha << 24 | 0x00FFFFFF);
            }
            context.drawCenteredTextWithShadow(this.textRenderer, widget.getMessage(), x + width / 2, y + (height - 8) / 2, textColor);
        }
    }

    private void drawSessionRow(DrawContext context, int x, int y, int width, PeerShareTransport.SessionInfo session, boolean selected) {
        int rowHeight = this.ui(ROW_HEIGHT);
        fillRect(context, x, y, width, rowHeight, selected ? 0xB01B2730 : 0x78131920);
        drawRectBorder(context, x, y, width, rowHeight, selected ? 0xFFD6B16F : 0x66475D68);
        context.drawTextWithShadow(this.textRenderer, Text.literal(session.name()), x + this.ui(12), y + this.ui(8), 0xFFF2F5F7);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Host: " + session.leaderName()), x + this.ui(12), y + this.ui(22), 0xFFB6C5CC);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.trimLine(session.description(), 56)), x + this.ui(12), y + this.ui(36), 0xFF90A2AC);

        String status = session.passwordProtected() ? "Locked" : "Open";
        int statusWidth = this.textRenderer.getWidth(status) + this.ui(16);
        this.drawChip(context, x + width - statusWidth - this.ui(12), y + this.ui(8), statusWidth, this.ui(18),
                status, 0x99202A31, session.passwordProtected() ? 0xFF94605B : 0xFF2F7A66, 0xFFF2F5F7);
        String players = session.participantCount() + " player" + (session.participantCount() == 1 ? "" : "s");
        int playersWidth = this.textRenderer.getWidth(players) + this.ui(16);
        this.drawChip(context, x + width - playersWidth - this.ui(12), y + this.ui(30), playersWidth, this.ui(18),
                players, 0x99202A31, 0xFF45636D, 0xFFF2F5F7);
        if (session.localHost()) {
            this.drawChip(context, x + width - this.ui(110), y + this.ui(30), this.ui(98), this.ui(18), "Owned by you", 0x99202A31, 0xFF9F7E42, 0xFFF2F5F7);
        }
    }

    private void drawViewerRow(DrawContext context, int x, int y, int width, PeerShareTransport.ViewerInfo viewer, boolean selected) {
        int rowHeight = this.ui(VIEWER_ROW_HEIGHT);
        fillRect(context, x, y, width, rowHeight, selected ? 0xB01B2730 : 0x78131920);
        drawRectBorder(context, x, y, width, rowHeight, selected ? 0xFFD6B16F : 0x66475D68);
        context.drawTextWithShadow(this.textRenderer, Text.literal(viewer.viewerName()), x + this.ui(12), y + this.ui(11), 0xFFF2F5F7);
        String permissions = (viewer.allowClicks() ? "Clicks on" : "Clicks off") + "  |  "
                + (viewer.allowTyping() ? "Typing on" : "Typing off");
        context.drawTextWithShadow(this.textRenderer, Text.literal(permissions), x + this.ui(136), y + this.ui(11), 0xFF90A2AC);
    }

    private void drawSectionHeading(DrawContext context, int x, int y, String title, String subtitle) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(title), x, y, 0xFFF2F5F7);
        context.drawTextWithShadow(this.textRenderer, Text.literal(subtitle), x, y + this.ui(16), 0xFF90A2AC);
    }

    private void drawEmptyState(DrawContext context, int x, int y, int width, int height, String title, String subtitle) {
        drawInset(context, x, y, width, height, 0x65080D11, 0x55405B69);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(title), x + width / 2, y + this.ui(26), 0xFFF2F5F7);
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(subtitle), Math.max(this.ui(80), width - this.ui(30)));
        int drawY = y + this.ui(44);
        for (OrderedText line : lines) {
            context.drawCenteredTextWithShadow(this.textRenderer, line, x + width / 2, drawY, 0xFF95A8B2);
            drawY += this.ui(10);
        }
    }

    private void drawScrollRail(DrawContext context, int x, int y, int height, int visibleItems, int totalItems, int offset) {
        if (totalItems <= visibleItems || height <= 10) {
            return;
        }

        fillRect(context, x, y, this.ui(4), height, 0x44131B22);
        int thumbHeight = Math.max(this.ui(18), Math.round(height * (visibleItems / (float) totalItems)));
        int maxTravel = Math.max(1, height - thumbHeight);
        int maxOffset = Math.max(1, totalItems - visibleItems);
        int thumbY = y + Math.round((offset / (float) maxOffset) * maxTravel);
        fillRect(context, x, thumbY, this.ui(4), thumbHeight, 0xCC45636D);
    }

    private void drawFieldFrame(DrawContext context, TextFieldWidget field, int borderColor) {
        int x = field.getX() - this.ui(4);
        int y = field.getY() - this.ui(4);
        int width = field.getWidth() + this.ui(8);
        int height = field.getHeight() + this.ui(8);
        fillRect(context, x, y, width, height, 0x7A0A1015);
        drawRectBorder(context, x, y, width, height, borderColor);
    }

    private void drawChip(DrawContext context, int x, int y, int width, int height, String label, int fillColor, int borderColor, int textColor) {
        fillRect(context, x, y, width, height, fillColor);
        drawRectBorder(context, x, y, width, height, borderColor);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + width / 2, y + (height - 8) / 2, textColor);
    }

    private static void drawInset(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor) {
        fillRect(context, x, y, width, height, fillColor);
        drawRectBorder(context, x, y, width, height, borderColor);
    }

    private int getVisibleSessionRows(Layout layout) {
        int availableHeight = layout.leftCardHeight - this.ui(112);
        return Math.max(1, availableHeight / (this.ui(ROW_HEIGHT) + this.ui(ROW_GAP)));
    }

    private int getVisibleViewerRows(Layout layout) {
        int reserved = this.selectedViewerIndex >= 0 && !this.hostedViewers.isEmpty() ? this.ui(116) : this.ui(70);
        int availableHeight = layout.leftCardHeight - reserved;
        return Math.max(1, availableHeight / (this.ui(VIEWER_ROW_HEIGHT) + this.ui(6)));
    }

    private int getMaxSessionScroll(Layout layout) {
        return Math.max(0, this.sessions.size() - this.getVisibleSessionRows(layout));
    }

    private int getMaxViewerScroll(Layout layout) {
        return Math.max(0, this.hostedViewers.size() - this.getVisibleViewerRows(layout));
    }

    private PeerShareTransport.SessionInfo getSelectedSession() {
        if (this.selectedSessionIndex < 0 || this.selectedSessionIndex >= this.sessions.size()) {
            return null;
        }

        return this.sessions.get(this.selectedSessionIndex);
    }

    private String trimLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "No description";
        }

        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "...";
    }

    private String getHeaderSubtitle() {
        return switch (this.activeTab) {
            case ACTIVE_SESSIONS -> "Browse live sessions, inspect who owns them, and join without digging through multiple panels.";
            case CREATION -> "Configure the session in one pass, preview the result, then publish it when it looks right.";
            case MY_SESSIONS -> "Viewer controls, host defaults, and joined-session state all live in one dashboard.";
        };
    }

    private List<String> getHeaderChips() {
        List<String> chips = new ArrayList<>();
        chips.add(this.sessions.size() + " nearby");
        PeerShareTransport.SessionInfo hosted = PeerShareTransport.getHostedSessionInfo();
        PeerShareTransport.SessionInfo remote = PeerShareTransport.getCurrentRemoteSession();
        if (hosted != null) {
            chips.add("hosting now");
        } else if (remote != null) {
            chips.add("connected");
        } else {
            chips.add("idle");
        }
        chips.add(this.activeTab == HubTab.CREATION ? "launch setup" : "session hub");
        return chips;
    }

    private Layout getLayout() {
        int contentWidth = Math.min(this.width - (this.ui(OUTER_MARGIN) * 2), this.ui(1040));
        int contentLeft = (this.width - contentWidth) / 2;
        int tabTop = this.ui(18 + HEADER_HEIGHT + 12);
        int tabRows = contentWidth < this.ui(760) ? 2 : 1;
        int bodyTop = tabTop + (tabRows == 1 ? this.ui(TAB_HEIGHT + 14) : this.ui((TAB_HEIGHT * 2) + 22));
        int bodyHeight = Math.max(this.ui(252), this.height - bodyTop - this.ui(22));
        boolean stacked = contentWidth < this.ui(930);
        int leftCardWidth = stacked ? contentWidth : Math.max(this.ui(420), (contentWidth - this.ui(SURFACE_GAP)) * 57 / 100);
        int rightCardWidth = stacked ? contentWidth : contentWidth - leftCardWidth - this.ui(SURFACE_GAP);
        int leftCardHeight = stacked ? Math.max(this.ui(220), (bodyHeight - this.ui(SURFACE_GAP)) / 2) : bodyHeight;
        int rightCardX = stacked ? contentLeft : contentLeft + leftCardWidth + this.ui(SURFACE_GAP);
        int rightCardY = stacked ? bodyTop + leftCardHeight + this.ui(SURFACE_GAP) : bodyTop;
        int rightCardHeight = stacked ? Math.max(this.ui(220), bodyHeight - leftCardHeight - this.ui(SURFACE_GAP)) : bodyHeight;
        return new Layout(contentLeft, contentWidth, tabTop, tabRows, bodyTop, bodyHeight,
                contentLeft, bodyTop, leftCardWidth, leftCardHeight,
                rightCardX, rightCardY, rightCardWidth, rightCardHeight);
    }

    private void drawWrappedText(DrawContext context, Text text, int x, int y, int maxWidth, int color, int lineHeight) {
        List<OrderedText> wrapped = this.textRenderer.wrapLines(text, Math.max(this.ui(48), maxWidth));
        int drawY = y;
        for (OrderedText line : wrapped) {
            context.drawTextWithShadow(this.textRenderer, line, x, drawY, color);
            drawY += lineHeight;
        }
    }

    private int ui(int pixels) {
        double scale = this.client == null ? 1.0D : Math.max(1.0D, this.client.getWindow().getScaleFactor());
        return Math.max(1, (int) Math.round(pixels / scale));
    }

    private float getEntranceProgress() {
        long elapsed = System.currentTimeMillis() - this.openedAtMs;
        return Math.max(0.0F, Math.min(1.0F, elapsed / (float) ENTRANCE_ANIMATION_MS));
    }

    private float getTabTransitionProgress() {
        if (this.tabTransitionStartedMs == 0L) {
            return 1.0F;
        }

        long elapsed = System.currentTimeMillis() - this.tabTransitionStartedMs;
        return Math.max(0.0F, Math.min(1.0F, elapsed / 170.0F));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean applyTemporaryGuiScale() {
        if (this.client == null || this.client.options == null || this.forcedGuiScaleApplied) {
            return false;
        }

        int currentGuiScale = this.client.options.getGuiScale().getValue();
        this.previousGuiScale = currentGuiScale;
        this.forcedGuiScaleApplied = true;
        if (currentGuiScale == 1) {
            return false;
        }

        this.client.options.getGuiScale().setValue(1);
        return true;
    }

    private void restorePreviousGuiScale() {
        if (!this.forcedGuiScaleApplied || this.client == null || this.client.options == null || this.previousGuiScale == null) {
            return;
        }

        int targetGuiScale = this.previousGuiScale;
        this.forcedGuiScaleApplied = false;
        this.previousGuiScale = null;
        if (this.client.options.getGuiScale().getValue() != targetGuiScale) {
            this.client.options.getGuiScale().setValue(targetGuiScale);
        }
    }

    private boolean isPointWithin(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static int lighten(int color, int amount) {
        int alpha = color >>> 24;
        int red = Math.min(255, ((color >>> 16) & 0xFF) + amount);
        int green = Math.min(255, ((color >>> 8) & 0xFF) + amount);
        int blue = Math.min(255, (color & 0xFF) + amount);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
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

    private enum ButtonTone {
        PRIMARY,
        SUBTLE,
        DANGER,
        TOGGLE_ON,
        TOGGLE_OFF,
        TAB_ACTIVE,
        TAB
    }

    private final class DecoratedButton {
        private ButtonWidget widget;
        private final ButtonTone tone;
        private final boolean modalOnly;
        private long lastPressedAtMs;

        private DecoratedButton(ButtonTone tone, boolean modalOnly) {
            this.tone = tone;
            this.modalOnly = modalOnly;
        }

        private void markPressed() {
            this.lastPressedAtMs = System.currentTimeMillis();
        }

        private float getPressProgress() {
            if (this.lastPressedAtMs == 0L) {
                return 0.0F;
            }

            long elapsed = System.currentTimeMillis() - this.lastPressedAtMs;
            return Math.max(0.0F, 1.0F - (elapsed / 140.0F));
        }
    }

    private record Layout(int contentLeft, int contentWidth, int tabTop, int tabRows, int bodyTop, int bodyHeight,
                          int leftCardX, int leftCardY, int leftCardWidth, int leftCardHeight,
                          int rightCardX, int rightCardY, int rightCardWidth, int rightCardHeight) {
    }
}