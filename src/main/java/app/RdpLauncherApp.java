package app;

import java.util.concurrent.atomic.AtomicBoolean;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

public class RdpLauncherApp extends Application {

    private static final String LOCAL_BIND = "127.0.0.1";
    private static final String LOOPBACK_HOST_FOR_RDP = "localhost";
    private static final String MSTSC_EXE = "C:\\Windows\\System32\\mstsc.exe";

    private static final Path APP_DIR = Paths.get(System.getProperty("user.home"), "rdp-launcher");
    private static final Path SESSIONS_CSV = APP_DIR.resolve("sessions.csv");
    private static final Path APP_KNOWN_HOSTS = APP_DIR.resolve("known_hosts");

    private final Connection connection = new Connection(
            LOCAL_BIND, LOOPBACK_HOST_FOR_RDP, MSTSC_EXE, APP_DIR, APP_KNOWN_HOSTS
    );

    // UI（左：セッション）
    private ListView<Session> sessionList;
    private final ObservableList<Session> sessions = FXCollections.observableArrayList();

    // UI（右：接続先）
    private TextField nameField;
    private CheckBox useBastionChk;
    private Label bastionAliasLabel;
    private TextField sshAliasField;
    private Label bastionOptionsLabel;
    private TextField sshOptionsField;

    private TextField rdpHostField;
    private TextField rdpPortField;

    // UI（右：資格情報：保存しない）
    private TextField userField;
    private PasswordField passField;
    private TextField domainField;
    private CheckBox autoSaveUserChk;

    private TextArea logArea;
    private Label statusLabel;

    private Button newBtn, saveBtn, deleteBtn, detailsBtn;
    private Button connectBtn, disconnectBtn;

    private boolean loadingForm = false;

    private record FormState(
            String name, boolean useBastion, String sshAlias, String sshOptions,
            String rdpHost, String rdpPort, String username, String domain
    ) {}
    private FormState baselineState = null;
    private final PauseTransition dirtyDebounce = new PauseTransition(javafx.util.Duration.millis(250));
    private boolean pendingClearName = false;

