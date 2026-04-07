package app;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class RdpLauncherApp extends Application {

    private static final String LOCAL_BIND = "127.0.0.1";
    private static final String LOOPBACK_HOST_FOR_RDP = "localhost";
    private static final String MSTSC_EXE = "C:\\Windows\\System32\\mstsc.exe";

    private static final Path APP_DIR = Paths.get(System.getProperty("user.home"), "rdp-launcher");
    private static final Path SESSIONS_CSV = APP_DIR.resolve("sessions.csv");
    private static final Path APP_KNOWN_HOSTS = APP_DIR.resolve("known_hosts");

    private static final String TITLE = "Windows Remote Desktop Launcher v0.1.2";

    private final Connection connection = new Connection(LOCAL_BIND, LOOPBACK_HOST_FOR_RDP, MSTSC_EXE, APP_DIR, APP_KNOWN_HOSTS);
    private final ObservableList<Session> sessions = FXCollections.observableArrayList();
    private final AtomicBoolean disconnecting = new AtomicBoolean(false);

    private ListView<Session> sessionList;

    private TextField nameField;
    private CheckBox useBastionChk;
    private Label bastionAliasLabel;
    private TextField sshAliasField;
    private Label sshChainLabel;
    private TextField sshChainField;
    private Label bastionOptionsLabel;
    private TextField sshOptionsField;

    private CheckBox useRdGatewayChk;
    private Label rdGatewayHostLabel;
    private TextField rdGatewayHostField;
    private CheckBox rdGatewayUseCurrentUserChk;
    private CheckBox rdGatewayShareCredsChk;

    private TextField rdpHostField;
    private TextField rdpPortField;

    private TextField userField;
    private PasswordField passField;
    private TextField domainField;
    private CheckBox autoSaveUserChk;

    private TextArea logArea;
    private Label statusLabel;

    private Button newBtn;
    private Button saveBtn;
    private Button deleteBtn;
    private Button detailsBtn;
    private Button connectBtn;
    private Button disconnectBtn;

    private boolean loadingForm = false;
    private GridPane mainForm;

    @Override
    public void start(Stage stage) {
        sessionList = new ListView<>(sessions);
        sessionList.setPrefWidth(280);
        sessionList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (loadingForm) return;
            if (newV != null) loadToForm(newV);
        });
        sessionList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && sessionList.getSelectionModel().getSelectedItem() != null) {
                onConnect();
            }
        });
        sessionList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && sessionList.getSelectionModel().getSelectedItem() != null) {
                onConnect();
                e.consume();
            }
        });

        buildForm();
        GridPane cred = buildCredentialPane();
        TitledPane credPane = new TitledPane("Login", cred);
        credPane.setExpanded(true);

        connectBtn = new Button("Connect");
        disconnectBtn = new Button("Disconnect");
        disconnectBtn.setDisable(true);
        connectBtn.setDefaultButton(true);
        connectBtn.setOnAction(e -> onConnect());
        disconnectBtn.setOnAction(e -> onDisconnect());

        detailsBtn = new Button("Display");
        detailsBtn.setOnAction(e -> onDetails());

        newBtn = new Button("New");
        saveBtn = new Button("Save");
        deleteBtn = new Button("Delete");
        deleteBtn.setStyle("-fx-text-fill: #b00020;");

        newBtn.setOnAction(e -> onNew());
        saveBtn.setOnAction(e -> onSave());
        deleteBtn.setOnAction(e -> onDelete());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(10, connectBtn, disconnectBtn, detailsBtn, spacer, newBtn, saveBtn, deleteBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Ready");

        VBox rightTop = new VBox(10, mainForm, credPane, new Separator(), toolbar, new Separator(), statusLabel);
        rightTop.setPadding(new Insets(10));

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        VBox right = new VBox(10, rightTop, new Label("Log"), logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        Label sessionsLabel = new Label("Sessions");
        Button newLeftBtn = new Button("New");
        newLeftBtn.setOnAction(e -> onNew());

        HBox leftHeader = new HBox(10, sessionsLabel, newLeftBtn);
        leftHeader.setPadding(new Insets(10, 10, 0, 10));

        VBox leftBox = new VBox(10, leftHeader, sessionList);
        leftBox.setPadding(new Insets(0, 10, 10, 10));
        VBox.setVgrow(sessionList, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setLeft(leftBox);
        root.setCenter(right);
        BorderPane.setMargin(right, new Insets(0, 10, 10, 10));

        try {
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/app/app.png"))));
        } catch (Exception ignored) {
        }

        stage.setTitle(TITLE);
        stage.setScene(new Scene(root, 1120, 760));
        stage.show();

        try {
            loadSessionsFromDisk();
            if (!sessions.isEmpty()) {
                sessionList.getSelectionModel().select(0);
            } else {
                onNew();
            }
        } catch (Exception ex) {
            appendLog("[ERROR] Failed to load sessions: " + ex.getMessage());
            onNew();
        }
    }

    private void buildForm() {
        nameField = new TextField();
        nameField.setPromptText("Session name");

        useBastionChk = new CheckBox("Use SSH tunnel");
        useBastionChk.setSelected(true);
        useBastionChk.selectedProperty().addListener((obs, oldV, newV) -> applyTransportUi());

        sshChainLabel = new Label("SSH bastion chain");
        sshChainField = new TextField();
        sshChainField.setPromptText("Example: bastion1,bastion2,bastion3");
        sshChainField.textProperty().addListener((obs, oldV, newV) -> updateDerivedSshAlias());

        bastionAliasLabel = new Label("Last SSH bastion");
        sshAliasField = new TextField();
        sshAliasField.setEditable(false);
        sshAliasField.setPromptText("Auto-filled from the last item in the chain");

        bastionOptionsLabel = new Label("SSH options");
        sshOptionsField = new TextField();
        sshOptionsField.setPromptText("Example: -p 2222 -i C:\\Users\\me\\.ssh\\id_ed25519");

        useRdGatewayChk = new CheckBox("Use RD Gateway");
        useRdGatewayChk.selectedProperty().addListener((obs, oldV, newV) -> applyTransportUi());

        rdGatewayHostLabel = new Label("Gateway host");
        rdGatewayHostField = new TextField();
        rdGatewayHostField.setPromptText("rdg.example.com");

        rdGatewayUseCurrentUserChk = new CheckBox("Use current Windows user for gateway");
        rdGatewayShareCredsChk = new CheckBox("Reuse credentials for gateway and target");
        rdGatewayShareCredsChk.setSelected(true);

        rdpHostField = new TextField();
        rdpHostField.setPromptText("Target RDP host or IP");

        rdpPortField = new TextField("3389");
        rdpPortField.setPromptText("3389");

        mainForm = new GridPane();
        mainForm.setHgap(10);
        mainForm.setVgap(8);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(190);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        mainForm.getColumnConstraints().addAll(c0, c1);

        int row = 0;
        mainForm.add(new Label("Name"), 0, row);
        mainForm.add(nameField, 1, row++);
        mainForm.add(new Label("SSH tunnel"), 0, row);
        mainForm.add(useBastionChk, 1, row++);
        mainForm.add(sshChainLabel, 0, row);
        mainForm.add(sshChainField, 1, row++);
        mainForm.add(bastionAliasLabel, 0, row);
        mainForm.add(sshAliasField, 1, row++);
        mainForm.add(bastionOptionsLabel, 0, row);
        mainForm.add(sshOptionsField, 1, row++);
        mainForm.add(new Label("RD Gateway"), 0, row);
        mainForm.add(useRdGatewayChk, 1, row++);
        mainForm.add(rdGatewayHostLabel, 0, row);
        mainForm.add(rdGatewayHostField, 1, row++);
        mainForm.add(new Label("Gateway auth"), 0, row);
        mainForm.add(rdGatewayUseCurrentUserChk, 1, row++);
        mainForm.add(new Label("Gateway credential reuse"), 0, row);
        mainForm.add(rdGatewayShareCredsChk, 1, row++);
        mainForm.add(new Label("RDP host"), 0, row);
        mainForm.add(rdpHostField, 1, row++);
        mainForm.add(new Label("RDP port"), 0, row);
        mainForm.add(rdpPortField, 1, row);

        applyTransportUi();
    }

    private GridPane buildCredentialPane() {
        userField = new TextField();
        passField = new PasswordField();
        domainField = new TextField();
        autoSaveUserChk = new CheckBox("Save username/domain in the session");
        autoSaveUserChk.setSelected(true);

        GridPane cred = new GridPane();
        cred.setHgap(10);
        cred.setVgap(8);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(190);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        cred.getColumnConstraints().addAll(c0, c1);

        cred.add(new Label("Username"), 0, 0);
        cred.add(userField, 1, 0);
        cred.add(new Label("Password"), 0, 1);
        cred.add(passField, 1, 1);
        cred.add(new Label("Domain/Prefix"), 0, 2);
        cred.add(domainField, 1, 2);
        cred.add(autoSaveUserChk, 1, 3);
        return cred;
    }

    private void applyTransportUi() {
        boolean sshEnabled = useBastionChk.isSelected();
        boolean rdgEnabled = useRdGatewayChk.isSelected();

        sshChainLabel.setManaged(sshEnabled);
        sshChainLabel.setVisible(sshEnabled);
        sshChainField.setManaged(sshEnabled);
        sshChainField.setVisible(sshEnabled);
        sshChainField.setDisable(!sshEnabled);

        bastionAliasLabel.setManaged(sshEnabled);
        bastionAliasLabel.setVisible(sshEnabled);
        sshAliasField.setManaged(sshEnabled);
        sshAliasField.setVisible(sshEnabled);
        sshAliasField.setDisable(!sshEnabled);

        bastionOptionsLabel.setManaged(sshEnabled);
        bastionOptionsLabel.setVisible(sshEnabled);
        sshOptionsField.setManaged(sshEnabled);
        sshOptionsField.setVisible(sshEnabled);
        sshOptionsField.setDisable(!sshEnabled);

        rdGatewayHostLabel.setManaged(rdgEnabled);
        rdGatewayHostLabel.setVisible(rdgEnabled);
        rdGatewayHostField.setManaged(rdgEnabled);
        rdGatewayHostField.setVisible(rdgEnabled);
        rdGatewayHostField.setDisable(!rdgEnabled);

        rdGatewayUseCurrentUserChk.setManaged(rdgEnabled);
        rdGatewayUseCurrentUserChk.setVisible(rdgEnabled);
        rdGatewayUseCurrentUserChk.setDisable(!rdgEnabled);

        rdGatewayShareCredsChk.setManaged(rdgEnabled);
        rdGatewayShareCredsChk.setVisible(rdgEnabled);
        rdGatewayShareCredsChk.setDisable(!rdgEnabled);
    }

    private void updateDerivedSshAlias() {
        SshChainParts chain = parseSshChain(sshChainField.getText());
        sshAliasField.setText(chain.sshAlias());
    }

    private static SshChainParts parseSshChain(String rawChain) {
        String value = norm(rawChain);
        if (value.isEmpty()) {
            return new SshChainParts("", "");
        }

        String[] parts = value.split(",");
        List<String> cleaned = new java.util.ArrayList<>();
        for (String part : parts) {
            String item = norm(part);
            if (!item.isEmpty()) {
                cleaned.add(item);
            }
        }
        if (cleaned.isEmpty()) {
            return new SshChainParts("", "");
        }

        String sshAlias = cleaned.get(cleaned.size() - 1);
        String jumpHosts = cleaned.size() > 1
                ? String.join(",", cleaned.subList(0, cleaned.size() - 1))
                : "";
        return new SshChainParts(sshAlias, jumpHosts);
    }

    private static String buildSshChain(String jumpHosts, String sshAlias) {
        String jump = norm(jumpHosts);
        String alias = norm(sshAlias);
        if (jump.isEmpty()) return alias;
        if (alias.isEmpty()) return jump;
        return jump + "," + alias;
    }

    private record SshChainParts(String sshAlias, String jumpHosts) {}

    private void onConnect() {
        Session base = readFromFormValidated();
        if (base == null) return;

        final String snapUser = userField.getText();
        final String snapDom = domainField.getText();
        final String snapPass = passField.getText();

        if (autoSaveUserChk.isSelected()) {
            onSave();
        }

        Session selected = sessionList.getSelectionModel().getSelectedItem();
        Session existing = (selected != null && selected.name().equalsIgnoreCase(base.name()))
                ? selected
                : (indexOfName(base.name()) >= 0 ? sessions.get(indexOfName(base.name())) : null);

        boolean fullscreen = existing != null && existing.fullscreen();
        Integer width = existing != null ? existing.width() : null;
        Integer height = existing != null ? existing.height() : null;
        boolean multimon = existing != null && existing.multimon();
        boolean span = existing != null && existing.span();

        String savedUser = existing != null ? norm(existing.username()) : "";
        String savedDom = existing != null ? norm(existing.domain()) : "";

        Session effective = new Session(
                base.name(),
                base.useBastion(),
                base.sshAlias(),
                base.jumpHosts(),
                base.sshOptions(),
                base.useRdGateway(),
                base.rdGatewayHost(),
                base.rdGatewayUseCurrentUser(),
                base.rdGatewayShareCreds(),
                base.rdpHost(),
                base.rdpPort(),
                savedUser,
                savedDom,
                fullscreen,
                width,
                height,
                multimon,
                span,
                existing != null ? norm(existing.selectedMonitors()) : norm(base.selectedMonitors())
        );

        Connection.Ui ui = new Connection.Ui() {
            @Override public void log(String s) { appendLog(s); }
            @Override public void status(String s) { setStatus(s); }
            @Override public void alert(String s) { alertDialog(s); }
            @Override public void setInputsDisabled(boolean disabled) { RdpLauncherApp.this.setInputsDisabled(disabled); }
            @Override public void setConnected(boolean connected) {
                Platform.runLater(() -> {
                    connectBtn.setDisable(connected);
                    disconnectBtn.setDisable(!connected);
                });
            }
            @Override public void runOnFx(Runnable r) { Platform.runLater(r); }
            @Override public void clearPassword() { passField.clear(); }
        };

        connection.connect(effective, snapUser, snapDom, snapPass, ui);
    }

    private void onDisconnect() {
        if (!disconnecting.compareAndSet(false, true)) return;

        appendLog("[INFO] Disconnect clicked");
        setStatus("Disconnecting...");
        Platform.runLater(() -> disconnectBtn.setDisable(true));

        Connection.Ui ui = new Connection.Ui() {
            @Override public void log(String s) { appendLog(s); }
            @Override public void status(String s) { setStatus(s); }
            @Override public void alert(String s) { alertDialog(s); }
            @Override public void setInputsDisabled(boolean disabled) { RdpLauncherApp.this.setInputsDisabled(disabled); }
            @Override public void setConnected(boolean connected) {
                Platform.runLater(() -> {
                    connectBtn.setDisable(connected);
                    disconnectBtn.setDisable(!connected);
                });
            }
            @Override public void runOnFx(Runnable r) { Platform.runLater(r); }
            @Override public void clearPassword() { passField.clear(); }
        };

        new Thread(() -> {
            try {
                connection.disconnect(ui);
                appendLog("[INFO] Disconnect requested (done)");
            } catch (Throwable t) {
                appendLog("[ERROR] Disconnect failed: " + t);
                Platform.runLater(() -> alertDialog("Disconnect failed:\n" + t));
                Platform.runLater(() -> disconnectBtn.setDisable(false));
            } finally {
                disconnecting.set(false);
            }
        }, "rdp-launcher-disconnect").start();
    }

    private void setInputsDisabled(boolean connecting) {
        Platform.runLater(() -> {
            nameField.setDisable(connecting);
            useBastionChk.setDisable(connecting);
            sshChainField.setDisable(connecting || !useBastionChk.isSelected());
            sshAliasField.setDisable(connecting || !useBastionChk.isSelected());
            sshOptionsField.setDisable(connecting || !useBastionChk.isSelected());
            useRdGatewayChk.setDisable(connecting);
            rdGatewayHostField.setDisable(connecting || !useRdGatewayChk.isSelected());
            rdGatewayUseCurrentUserChk.setDisable(connecting || !useRdGatewayChk.isSelected());
            rdGatewayShareCredsChk.setDisable(connecting || !useRdGatewayChk.isSelected());
            rdpHostField.setDisable(connecting);
            rdpPortField.setDisable(connecting);
            userField.setDisable(connecting);
            passField.setDisable(connecting);
            domainField.setDisable(connecting);
            autoSaveUserChk.setDisable(connecting);
            sessionList.setDisable(connecting);
            detailsBtn.setDisable(connecting);
            newBtn.setDisable(connecting);
            saveBtn.setDisable(connecting);
            deleteBtn.setDisable(connecting);
        });
    }

    private void onNew() {
        loadingForm = true;
        try {
            nameField.clear();
            useBastionChk.setSelected(true);
            sshChainField.setText("rdp");
            sshOptionsField.clear();
            useRdGatewayChk.setSelected(false);
            rdGatewayHostField.clear();
            rdGatewayUseCurrentUserChk.setSelected(false);
            rdGatewayShareCredsChk.setSelected(true);
            rdpHostField.clear();
            rdpPortField.setText("3389");
            userField.clear();
            domainField.clear();
            passField.clear();
            sessionList.getSelectionModel().clearSelection();
            applyTransportUi();
        } finally {
            loadingForm = false;
        }
        appendLog("[INFO] New session");
    }

    private void onSave() {
        Session base = readFromFormValidated();
        if (base == null) return;

        int idx = indexOfName(base.name());
        Session existing = idx >= 0 ? sessions.get(idx) : null;

        String username;
        String domain;
        if (autoSaveUserChk.isSelected()) {
            username = norm(userField.getText());
            domain = norm(domainField.getText());
        } else {
            username = existing != null ? norm(existing.username()) : "";
            domain = existing != null ? norm(existing.domain()) : "";
        }

        boolean fullscreen = existing != null && existing.fullscreen();
        Integer width = existing != null ? existing.width() : null;
        Integer height = existing != null ? existing.height() : null;
        boolean multimon = existing != null && existing.multimon();
        boolean span = existing != null && existing.span();

        Session merged = new Session(
                base.name(),
                base.useBastion(),
                base.sshAlias(),
                base.jumpHosts(),
                base.sshOptions(),
                base.useRdGateway(),
                base.rdGatewayHost(),
                base.rdGatewayUseCurrentUser(),
                base.rdGatewayShareCreds(),
                base.rdpHost(),
                base.rdpPort(),
                username,
                domain,
                fullscreen,
                width,
                height,
                multimon,
                span,
                existing != null ? norm(existing.selectedMonitors()) : ""
        );

        if (idx >= 0) sessions.set(idx, merged);
        else sessions.add(merged);

        FXCollections.sort(sessions, Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
        sessionList.getSelectionModel().select(merged);

        try {
            saveSessionsToDisk();
            appendLog("[INFO] Saved: " + merged.name());
        } catch (Exception ex) {
            appendLog("[ERROR] Save failed: " + ex.getMessage());
        }
    }

    private void onDelete() {
        Session sel = sessionList.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alertDialog("Select a session to delete.");
            return;
        }

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Delete session");
        a.setHeaderText(null);
        a.setContentText("Delete '" + sel.name() + "'?");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;

        sessions.remove(sel);
        try {
            saveSessionsToDisk();
            appendLog("[INFO] Deleted: " + sel.name());
        } catch (Exception ex) {
            appendLog("[ERROR] Save failed: " + ex.getMessage());
        }
        onNew();
    }
    private void onDetails() {
        Session base = readFromFormValidated();
        if (base == null) return;

        Session sel = sessionList.getSelectionModel().getSelectedItem();
        Session cur = (sel != null && sel.name().equalsIgnoreCase(base.name())) ? sel : base;
        final String[] selectedMonitors = { norm(cur.selectedMonitors()) };

        CheckBox fullscreenChk = new CheckBox("Fullscreen (/f)");
        fullscreenChk.setSelected(cur.fullscreen());

        TextField wField = new TextField(cur.width() == null ? "" : String.valueOf(cur.width()));
        TextField hField = new TextField(cur.height() == null ? "" : String.valueOf(cur.height()));
        wField.setPromptText("1920");
        hField.setPromptText("1080");

        Runnable applyFs = () -> {
            boolean fs = fullscreenChk.isSelected();
            wField.setDisable(fs);
            hField.setDisable(fs);
        };
        fullscreenChk.selectedProperty().addListener((o, ov, nv) -> applyFs.run());
        applyFs.run();

        CheckBox multimonChk = new CheckBox("Use multi-monitor (/multimon)");
        multimonChk.setSelected(cur.multimon());

        CheckBox spanChk = new CheckBox("Span monitors (/span)");
        spanChk.setSelected(cur.span());

        multimonChk.selectedProperty().addListener((o, ov, nv) -> { if (nv) spanChk.setSelected(false); });
        spanChk.selectedProperty().addListener((o, ov, nv) -> { if (nv) multimonChk.setSelected(false); });

        Label selectedMonitorsLabel = new Label(formatSelectedMonitorsText(selectedMonitors[0]));
        selectedMonitorsLabel.setWrapText(true);
        Button chooseMonitorsBtn = new Button("Choose monitors...");
        chooseMonitorsBtn.setOnAction(e -> {
            MonitorSelectionSupport.SelectionResult result =
                    MonitorSelectionSupport.showDialog(mainForm.getScene().getWindow(), MSTSC_EXE, selectedMonitors[0]);
            if (result != null) {
                selectedMonitors[0] = result.toCsv();
                selectedMonitorsLabel.setText(formatSelectedMonitorsText(selectedMonitors[0]));
                if (!selectedMonitors[0].isBlank()) {
                    fullscreenChk.setSelected(true);
                    multimonChk.setSelected(false);
                    spanChk.setSelected(false);
                }
            }
        });
        Button clearMonitorsBtn = new Button("Clear");
        clearMonitorsBtn.setOnAction(e -> {
            selectedMonitors[0] = "";
            selectedMonitorsLabel.setText(formatSelectedMonitorsText(selectedMonitors[0]));
        });
        HBox monitorButtons = new HBox(8, chooseMonitorsBtn, clearMonitorsBtn);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(12));
        g.add(fullscreenChk, 0, 0, 2, 1);
        g.add(new Label("Width"), 0, 1);
        g.add(wField, 1, 1);
        g.add(new Label("Height"), 0, 2);
        g.add(hField, 1, 2);
        g.add(multimonChk, 0, 3, 2, 1);
        g.add(spanChk, 0, 4, 2, 1);
        g.add(new Label("Selected monitors"), 0, 5);
        g.add(selectedMonitorsLabel, 1, 5);
        g.add(monitorButtons, 1, 6);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Display settings");
        dialog.getDialogPane().setContent(g);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;

            Integer w = parseNullableInt(wField.getText());
            Integer h = parseNullableInt(hField.getText());
            boolean fs = fullscreenChk.isSelected();
            if (fs) {
                w = null;
                h = null;
            }

            Session updated = new Session(
                    base.name(),
                    base.useBastion(),
                    base.sshAlias(),
                    base.jumpHosts(),
                    base.sshOptions(),
                    base.useRdGateway(),
                    base.rdGatewayHost(),
                    base.rdGatewayUseCurrentUser(),
                    base.rdGatewayShareCreds(),
                    base.rdpHost(),
                    base.rdpPort(),
                    norm(userField.getText()),
                    norm(domainField.getText()),
                    fs,
                    w,
                    h,
                    multimonChk.isSelected(),
                    spanChk.isSelected(),
                    selectedMonitors[0]
            );

            int idx = indexOfName(updated.name());
            if (idx >= 0) sessions.set(idx, updated);
            else sessions.add(updated);

            FXCollections.sort(sessions, Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
            sessionList.getSelectionModel().select(updated);

            try {
                saveSessionsToDisk();
                appendLog("[INFO] Saved details: " + updated.name());
            } catch (Exception ex) {
                appendLog("[ERROR] Save details failed: " + ex.getMessage());
            }
        });
    }

    private int indexOfName(String name) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private Session readFromFormValidated() {
        String name = norm(nameField.getText());
        boolean useBastion = useBastionChk.isSelected();
        SshChainParts sshChain = parseSshChain(sshChainField.getText());
        String sshAlias = sshChain.sshAlias();
        String jumpHosts = sshChain.jumpHosts();
        String sshOptions = norm(sshOptionsField.getText());
        boolean useRdGateway = useRdGatewayChk.isSelected();
        String rdGatewayHost = norm(rdGatewayHostField.getText());
        boolean rdGatewayUseCurrentUser = rdGatewayUseCurrentUserChk.isSelected();
        boolean rdGatewayShareCreds = rdGatewayShareCredsChk.isSelected();
        String rdpHost = norm(rdpHostField.getText());
        String rdpPortText = norm(rdpPortField.getText());

        if (name.isEmpty()) {
            alertDialog("Name is required.");
            return null;
        }
        if (useBastion && useRdGateway) {
            alertDialog("Use either SSH tunnel or RD Gateway for a session, not both at once.");
            return null;
        }
        if (useBastion && sshAlias.isEmpty()) {
            alertDialog("SSH bastion chain is required when the SSH tunnel is enabled.");
            return null;
        }
        if (useRdGateway && rdGatewayHost.isEmpty()) {
            alertDialog("Gateway host is required when RD Gateway is enabled.");
            return null;
        }
        if (rdpHost.isEmpty()) {
            alertDialog("RDP host is required.");
            return null;
        }

        int rdpPort;
        try {
            rdpPort = Integer.parseInt(rdpPortText);
            if (rdpPort < 1 || rdpPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            alertDialog("RDP port must be a number between 1 and 65535.");
            return null;
        }

        return new Session(
                name,
                useBastion,
                sshAlias,
                jumpHosts,
                sshOptions,
                useRdGateway,
                rdGatewayHost,
                rdGatewayUseCurrentUser,
                rdGatewayShareCreds,
                rdpHost,
                rdpPort,
                "",
                "",
                false,
                null,
                null,
                false,
                false,
                ""
        );
    }

    private void loadToForm(Session s) {
        loadingForm = true;
        try {
            nameField.setText(s.name());
            useBastionChk.setSelected(s.useBastion());
            sshChainField.setText(buildSshChain(s.jumpHosts(), s.sshAlias()));
            sshAliasField.setText(s.sshAlias() == null ? "" : s.sshAlias());
            sshOptionsField.setText(s.sshOptions() == null ? "" : s.sshOptions());
            useRdGatewayChk.setSelected(s.useRdGateway());
            rdGatewayHostField.setText(s.rdGatewayHost() == null ? "" : s.rdGatewayHost());
            rdGatewayUseCurrentUserChk.setSelected(s.rdGatewayUseCurrentUser());
            rdGatewayShareCredsChk.setSelected(s.rdGatewayShareCreds());
            rdpHostField.setText(s.rdpHost());
            rdpPortField.setText(String.valueOf(s.rdpPort()));
            userField.setText(s.username() == null ? "" : s.username());
            domainField.setText(s.domain() == null ? "" : s.domain());
            applyTransportUi();
        } finally {
            loadingForm = false;
        }
    }
    private void loadSessionsFromDisk() throws IOException {
        Files.createDirectories(APP_DIR);
        if (!Files.exists(SESSIONS_CSV)) return;

        List<String> lines = Files.readAllLines(SESSIONS_CSV, Charset.forName("UTF-8"));
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (line.startsWith("#")) continue;
            if (line.toLowerCase().startsWith("name,")) continue;

            String[] parts = line.split(",", -1);
            if (parts.length < 6) continue;

            int i = 0;
            String name = parts[i++].trim();
            boolean useBastion = Boolean.parseBoolean(parts[i++].trim());
            String sshAlias = parts[i++].trim();
            String sshOptions = parts[i++].trim();
            String rdpHost = parts[i++].trim();

            int rdpPort;
            try {
                rdpPort = Integer.parseInt(parts[i++].trim());
            } catch (NumberFormatException e) {
                continue;
            }

            String username = parts.length > i ? parts[i++].trim() : "";
            String domain = parts.length > i ? parts[i++].trim() : "";
            boolean fullscreen = parts.length > i && Boolean.parseBoolean(parts[i++].trim());
            Integer width = parts.length > i ? parseNullableInt(parts[i++]) : null;
            Integer height = parts.length > i ? parseNullableInt(parts[i++]) : null;
            boolean multimon = parts.length > i && Boolean.parseBoolean(parts[i++].trim());
            boolean span = parts.length > i && Boolean.parseBoolean(parts[i++].trim());

            String jumpHosts = parts.length > i ? parts[i++].trim() : "";
            boolean useRdGateway = parts.length > i && Boolean.parseBoolean(parts[i++].trim());
            String rdGatewayHost = parts.length > i ? parts[i++].trim() : "";
            boolean rdGatewayUseCurrentUser = parts.length > i && Boolean.parseBoolean(parts[i++].trim());
            boolean rdGatewayShareCreds = parts.length > i ? Boolean.parseBoolean(parts[i++].trim()) : true;
            String selectedMonitors = parts.length > i ? parts[i++].trim() : "";

            sessions.add(new Session(
                    name,
                    useBastion,
                    sshAlias,
                    jumpHosts,
                    sshOptions,
                    useRdGateway,
                    rdGatewayHost,
                    rdGatewayUseCurrentUser,
                    rdGatewayShareCreds,
                    rdpHost,
                    rdpPort,
                    username,
                    domain,
                    fullscreen,
                    width,
                    height,
                    multimon,
                    span,
                    selectedMonitors
            ));
        }

        FXCollections.sort(sessions, Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
        appendLog("[INFO] Loaded sessions: " + sessions.size() + " (" + SESSIONS_CSV + ")");
    }

    private void saveSessionsToDisk() throws IOException {
        Files.createDirectories(APP_DIR);

        StringBuilder sb = new StringBuilder();
        sb.append("name,useBastion,sshAlias,sshOptions,rdpHost,rdpPort,username,domain,fullscreen,width,height,multimon,span,jumpHosts,useRdGateway,rdGatewayHost,rdGatewayUseCurrentUser,rdGatewayShareCreds,selectedMonitors")
                .append(System.lineSeparator());

        for (Session s : sessions) {
            sb.append(nullToEmpty(s.name())).append(",")
                    .append(s.useBastion()).append(",")
                    .append(nullToEmpty(s.sshAlias())).append(",")
                    .append(nullToEmpty(s.sshOptions())).append(",")
                    .append(nullToEmpty(s.rdpHost())).append(",")
                    .append(s.rdpPort()).append(",")
                    .append(nullToEmpty(s.username())).append(",")
                    .append(nullToEmpty(s.domain())).append(",")
                    .append(s.fullscreen()).append(",")
                    .append(s.width() == null ? "" : s.width()).append(",")
                    .append(s.height() == null ? "" : s.height()).append(",")
                    .append(s.multimon()).append(",")
                    .append(s.span()).append(",")
                    .append(nullToEmpty(s.jumpHosts())).append(",")
                    .append(s.useRdGateway()).append(",")
                    .append(nullToEmpty(s.rdGatewayHost())).append(",")
                    .append(s.rdGatewayUseCurrentUser()).append(",")
                    .append(s.rdGatewayShareCreds()).append(",")
                    .append(nullToEmpty(s.selectedMonitors()))
                    .append(System.lineSeparator());
        }

        Files.writeString(SESSIONS_CSV, sb.toString(), Charset.forName("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        appendLog("[INFO] Saved sessions: " + sessions.size() + " (" + SESSIONS_CSV + ")");
    }

    private static Integer parseNullableInt(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return null;
        try {
            int v = Integer.parseInt(t);
            return v <= 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String formatSelectedMonitorsText(String csv) {
        String value = norm(csv);
        if (value.isEmpty()) {
            return "Not set";
        }
        return "RDP monitor IDs: " + value;
    }

    private void appendLog(String s) {
        Platform.runLater(() -> logArea.appendText(s + System.lineSeparator()));
    }

    private void setStatus(String s) {
        Platform.runLater(() -> statusLabel.setText(s));
    }

    private void alertDialog(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle("Input error");
            a.setHeaderText(null);
            a.setContentText(msg);
            a.showAndWait();
        });
    }
    @Override
    public void stop() {
        try {
            connection.shutdown();
        } catch (Exception ignored) {
        }
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0 && looksLikeSshAskPass(args[0]) && System.getenv("SSH_ASKPASS") != null) {
            AskPassMain.main(args);
            return;
        }
        launch(args);
    }

    private static boolean looksLikeSshAskPass(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase();
        return p.contains("passphrase")
                || p.contains("password")
                || p.contains("verification code")
                || p.contains("otp")
                || p.contains("enter");
    }
}