    @Override
    public void start(Stage stage) {
        // 左：セッション一覧
        sessionList = new ListView<>(sessions);
        sessionList.setPrefWidth(260);
        sessionList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (loadingForm) return;
            if (newV != null) loadToForm(newV);
        });

        sessionList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                if (sessionList.getSelectionModel().getSelectedItem() == null) return;
                onConnect();
            }
        });
        sessionList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (sessionList.getSelectionModel().getSelectedItem() == null) return;
                onConnect();
                e.consume();
            }
        });

        // 右：フォーム
        nameField = new TextField("");
        nameField.setPromptText("セッションの名前");

        useBastionChk = new CheckBox("踏み台を使用する（SSHトンネル）");
        useBastionChk.setSelected(true);

        sshAliasField = new TextField("");
        sshAliasField.setPromptText("例: rdp / user@bastion.example.com / 10.0.0.10");

        bastionOptionsLabel = new Label("SSHオプション（任意）");
        sshOptionsField = new TextField("");
        sshOptionsField.setPromptText("例: -p 2222 -i C:\\Users\\me\\.ssh\\id_ed25519");

        rdpHostField = new TextField("");
        rdpHostField.setPromptText("Remote Desktop接続先のホスト名またはIPアドレス");

        rdpPortField = new TextField("");
        rdpPortField.setPromptText("Remote Desktop接続先のポート番号（通常3389）");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);

        form.add(new Label("名前"), 0, 0);
        form.add(nameField, 1, 0);

        form.add(new Label("踏み台"), 0, 1);
        form.add(useBastionChk, 1, 1);

        bastionAliasLabel = new Label("SSHConfig設定名/ユーザー名@踏み台サーバのIPまたはホスト名");
        form.add(bastionAliasLabel, 0, 2);
        form.add(sshAliasField, 1, 2);

        form.add(bastionOptionsLabel, 0, 3);
        form.add(sshOptionsField, 1, 3);

        form.add(new Label("RDP host"), 0, 4);
        form.add(rdpHostField, 1, 4);

        form.add(new Label("RDP port"), 0, 5);
        form.add(rdpPortField, 1, 5);

        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(180);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);

        useBastionChk.selectedProperty().addListener((obs, ov, nv) -> applyBastionUi(nv));
        applyBastionUi(useBastionChk.isSelected());

        // 資格情報（保存しない）
        userField = new TextField("");
        passField = new PasswordField();
        domainField = new TextField("");

        autoSaveUserChk = new CheckBox("保存時に Username/Domain もセッションに保存（Passwordは保存しない）");
        autoSaveUserChk.setSelected(true);

        GridPane cred = new GridPane();
        cred.setHgap(10);
        cred.setVgap(8);
        cred.getColumnConstraints().addAll(c0, c1);

        cred.add(new Label("Username"), 0, 0); cred.add(userField, 1, 0);
        cred.add(new Label("Password"), 0, 1); cred.add(passField, 1, 1);
        cred.add(new Label("Domain/Prefix (optional)"), 0, 2); cred.add(domainField, 1, 2);
        cred.add(autoSaveUserChk, 1, 3);

        TitledPane credPane = new TitledPane("ログイン情報", cred);
        credPane.setExpanded(true);

        // ボタン列
        connectBtn = new Button("接続");
        disconnectBtn = new Button("切断");
        disconnectBtn.setDisable(true);
        connectBtn.setDefaultButton(true);

        connectBtn.setOnAction(e -> onConnect());
        disconnectBtn.setOnAction(e -> onDisconnect());

        detailsBtn = new Button("詳細設定");
        detailsBtn.setOnAction(e -> onDetails());

        newBtn = new Button("新しいセッション");
        saveBtn = new Button("保存");
        deleteBtn = new Button("削除");
        deleteBtn.setStyle("-fx-text-fill: #b00020;");

        newBtn.setOnAction(e -> onNew());
        saveBtn.setOnAction(e -> onSave());
        deleteBtn.setOnAction(e -> onDelete());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(10, connectBtn, disconnectBtn, detailsBtn, spacer, newBtn, saveBtn, deleteBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Ready");

        VBox rightTop = new VBox(10, form, credPane, new Separator(), toolbar, new Separator(), statusLabel);
        rightTop.setPadding(new Insets(10));

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        VBox right = new VBox(10, rightTop, new Label("Log"), logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        BorderPane root = new BorderPane();

        Label sessionsLabel = new Label("セッション一覧");
        Button newLeftBtn = new Button("新しいセッション");
        newLeftBtn.setOnAction(e -> onNew());

        HBox leftHeader = new HBox(10, sessionsLabel, newLeftBtn);
        leftHeader.setPadding(new Insets(10, 10, 0, 10));

        VBox leftBox = new VBox(10, leftHeader, sessionList);
        leftBox.setPadding(new Insets(0, 10, 10, 10));
        VBox.setVgrow(sessionList, Priority.ALWAYS);

        root.setLeft(leftBox);
        root.setCenter(right);
        BorderPane.setMargin(right, new Insets(0, 10, 10, 10));

        try {
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/app/app.png"))));
        } catch (Exception ignored) {}

        stage.setTitle("Windows Remote Desktop Launcher");
        stage.setScene(new Scene(root, 1020, 700));
        stage.show();

        dirtyDebounce.setOnFinished(e -> evaluateDirtyAndConvertIfNeeded(pendingClearName));
        installDirtyHandlers();

        try {
            loadSessionsFromDisk();
            if (!sessions.isEmpty()) sessionList.getSelectionModel().select(0);
            else onNew();
        } catch (Exception ex) {
            appendLog("[ERROR] Failed to load sessions: " + ex.getMessage());
            onNew();
        }
    }

    // ---------------------------
    // Connect/Disconnect
    // ---------------------------

    private void onConnect() {
        Session base = readFromFormValidated();
        if (base == null) return;

        final String snapUser = userField.getText();
        final String snapDom  = domainField.getText();
        final String snapPass = passField.getText();

        if (autoSaveUserChk.isSelected()) {
            onSave(); // ★ここで同名セッションが更新される
        }

        // ★保存後/未保存でも、同名の既存セッションから詳細設定を拾う
        Session selected = sessionList.getSelectionModel().getSelectedItem();
        Session existing = (selected != null && selected.name().equalsIgnoreCase(base.name()))
                ? selected
                : (indexOfName(base.name()) >= 0 ? sessions.get(indexOfName(base.name())) : null);

        boolean fullscreen = (existing != null) && existing.fullscreen();
        Integer width      = (existing != null) ? existing.width()  : null;
        Integer height     = (existing != null) ? existing.height() : null;
        boolean multimon   = (existing != null) && existing.multimon();
        boolean span       = (existing != null) && existing.span();

        // Sessionに保存済みの username/domain も渡す（UI未入力時のフォールバック用）
        String savedUser = (existing != null) ? norm(existing.username()) : "";
        String savedDom  = (existing != null) ? norm(existing.domain())   : "";

        Session effective = new Session(
                base.name(),
                base.useBastion(),
                base.sshAlias(),
                base.sshOptions(),
                base.rdpHost(), base.rdpPort(),
                savedUser, savedDom,
                fullscreen, width, height,
                multimon, span
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
        if (!disconnecting.compareAndSet(false, true)) return; // 二重押し防止

        appendLog("[INFO] Disconnect clicked");
        setStatus("切断中...");
        Platform.runLater(() -> {
            disconnectBtn.setDisable(true); // 押したら即 disable（見た目の反応）
            // passField.clear();
        });

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
                Platform.runLater(() -> alertDialog("切断に失敗しました:\n" + t));
                // 失敗したら再試行できるようにボタンを戻す（状態は connection 側に任せる）
                Platform.runLater(() -> disconnectBtn.setDisable(false));
            } finally {
                disconnecting.set(false);
            }
        }, "rdp-launcher-disconnect").start();
    }


    private final AtomicBoolean disconnecting = new AtomicBoolean(false);


    private void setInputsDisabled(boolean connecting) {
        Platform.runLater(() -> {
            nameField.setDisable(connecting);

            useBastionChk.setDisable(connecting);
            sshAliasField.setDisable(connecting || !useBastionChk.isSelected());
            sshOptionsField.setDisable(connecting || !useBastionChk.isSelected());

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

    // ---------------------------
    // Dirty tracking（そのまま）
    // ---------------------------

    private void installDirtyHandlers() {
        nameField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(false));
        useBastionChk.selectedProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        sshAliasField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        sshOptionsField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        rdpHostField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        rdpPortField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        userField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(false));
        domainField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(false));
    }

    private void scheduleDirtyCheck(boolean clearName) {
        if (loadingForm) return;
        if (sessionList.getSelectionModel().getSelectedItem() == null) return;
        if (baselineState == null) return;

        pendingClearName = pendingClearName || clearName;
        dirtyDebounce.playFromStart();
    }

    private void evaluateDirtyAndConvertIfNeeded(boolean clearName) {
        if (loadingForm) return;
        Session sel = sessionList.getSelectionModel().getSelectedItem();
        if (sel == null || baselineState == null) { pendingClearName = false; return; }

        FormState now = snapshotCurrentFormState();
        if (statesEqual(now, baselineState)) { pendingClearName = false; return; }

        sessionList.getSelectionModel().clearSelection();
        baselineState = null;
        dirtyDebounce.stop();

        if (clearName) nameField.clear();
        nameField.setPromptText("New session (unsaved)");
        appendLog("[INFO] Edited selected session -> treated as NEW (unsaved)");
        pendingClearName = false;
    }

    private static boolean statesEqual(FormState a, FormState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        return Objects.equals(norm(a.name), norm(b.name))
                && a.useBastion == b.useBastion
                && Objects.equals(norm(a.sshAlias), norm(b.sshAlias))
                && Objects.equals(norm(a.sshOptions), norm(b.sshOptions))
                && Objects.equals(norm(a.rdpHost), norm(b.rdpHost))
                && Objects.equals(norm(a.rdpPort), norm(b.rdpPort))
                && Objects.equals(norm(a.username), norm(b.username))
                && Objects.equals(norm(a.domain), norm(b.domain));
    }

    private static String norm(String s) { return (s == null) ? "" : s.trim(); }

    private FormState snapshotCurrentFormState() {
        return new FormState(
                norm(nameField.getText()),
                useBastionChk.isSelected(),
                norm(sshAliasField.getText()),
                norm(sshOptionsField.getText()),
                norm(rdpHostField.getText()),
                norm(rdpPortField.getText()),
                norm(userField.getText()),
                norm(domainField.getText())
        );
    }

    private void setBaselineFromLoadedSession(Session s) {
        baselineState = new FormState(
                norm(s.name()),
                s.useBastion(),
                norm(s.sshAlias()),
                norm(s.sshOptions()),
                norm(s.rdpHost()),
                String.valueOf(s.rdpPort()),
                norm(s.username()),
                norm(s.domain())
        );
        pendingClearName = false;
        dirtyDebounce.stop();
        nameField.setPromptText("セッションの名前");
    }

    private void clearBaseline() {
        baselineState = null;
        pendingClearName = false;
        dirtyDebounce.stop();
    }

    // ---------------------------
    // UI
    // ---------------------------

    private void applyBastionUi(boolean enabled) {
        sshAliasField.setDisable(!enabled);
        sshOptionsField.setDisable(!enabled);

        bastionAliasLabel.setManaged(enabled);
        bastionAliasLabel.setVisible(enabled);

        sshAliasField.setManaged(enabled);
        sshAliasField.setVisible(enabled);

        bastionOptionsLabel.setManaged(enabled);
        bastionOptionsLabel.setVisible(enabled);

        sshOptionsField.setManaged(enabled);
        sshOptionsField.setVisible(enabled);
    }

    // ---------------------------
    // Session CRUD
    // ---------------------------

    private void onNew() {
        loadingForm = true;
        try {
            nameField.setText("");
            nameField.setPromptText("New session (unsaved)");

            useBastionChk.setSelected(true);
            sshAliasField.setText("rdp");
            sshOptionsField.setText("");
            applyBastionUi(true);

            rdpHostField.setText("");
            rdpPortField.setText("3389");

            userField.setText("");
            domainField.setText("");
            passField.clear();

            sessionList.getSelectionModel().clearSelection();
        } finally {
            loadingForm = false;
        }

        clearBaseline();
        appendLog("[INFO] New session");
    }

    private void onSave() {
        Session base = readFromFormValidated();
        if (base == null) return;

        // 既存の同名セッション（= 詳細設定の保持元）
        int idx = indexOfName(base.name());
        Session existing = (idx >= 0) ? sessions.get(idx) : null;

        // Username/Domain の保存はチェックONのときだけ。
        // OFFなら既存値を保持（無ければ空）
        String username;
        String domain;
        if (autoSaveUserChk.isSelected()) {
            username = norm(userField.getText());
            domain   = norm(domainField.getText());
        } else {
            username = (existing != null) ? norm(existing.username()) : "";
            domain   = (existing != null) ? norm(existing.domain())   : "";
        }

        // ★詳細設定は既存から引き継ぐ（無ければデフォルト）
        boolean fullscreen = (existing != null) && existing.fullscreen();
        Integer width      = (existing != null) ? existing.width()   : null;
        Integer height     = (existing != null) ? existing.height()  : null;
        boolean multimon   = (existing != null) && existing.multimon();
        boolean span       = (existing != null) && existing.span();

        Session merged = new Session(
                base.name(),
                base.useBastion(),
                base.sshAlias(),
                base.sshOptions(),
                base.rdpHost(), base.rdpPort(),
                username, domain,
                fullscreen, width, height,
                multimon, span
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

        setBaselineFromLoadedSession(merged);
    }


    private void onDelete() {
        Session sel = sessionList.getSelectionModel().getSelectedItem();
        if (sel == null) { alertDialog("削除するセッションを選択してください"); return; }

        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("削除確認");
        a.setHeaderText(null);
        a.setContentText("「" + sel.name() + "」を削除します。よろしいですか？");
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

        // 既存セッションが選択されていれば、その詳細設定を初期値にする
        Session sel = sessionList.getSelectionModel().getSelectedItem();
        Session cur = (sel != null && sel.name().equalsIgnoreCase(base.name())) ? sel : base;

        CheckBox fullscreenChk = new CheckBox("Fullscreen (/f)");
        fullscreenChk.setSelected(cur.fullscreen());

        TextField wField = new TextField(cur.width() == null ? "" : String.valueOf(cur.width()));
        TextField hField = new TextField(cur.height() == null ? "" : String.valueOf(cur.height()));
        wField.setPromptText("e.g. 1920");
        hField.setPromptText("e.g. 1080");

        // フルスクリーン時は /w /h を無効化（保存時も null にする）
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

        // 排他
        multimonChk.selectedProperty().addListener((o, ov, nv) -> { if (nv) spanChk.setSelected(false); });
        spanChk.selectedProperty().addListener((o, ov, nv) -> { if (nv) multimonChk.setSelected(false); });

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(12));

        g.add(fullscreenChk, 0, 0, 2, 1);
        g.add(new Label("Width (/w)"), 0, 1);  g.add(wField, 1, 1);
        g.add(new Label("Height (/h)"), 0, 2); g.add(hField, 1, 2);
        g.add(multimonChk, 0, 3, 2, 1);
        g.add(spanChk, 0, 4, 2, 1);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("詳細設定");
        dialog.getDialogPane().setContent(g);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;

            Integer w = parseNullableInt(wField.getText());
            Integer h = parseNullableInt(hField.getText());

            boolean fs = fullscreenChk.isSelected();
            if (fs) { w = null; h = null; }

            // Username/Domain はフォームの入力を優先（※Passwordは保存しない）
            String username = (userField.getText() == null) ? "" : userField.getText().trim();
            String domain   = (domainField.getText() == null) ? "" : domainField.getText().trim();

            Session updated = new Session(
                    base.name(),
                    base.useBastion(),
                    base.sshAlias(),
                    base.sshOptions(),
                    base.rdpHost(), base.rdpPort(),
                    username, domain,
                    fs, w, h,
                    multimonChk.isSelected(),
                    spanChk.isSelected()
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

            // dirty扱いにしない
            setBaselineFromLoadedSession(updated);
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
        String sshAlias = norm(sshAliasField.getText());
        String sshOptions = norm(sshOptionsField.getText());

        String rdpHost = norm(rdpHostField.getText());
        String rdpPortText = norm(rdpPortField.getText());

        if (name.isEmpty()) { alertDialog("Name が空です"); return null; }
        if (useBastion && sshAlias.isEmpty()) {
            alertDialog("踏み台を使用する場合は「踏み台（SSHConfig設定名）」を入力してください（例: rdp / user@host）");
            return null;
        }
        if (rdpHost.isEmpty()) { alertDialog("RDP host が空です"); return null; }

        int rdpPort;
        try {
            rdpPort = Integer.parseInt(rdpPortText);
            if (rdpPort < 1 || rdpPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            alertDialog("RDP port は 1〜65535 の数字で入力してください");
            return null;
        }

        return new Session(
                name, useBastion,
                sshAlias, sshOptions,
                rdpHost, rdpPort,
                "", "",
                false, null, null,
                false, false
        );
    }

    private void loadToForm(Session s) {
        loadingForm = true;
        try {
            nameField.setText(s.name());
            nameField.setPromptText("セッションの名前");

            useBastionChk.setSelected(s.useBastion());
            sshAliasField.setText(s.sshAlias() == null ? "" : s.sshAlias());
            sshOptionsField.setText(s.sshOptions() == null ? "" : s.sshOptions());
            applyBastionUi(s.useBastion());

            rdpHostField.setText(s.rdpHost());
            rdpPortField.setText(String.valueOf(s.rdpPort()));

            userField.setText(s.username() == null ? "" : s.username());
            domainField.setText(s.domain() == null ? "" : s.domain());

            //passField.clear();
        } finally {
            loadingForm = false;
        }

        setBaselineFromLoadedSession(s);
    }

    // ---------------------------
    // CSV Save/Load
    // ---------------------------

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
            try { rdpPort = Integer.parseInt(parts[i++].trim()); }
            catch (NumberFormatException e) { continue; }

            String username = (parts.length > i) ? parts[i++].trim() : "";
            String domain   = (parts.length > i) ? parts[i++].trim() : "";

            boolean fullscreen = (parts.length > i) && Boolean.parseBoolean(parts[i++].trim());
            Integer width = (parts.length > i) ? parseNullableInt(parts[i++]) : null;
            Integer height = (parts.length > i) ? parseNullableInt(parts[i++]) : null;
            boolean multimon = (parts.length > i) && Boolean.parseBoolean(parts[i++].trim());
            boolean span = (parts.length > i) && Boolean.parseBoolean(parts[i++].trim());

            sessions.add(new Session(
                    name,
                    useBastion, sshAlias, sshOptions,
                    rdpHost, rdpPort,
                    username, domain,
                    fullscreen, width, height, multimon, span
            ));
        }

        FXCollections.sort(sessions, Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
        appendLog("[INFO] Loaded sessions: " + sessions.size() + " (" + SESSIONS_CSV + ")");
    }

    private void saveSessionsToDisk() throws IOException {
        Files.createDirectories(APP_DIR);

        StringBuilder sb = new StringBuilder();
        sb.append("name,useBastion,sshAlias,sshOptions,rdpHost,rdpPort,username,domain,fullscreen,width,height,multimon,span")
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
                    .append(s.span())
                    .append(System.lineSeparator());
        }

        Files.writeString(SESSIONS_CSV, sb.toString(), Charset.forName("UTF-8"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        appendLog("[INFO] Saved sessions: " + sessions.size() + " (" + SESSIONS_CSV + ")");
    }

    private static Integer parseNullableInt(String s) {
        String t = (s == null) ? "" : s.trim();
        if (t.isEmpty()) return null;
        try {
            int v = Integer.parseInt(t);
            if (v <= 0) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // ---------------------------
    // UI helpers
    // ---------------------------

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
        try { connection.shutdown(); } catch (Exception ignored) {}
    }

    public static void main(String[] args) {
        // If invoked by OpenSSH as SSH_ASKPASS helper, args[0] is the prompt.
        if (args != null && args.length > 0 && looksLikeSshAskPass(args[0]) && System.getenv("SSH_ASKPASS") != null) {
            AskPassMain.main(args); // shows dialog and prints to stdout, then System.exit()
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
