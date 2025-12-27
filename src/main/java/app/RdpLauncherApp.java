package app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.scene.input.KeyCode;
import javafx.animation.PauseTransition;
import javafx.scene.image.Image;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class RdpLauncherApp extends Application {

    private static final String LOCAL_BIND = "127.0.0.1";  // ポート開通チェック用（127固定でOK）
    private static final String LOOPBACK_HOST_FOR_RDP = "localhost"; // 踏み台ON時のRDP接続先/資格情報のキーを統一
    private static final String MSTSC_EXE = "C:\\Windows\\System32\\mstsc.exe";

    // 保存先（セッション。※パスワードは保存しない）
    private static final Path APP_DIR = Paths.get(System.getProperty("user.home"), "rdp-launcher");
    private static final Path SESSIONS_CSV = APP_DIR.resolve("sessions.csv");

    // UI（左：セッション）
    private ListView<Session> sessionList;
    private final ObservableList<Session> sessions = FXCollections.observableArrayList();

    // UI（右：接続先）
    private TextField nameField;
    private CheckBox useBastionChk;    // 踏み台を使う/使わない
    private Label bastionAliasLabel;   // 踏み台（SSH設定名）ラベル（非表示制御用）
    private TextField sshAliasField;   // 踏み台（SSH設定名 or user@host）
    private Label bastionOptionsLabel;
    private TextField sshOptionsField; // -p や -i など

    private TextField rdpHostField;
    private TextField rdpPortField;

    // --- 「選択中を編集したら新規扱い」用 ---
    private boolean loadingForm = false;          // loadToForm/onNew中の変更検知を無視する

    // ★「実際に変わった時だけ New(unsaved)」用
    private FormState baselineState = null;       // 選択して読み込んだ直後のフォーム状態
    private final PauseTransition dirtyDebounce = new PauseTransition(javafx.util.Duration.millis(250));
    private boolean pendingClearName = false;     // デバウンス中に「名前をクリアしたい編集」が含まれたか

    // 直近のSSHログ（エラー解析用）
    private volatile String lastSshOutLog = null;
    private volatile String lastSshErrLog = null;

    // UI（右：資格情報：保存しない）
    private TextField userField;
    private PasswordField passField;
    private TextField domainField;     // 任意
    private CheckBox autoSaveUserChk;  // 接続時に Username/Domain をセッションへ保存（Passwordは保存しない）

    private TextArea logArea;
    private Label statusLabel;

    private Button newBtn, saveBtn, connectBtn, disconnectBtn, detailsBtn;

    // 実行（ConnectがwaitForで塞がってもDisconnectを動かすため2スレッド）
    private final ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "worker");
        t.setDaemon(true);
        return t;
    });

    private volatile int sshPid = -1;
    private volatile Process mstscProc = null;

    @Override
    public void start(Stage stage) {

        // 左：セッション一覧
        sessionList = new ListView<>(sessions);
        sessionList.setPrefWidth(260);
        sessionList.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) loadToForm(newV);
        });

        // セッション一覧：ダブルクリックで接続
        sessionList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                if (connectBtn != null && connectBtn.isDisabled()) return;
                if (sessionList.getSelectionModel().getSelectedItem() == null) return;
                onConnect();
            }
        });

        // セッション一覧：Enterで接続
        sessionList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (connectBtn != null && connectBtn.isDisabled()) return;
                if (sessionList.getSelectionModel().getSelectedItem() == null) return;
                onConnect();
                e.consume();
            }
        });

        // 右：フォーム（接続先）
        nameField = new TextField("");
        nameField.setPromptText("セッションの名前");

        useBastionChk = new CheckBox("踏み台を使用する（SSHトンネル）");
        useBastionChk.setSelected(true);

        sshAliasField = new TextField("");
        sshAliasField.setPromptText("例: rdp / user@bastion.example.com / 10.0.0.10");

        bastionOptionsLabel = new Label("SSHオプション（任意）");
        sshOptionsField = new TextField("");
        sshOptionsField.setPromptText("例: -p 2222 -i C:\\Users\\me\\.ssh\\id_ed25519 -o ServerAliveInterval=30");
        sshOptionsField.setTooltip(new Tooltip(
                "ssh.exe に渡す追加オプションです。\n" +
                        "例: -p 2222 -i C:\\Users\\me\\.ssh\\id_ed25519\n" +
                        "空でもOKです。"
        ));

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

        // 踏み台OFFなら踏み台設定を触れない＆行ごと隠す
        useBastionChk.selectedProperty().addListener((obs, ov, nv) -> applyBastionUi(nv));
        applyBastionUi(useBastionChk.isSelected());

        // 資格情報（保存しない）
        userField = new TextField("");
        passField = new PasswordField();
        domainField = new TextField("");

        autoSaveUserChk = new CheckBox("接続時に Username/Domain をセッションに保存（Passwordは保存しない）");
        autoSaveUserChk.setSelected(true);

        GridPane cred = new GridPane();
        cred.setHgap(10);
        cred.setVgap(8);
        cred.getColumnConstraints().addAll(c0, c1);

        cred.add(new Label("Username"), 0, 0);   cred.add(userField, 1, 0);
        cred.add(new Label("Password"), 0, 1);   cred.add(passField, 1, 1);
        cred.add(new Label("Domain/Prefix (optional)"), 0, 2); cred.add(domainField, 1, 2);
        cred.add(autoSaveUserChk, 1, 3);

        TitledPane credPane = new TitledPane("ログイン情報", cred);
        credPane.setExpanded(true);

        // ボタン列
        detailsBtn = new Button("詳細設定");
        detailsBtn.setOnAction(e -> onDetails());

        newBtn = new Button("新しいセッション");
        saveBtn = new Button("保存");
        connectBtn = new Button("接続");
        disconnectBtn = new Button("切断");
        disconnectBtn.setDisable(true);

        newBtn.setOnAction(e -> onNew());
        saveBtn.setOnAction(e -> onSave());
        connectBtn.setOnAction(e -> onConnect());
        disconnectBtn.setOnAction(e -> onDisconnect());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(10,
                connectBtn, disconnectBtn, detailsBtn,
                spacer,
                newBtn, saveBtn
        );
        toolbar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Ready");

        VBox rightTop = new VBox(10, form, credPane, new Separator(), toolbar, new Separator(), statusLabel);
        rightTop.setPadding(new Insets(10));

        // ログ
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);

        VBox right = new VBox(10, rightTop, new Label("Log"), logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        // 全体レイアウト
        BorderPane root = new BorderPane();

        Label sessionsLabel = new Label("セッション一覧");
        Button newLeftBtn = new Button("新しいセッション");
        newLeftBtn.setOnAction(e -> onNew());

        Button deleteLeftBtn = new Button("削除");
        deleteLeftBtn.setOnAction(e -> onDelete());
        deleteLeftBtn.setStyle("-fx-text-fill: #b00020;");

        HBox leftHeader = new HBox(10, sessionsLabel, newLeftBtn, deleteLeftBtn);
        leftHeader.setPadding(new Insets(10, 10, 0, 10));

        VBox leftBox = new VBox(10, leftHeader, sessionList);
        leftBox.setPadding(new Insets(0, 10, 10, 10));
        VBox.setVgrow(sessionList, Priority.ALWAYS);

        root.setLeft(leftBox);
        root.setCenter(right);
        BorderPane.setMargin(right, new Insets(0, 10, 10, 10));

        stage.getIcons().add(new Image(
                Objects.requireNonNull(getClass().getResourceAsStream("/app/app.png"))
        ));
        stage.setTitle("Windows Remote Desktop Launcher");
        stage.setScene(new Scene(root, 1020, 700));
        stage.show();

        // ★dirty判定（デバウンス後に評価）
        dirtyDebounce.setOnFinished(e -> evaluateDirtyAndConvertIfNeeded(pendingClearName));

        installDirtyHandlers();

        // 起動時：前回残った資格情報の掃除（安全策） ※ここも Hidden 実行に変わる（deleteTempCredentialがHiddenになったため）
        exec.submit(() -> {
            try {
                deleteTempCredential("localhost");
                deleteTempCredential("127.0.0.1");
            } catch (Exception ignored) {}
        });

        // 起動時ロード
        try {
            loadSessionsFromDisk();
            if (!sessions.isEmpty()) sessionList.getSelectionModel().select(0);
        } catch (Exception ex) {
            appendLog("[ERROR] Failed to load sessions: " + ex.getMessage());
        }
    }

    // ---------------------------
    // Dirty tracking (実際に変わった時だけ New(unsaved))
    // ---------------------------

    private record FormState(
            String name,
            boolean useBastion,
            String sshAlias,
            String sshOptions,
            String rdpHost,
            String rdpPort,
            String username,
            String domain
    ) {}

    private void installDirtyHandlers() {
        // 「保存対象の項目」のみ監視（Passwordは監視しない）
        // clearName=true のものは、dirty確定時に nameField を空にして prompt を見せる
        nameField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(false));
        useBastionChk.selectedProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        sshAliasField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        sshOptionsField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        rdpHostField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));
        rdpPortField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(true));

        userField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(false));
        domainField.textProperty().addListener((o, ov, nv) -> scheduleDirtyCheck(false));

        // passField は監視しない（入力しただけで New 扱いにしない）
    }

    private void scheduleDirtyCheck(boolean clearName) {
        if (loadingForm) return;
        if (sessionList.getSelectionModel().getSelectedItem() == null) return; // 「選択中だけ」新規扱い
        if (baselineState == null) return;

        pendingClearName = pendingClearName || clearName;
        dirtyDebounce.playFromStart();
    }

    private void evaluateDirtyAndConvertIfNeeded(boolean clearName) {
        // デバウンス後、最終状態で比較する
        if (loadingForm) return;
        Session sel = sessionList.getSelectionModel().getSelectedItem();
        if (sel == null) { pendingClearName = false; return; }
        if (baselineState == null) { pendingClearName = false; return; }

        FormState now = snapshotCurrentFormState();
        if (statesEqual(now, baselineState)) {
            // 変化なし（同じ値を入れ直しただけ / 変更して戻した）
            pendingClearName = false;
            return;
        }

        // ★ここで初めて「新規(unsaved)」へ
        sessionList.getSelectionModel().clearSelection();
        baselineState = null; // 以後の編集は新規扱いなので比較不要
        dirtyDebounce.stop();

        if (clearName) {
            nameField.clear(); // prompt を見せる
        }
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

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim();
    }

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
        // ★loadToForm直後の「正」となる状態を保持
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
        nameField.setPromptText("セッションの名前"); // 既存セッション表示時は通常に戻す
    }

    private void clearBaseline() {
        baselineState = null;
        pendingClearName = false;
        dirtyDebounce.stop();
    }

    // ---------------------------

    // 踏み台（SSHトンネル）UIの表示/非表示
    private void applyBastionUi(boolean enabled) {
        if (sshAliasField != null) sshAliasField.setDisable(!enabled);
        if (sshOptionsField != null) sshOptionsField.setDisable(!enabled);

        if (bastionAliasLabel != null) {
            bastionAliasLabel.setManaged(enabled);
            bastionAliasLabel.setVisible(enabled);
        }
        if (sshAliasField != null) {
            sshAliasField.setManaged(enabled);
            sshAliasField.setVisible(enabled);
        }
        if (bastionOptionsLabel != null) {
            bastionOptionsLabel.setManaged(enabled);
            bastionOptionsLabel.setVisible(enabled);
        }
        if (sshOptionsField != null) {
            sshOptionsField.setManaged(enabled);
            sshOptionsField.setVisible(enabled);
        }
    }

    // --- セッションUI操作 ---
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

        Session sel = sessionList.getSelectionModel().getSelectedItem();
        Session cur = (sel != null && sel.name().equalsIgnoreCase(base.name())) ? sel : base;

        String username = userField.getText().trim();
        String domain = domainField.getText().trim();

        Session s = new Session(
                base.name(),
                base.useBastion(),
                base.sshAlias(),
                base.sshOptions(),
                base.rdpHost(), base.rdpPort(),
                username, domain,
                cur.fullscreen(), cur.width(), cur.height(),
                cur.multimon(), cur.span()
        );

        int idx = indexOfName(s.name());

        // ★同名があるなら上書き確認
        if (idx >= 0) {
            if (!confirmOverwriteCentered(s.name())) {
                appendLog("[INFO] Overwrite canceled: " + s.name());
                return;
            }
            sessions.set(idx, s);
        } else {
            sessions.add(s);
        }

        FXCollections.sort(sessions, Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
        sessionList.getSelectionModel().select(s);

        try {
            saveSessionsToDisk();
            appendLog("[INFO] Saved: " + s.name());
        } catch (Exception ex) {
            appendLog("[ERROR] Save failed: " + ex.getMessage());
        }

        // ★保存後は baseline を更新（dirty扱いにしない）
        setBaselineFromLoadedSession(s);
    }

    private void onDelete() {
        Session sel = sessionList.getSelectionModel().getSelectedItem();
        if (sel == null) {
            alert("削除するセッションを選択してください");
            return;
        }

        if (!confirmDeleteCentered(sel.name())) {
            appendLog("[INFO] Delete canceled: " + sel.name());
            return;
        }

        sessions.remove(sel);
        try {
            saveSessionsToDisk();
            appendLog("[INFO] Deleted: " + sel.name());
        } catch (Exception ex) {
            appendLog("[ERROR] Save failed: " + ex.getMessage());
        }
        onNew();
    }

    private boolean confirmOverwriteCentered(String sessionName) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("上書き確認");
        alert.setHeaderText(null);
        alert.setContentText(null);

        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        Label centerTitle = new Label("上書きしますか？");
        centerTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label body = new Label("セッション名「" + sessionName + "」を上書きします。よろしいですか？");
        body.setWrapText(true);

        VBox box = new VBox(12, centerTitle, body);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20));
        box.setMinWidth(420);

        alert.getDialogPane().setContent(box);

        Optional<ButtonType> r = alert.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    private boolean confirmDeleteCentered(String sessionName) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("削除確認");
        alert.setHeaderText(null);
        alert.setContentText(null);

        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        Label centerTitle = new Label("削除しますか？");
        centerTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label body = new Label("「" + sessionName + "」を削除します。\nこの操作は取り消せません。");
        body.setWrapText(true);

        VBox box = new VBox(12, centerTitle, body);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20));
        box.setMinWidth(420);

        alert.getDialogPane().setContent(box);

        Optional<ButtonType> r = alert.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    private void onDetails() {
        Session base = readFromFormValidated();
        if (base == null) return;

        Session sel = sessionList.getSelectionModel().getSelectedItem();
        Session current = (sel != null && sel.name().equalsIgnoreCase(base.name())) ? sel : base;

        CheckBox fullscreenChk = new CheckBox("Fullscreen (/f)");
        fullscreenChk.setSelected(current.fullscreen());

        TextField wField = new TextField(current.width() == null ? "" : String.valueOf(current.width()));
        TextField hField = new TextField(current.height() == null ? "" : String.valueOf(current.height()));
        wField.setPromptText("e.g. 1920");
        hField.setPromptText("e.g. 1080");

        CheckBox multimonChk = new CheckBox("Use multi-monitor (/multimon)");
        multimonChk.setSelected(current.multimon());

        CheckBox spanChk = new CheckBox("Span monitors (/span)");
        spanChk.setSelected(current.span());

        multimonChk.selectedProperty().addListener((o, ov, nv) -> { if (nv) spanChk.setSelected(false); });
        spanChk.selectedProperty().addListener((o, ov, nv) -> { if (nv) multimonChk.setSelected(false); });

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(12));

        g.add(fullscreenChk, 0, 0, 2, 1);
        g.add(new Label("Width (/w)"), 0, 1); g.add(wField, 1, 1);
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

            Session updated = new Session(
                    base.name(),
                    base.useBastion(),
                    base.sshAlias(),
                    base.sshOptions(),
                    base.rdpHost(), base.rdpPort(),
                    current.username(), current.domain(),
                    fs, w, h,
                    multimonChk.isSelected(), spanChk.isSelected()
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

            // ★詳細保存後も baseline を更新しておく（dirty扱いにしない）
            setBaselineFromLoadedSession(updated);
        });
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

            passField.clear();
        } finally {
            loadingForm = false;
        }

        // ★load直後の baseline 設定（ここがポイント）
        setBaselineFromLoadedSession(s);
    }

    private int indexOfName(String name) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private Session readFromFormValidated() {
        String name = nameField.getText().trim();

        boolean useBastion = useBastionChk.isSelected();
        String sshAlias = sshAliasField.getText().trim();
        String sshOptions = (sshOptionsField == null) ? "" : sshOptionsField.getText().trim();

        String rdpHost = rdpHostField.getText().trim();
        String rdpPortText = rdpPortField.getText().trim();

        if (name.isEmpty()) { alert("Name が空です"); return null; }
        if (useBastion && sshAlias.isEmpty()) {
            alert("踏み台を使用する場合は「踏み台（SSHConfig設定名）」を入力してください（例: rdp / user@host）");
            return null;
        }
        if (rdpHost.isEmpty()) { alert("RDP host が空です"); return null; }

        int rdpPort;
        try {
            rdpPort = Integer.parseInt(rdpPortText);
            if (rdpPort < 1 || rdpPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            alert("RDP port は 1〜65535 の数字で入力してください");
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

    // --- 接続/切断 ---
    private void onConnect() {
        Session s0 = readFromFormValidated();
        if (s0 == null) return;

        // ★FXスレッドで値をスナップショット（先にやる）
        final String snapUser = userField.getText();
        final String snapDom  = domainField.getText();
        final String snapPass = passField.getText();

        final String userForRdp = buildUsernameForCmdkey(snapUser, snapDom);
        final boolean hasUser = userForRdp != null && !userForRdp.isBlank();
        final boolean hasPass = snapPass != null && !snapPass.isEmpty();

        if (!hasUser && hasPass) {
            alert("Password だけ入力されています。Username を入力してください。");
            return;
        }

        // ★ここで先に保存（保存後に選択セッションを取り直す）
        if (autoSaveUserChk.isSelected()) {
            autoSaveSessionFromCurrentForm();
        }

        // ★保存後に選択を取り直す（ここ重要）
        Session selected = sessionList.getSelectionModel().getSelectedItem();
        Session detailsSource = (selected != null && selected.name().equalsIgnoreCase(s0.name())) ? selected : null;

        // ★接続先/踏み台はフォーム(s0)を正とする。Detailsだけ selected から引き継ぐ
        final Session effective = new Session(
                s0.name(),
                s0.useBastion(),
                s0.sshAlias(),
                s0.sshOptions(),
                s0.rdpHost(), s0.rdpPort(),
                (detailsSource != null ? detailsSource.username() : ""),
                (detailsSource != null ? detailsSource.domain()   : ""),
                (detailsSource != null ? detailsSource.fullscreen() : false),
                (detailsSource != null ? detailsSource.width()      : null),
                (detailsSource != null ? detailsSource.height()     : null),
                (detailsSource != null ? detailsSource.multimon()   : false),
                (detailsSource != null ? detailsSource.span()       : false)
        );

        setInputsDisabled(true);

        appendLog("[INFO] Connect: " + effective.name()
                + " (Bastion=" + (effective.useBastion() ? effective.sshAlias() : "OFF")
                + ", RDP=" + effective.rdpHost() + ":" + effective.rdpPort() + ")");
        if (effective.useBastion() && effective.sshOptions() != null && !effective.sshOptions().isBlank()) {
            appendLog("[INFO] SSH options: " + effective.sshOptions());
        }
        appendLog("[INFO] Login mode: " + (hasUser ? (hasPass ? "AUTO (user+pass)" : "PROMPT (user only)") : "DEFAULT (mstsc)"));
        setStatus("Connecting...");

        exec.submit(() -> {
            boolean tempCredUsed = false;
            String credHostKey = null;
            int localPort = -1;

            Path tempRdpFile = null;

            try {
                String rdpHostToUse = effective.rdpHost();
                int rdpPortToUse = effective.rdpPort();

                if (effective.useBastion()) {
                    localPort = findFreePort();
                    appendLog("[INFO] Using local port: " + localPort);

                    SshStartResult ssh = startSshTunnelHidden(
                            effective.sshAlias(),
                            effective.sshOptions(),
                            localPort,
                            effective.rdpHost(),
                            effective.rdpPort()
                    );
                    sshPid = ssh.pid();
                    lastSshOutLog = ssh.outLog();
                    lastSshErrLog = ssh.errLog();

                    appendLog("[INFO] Bastion tunnel started. PID=" + sshPid);

                    Thread.sleep(350);
                    if (!isProcessAlive(sshPid)) {
                        throw new IllegalStateException(
                                "踏み台(SSH)の起動に失敗しました。\n" +
                                        summarizeSshError(effective.sshAlias(), lastSshErrLog)
                        );
                    }

                    if (!waitLocalPortOpen(LOCAL_BIND, localPort, Duration.ofSeconds(6))) {
                        String detail = summarizeSshError(effective.sshAlias(), lastSshErrLog);
                        throw new IllegalStateException(
                                "踏み台トンネルのローカルポートが開きませんでした: " + LOCAL_BIND + ":" + localPort + "\n" +
                                        detail
                        );
                    }

                    rdpHostToUse = LOOPBACK_HOST_FOR_RDP;
                    rdpPortToUse = localPort;
                }

                appendLog("[INFO] Preparing credentials...");
                setStatus("RDP running");

                credHostKey = rdpHostToUse;

                // user+pass のときだけ自動ログイン（cmdkey）
                if (hasUser && hasPass) {
                    addTempCredential(credHostKey, userForRdp, snapPass);
                    tempCredUsed = true;
                    appendLog("[INFO] Temporary credentials set for TERMSRV/" + credHostKey);
                }

                appendLog("[INFO] Launching mstsc...");
                if (hasUser && !hasPass) {
                    tempRdpFile = createTempRdpFile(
                            rdpHostToUse, rdpPortToUse,
                            userForRdp,
                            effective
                    );
                    mstscProc = launchMstscWithRdpFile(tempRdpFile);
                } else {
                    mstscProc = launchMstsc(rdpHostToUse, rdpPortToUse, effective);
                }

                int exitCode = mstscProc.waitFor();
                appendLog("[INFO] mstsc exited with code: " + exitCode);

            } catch (Exception ex) {
                String msg = ex.getMessage();
                appendLog("[ERROR] " + msg);
                setStatus("Error");

                if (msg != null && msg.contains("踏み台")) {
                    alert(msg);
                }
            } finally {
                mstscProc = null;

                Platform.runLater(() -> passField.clear());

                if (tempRdpFile != null) {
                    try { Files.deleteIfExists(tempRdpFile); } catch (Exception ignored) {}
                }

                if (tempCredUsed && credHostKey != null) {
                    try {
                        deleteTempCredential(credHostKey);
                        appendLog("[INFO] Temporary credentials removed (TERMSRV/" + credHostKey + ")");
                    } catch (Exception ignored) {}
                }

                try { stopSshIfNeeded(); } catch (Exception ignored) {}

                Platform.runLater(() -> {
                    setInputsDisabled(false);
                    connectBtn.setDisable(false);
                    disconnectBtn.setDisable(true);
                });

                if (!"Error".equals(statusLabel.getText())) setStatus("Ready");
            }
        });
    }

    private void autoSaveSessionFromCurrentForm() {
        Session base = readFromFormValidated();
        if (base == null) return;

        Session sel = sessionList.getSelectionModel().getSelectedItem();
        Session cur = (sel != null && sel.name().equalsIgnoreCase(base.name())) ? sel : base;

        String username = userField.getText().trim();
        String domain = domainField.getText().trim();

        Session s = new Session(
                base.name(),
                base.useBastion(),
                base.sshAlias(),
                base.sshOptions(),
                base.rdpHost(), base.rdpPort(),
                username, domain,
                cur.fullscreen(), cur.width(), cur.height(),
                cur.multimon(), cur.span()
        );

        int idx = indexOfName(s.name());
        if (idx >= 0) sessions.set(idx, s);
        else sessions.add(s);

        FXCollections.sort(sessions, Comparator.comparing(Session::name, String.CASE_INSENSITIVE_ORDER));
        sessionList.getSelectionModel().select(s);

        try {
            saveSessionsToDisk();
            appendLog("[INFO] Auto-saved session (username only): " + s.name());
        } catch (Exception ex) {
            appendLog("[ERROR] Auto-save failed: " + ex.getMessage());
        }

        // ★自動保存後も baseline を更新しておく
        setBaselineFromLoadedSession(s);
    }

    private void onDisconnect() {
        appendLog("[INFO] Disconnect requested");
        setStatus("Disconnecting...");

        connectBtn.setDisable(true);
        disconnectBtn.setDisable(true);

        final String snapRdpHost = rdpHostField.getText() == null ? "" : rdpHostField.getText().trim();
        final boolean snapUseBastion = useBastionChk.isSelected();

        exec.submit(() -> {
            try {
                stopMstscIfNeeded();

                try { deleteTempCredential("localhost"); } catch (Exception ignored) {}
                try { deleteTempCredential("127.0.0.1"); } catch (Exception ignored) {}
                if (!snapUseBastion && !snapRdpHost.isEmpty()) {
                    try { deleteTempCredential(snapRdpHost); } catch (Exception ignored) {}
                }

                Platform.runLater(() -> passField.clear());
                stopSshIfNeeded();

            } catch (Exception ex) {
                appendLog("[ERROR] " + ex.getMessage());
            } finally {
                Platform.runLater(() -> {
                    setInputsDisabled(false);
                    connectBtn.setDisable(false);
                    disconnectBtn.setDisable(true);
                });
                setStatus("Ready");
            }
        });
    }

    private void setInputsDisabled(boolean connecting) {
        Platform.runLater(() -> {
            nameField.setDisable(connecting);

            useBastionChk.setDisable(connecting);
            if (sshAliasField != null) sshAliasField.setDisable(connecting || !useBastionChk.isSelected());
            if (sshOptionsField != null) sshOptionsField.setDisable(connecting || !useBastionChk.isSelected());

            rdpHostField.setDisable(connecting);
            rdpPortField.setDisable(connecting);

            userField.setDisable(connecting);
            passField.setDisable(connecting);
            domainField.setDisable(connecting);
            autoSaveUserChk.setDisable(connecting);

            sessionList.setDisable(connecting);

            newBtn.setDisable(connecting);
            if (detailsBtn != null) detailsBtn.setDisable(connecting);
            saveBtn.setDisable(connecting);

            connectBtn.setDisable(connecting);
            disconnectBtn.setDisable(!connecting);
        });
    }

    // ★変更：taskkill を Hidden 実行に変更（コンソールが出ない）
    private void stopSshIfNeeded() throws IOException, InterruptedException {
        int pid = sshPid;
        if (pid <= 0) return;
        sshPid = -1;

        int exit = runHiddenAndWait("taskkill.exe", List.of("/PID", String.valueOf(pid), "/T", "/F"));
        appendLog("[INFO] Bastion tunnel stop requested. PID=" + pid + " (taskkill exit=" + exit + ")");
    }

    private void stopMstscIfNeeded() {
        Process p = mstscProc;
        if (p == null) return;

        try {
            appendLog("[INFO] Closing mstsc...");
            p.destroy();
            if (!p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
        } finally {
            mstscProc = null;
        }
    }

    // --- SSH起動（Hidden） ---
    private record SshStartResult(int pid, String outLog, String errLog) {}

    private static boolean isProcessAlive(int pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String tailTextFile(String path, int maxLines) {
        if (path == null) return "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(path), Charset.defaultCharset());
            if (lines.size() <= maxLines) return String.join(System.lineSeparator(), lines);
            return String.join(System.lineSeparator(), lines.subList(lines.size() - maxLines, lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    private static String summarizeSshError(String sshAlias, String errLogPath) {
        String tail = tailTextFile(errLogPath, 30).trim();
        if (tail.isEmpty()) return "（sshエラーログが空です）";

        String lower = tail.toLowerCase(Locale.ROOT);

        if (lower.contains("could not resolve hostname")
                || lower.contains("name or service not known")
                || lower.contains("no such host")
                || lower.contains("unknown host")) {
            return "ssh の接続先が見つかりません。\n"
                    + "・SSHConfig設定名を使う場合: %USERPROFILE%\\.ssh\\config に Host " + sshAlias + " があるか\n"
                    + "・SSHConfigを使わない場合: 「踏み台」に user@host を入力し、必要ならSSHオプションに -p/-i を指定\n\n"
                    + tail;
        }
        if (lower.contains("permission denied") && lower.contains("publickey")) {
            return "踏み台へのSSH認証に失敗しました（公開鍵/権限）。\n"
                    + "・-i で鍵を指定\n"
                    + "・鍵ファイル権限/ユーザー名/authorized_keys を確認\n\n"
                    + tail;
        }
        return tail;
    }

    private SshStartResult startSshTunnelHidden(
            String sshAlias,
            String sshOptions,
            int localPort,
            String rdpHost,
            int rdpPort
    ) throws IOException, InterruptedException {

        String forward = LOCAL_BIND + ":" + localPort + ":" + rdpHost + ":" + rdpPort;

        String tempDir = System.getenv("TEMP");
        String outLog = tempDir + "\\rdp-launcher-ssh-out.log";
        String errLog = tempDir + "\\rdp-launcher-ssh-err.log";
        appendLog("[INFO] SSH logs: " + outLog + " / " + errLog);

        List<String> args = new ArrayList<>();
        args.add("-N");
        args.add("-L");
        args.add(forward);
        args.add("-o");
        args.add("ExitOnForwardFailure=yes");

        args.addAll(splitSshOptions(sshOptions));
        args.add(sshAlias);

        String argList = args.stream()
                .map(a -> "'" + escapePsSingle(a) + "'")
                .collect(Collectors.joining(","));

        String ps = ""
                + "$p = Start-Process -FilePath 'ssh.exe' "
                + " -ArgumentList @(" + argList + ") "
                + " -WindowStyle Hidden -PassThru "
                + " -RedirectStandardOutput '" + escapePsSingle(outLog) + "' "
                + " -RedirectStandardError '" + escapePsSingle(errLog) + "'; "
                + "Write-Output $p.Id;";

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", ps
        );
        pb.redirectErrorStream(true);

        Process p = pb.start();

        String pidText;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
            pidText = br.readLine();
        }
        int exit = p.waitFor();
        if (exit != 0 || pidText == null || pidText.isBlank()) {
            throw new IllegalStateException("Failed to start hidden ssh. powershell exit=" + exit + " pidLine=" + pidText);
        }
        int pid = Integer.parseInt(pidText.trim());
        return new SshStartResult(pid, outLog, errLog);
    }

    private static String escapePsSingle(String s) {
        return s.replace("'", "''");
    }

    // ★追加：コンソール系 EXE を“確実に表示無し”で実行して exit code を返す
    private static int runHiddenAndWait(String exe, List<String> args)
            throws IOException, InterruptedException {

        String argList = args.stream()
                .map(a -> "'" + escapePsSingle(a) + "'")
                .collect(Collectors.joining(","));

        String ps =
                "$p = Start-Process -FilePath '" + escapePsSingle(exe) + "' " +
                        "-ArgumentList @(" + argList + ") " +
                        "-WindowStyle Hidden -PassThru -Wait; " +
                        "exit $p.ExitCode;";

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", ps
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        return pb.start().waitFor();
    }

    // " -p 2222 -i \"C:\path with space\id\" " などを想定して分割
    private static List<String> splitSshOptions(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String t = s.trim();
        if (t.isEmpty()) return out;

        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);

            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());

        return out;
    }

    // --- RDP起動（通常） ---
    private static Process launchMstsc(String host, int port, Session s) throws IOException {
        String target = host + ":" + port;

        List<String> cmd = new ArrayList<>();
        cmd.add(MSTSC_EXE);
        cmd.add("/v:" + target);

        // フルスクリーン
        if (s.fullscreen()) {
            cmd.add("/f");
        } else {
            int w = (s.width()  != null) ? s.width()  : 1600;
            int h = (s.height() != null) ? s.height() : 900;
            cmd.add("/w:" + w);
            cmd.add("/h:" + h);
        }

        if (s.multimon()) cmd.add("/multimon");
        if (s.span()) cmd.add("/span");

        return new ProcessBuilder(cmd).start();
    }

    // --- Usernameのみのとき：一時 .rdp で username を渡してパスワード入力を促す ---
    private static Path createTempRdpFile(String host, int port, String username, Session s) throws IOException {
        Path p = Files.createTempFile("rdp-launcher-", ".rdp");

        int w = (s.width()  != null) ? s.width()  : 1600;
        int h = (s.height() != null) ? s.height() : 900;

        StringBuilder sb = new StringBuilder();
        sb.append("full address:s:").append(host).append(":").append(port).append("\r\n");
        sb.append("username:s:").append(username).append("\r\n");
        sb.append("prompt for credentials:i:1").append("\r\n");
        sb.append("authentication level:i:2").append("\r\n");
        sb.append("enablecredsspsupport:i:1").append("\r\n");

        if (s.fullscreen()) {
            sb.append("screen mode id:i:2").append("\r\n");
        } else {
            sb.append("screen mode id:i:1").append("\r\n");
            sb.append("desktopwidth:i:").append(w).append("\r\n");
            sb.append("desktopheight:i:").append(h).append("\r\n");
        }

        if (s.multimon()) sb.append("use multimon:i:1").append("\r\n");
        if (s.span()) sb.append("span monitors:i:1").append("\r\n");

        Files.writeString(p, sb.toString(), Charset.forName("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
        return p;
    }

    private static Process launchMstscWithRdpFile(Path rdpFile) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(MSTSC_EXE);
        cmd.add(rdpFile.toAbsolutePath().toString());
        return new ProcessBuilder(cmd).start();
    }

    // --- ループバック疎通チェック ---
    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName(LOCAL_BIND))) {
            return s.getLocalPort();
        }
    }

    private static boolean waitLocalPortOpen(String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (canConnect(host, port, 300)) return true;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // --- 資格情報（cmdkey） ---
    private static String credTargetFor(String host) {
        return "TERMSRV/" + host;
    }

    // ★変更：cmdkey を Hidden 実行に変更（コンソールが出ない）
    private static void addTempCredential(String host, String username, String password)
            throws IOException, InterruptedException {

        String target = credTargetFor(host);

        int exit = runHiddenAndWait("cmdkey.exe", List.of(
                "/generic:" + target,
                "/user:" + username,
                "/pass:" + password
        ));
        if (exit != 0) throw new IOException("cmdkey add failed (exit=" + exit + ")");
    }

    // ★変更：cmdkey を Hidden 実行に変更（コンソールが出ない）
    private static void deleteTempCredential(String host) throws IOException, InterruptedException {
        if (host == null || host.isBlank()) return;
        String target = credTargetFor(host);

        // deleteは失敗しても致命じゃないので exit 無視でもOK（現状維持でwaitだけ）
        runHiddenAndWait("cmdkey.exe", List.of("/delete:" + target));
    }

    // 入力形式を壊さず、必要なら DOMAIN\\user に補完
    private static String buildUsernameForCmdkey(String rawUser, String rawDomain) {
        String user = rawUser == null ? "" : rawUser.trim();
        String dom  = rawDomain == null ? "" : rawDomain.trim();

        if (user.contains("\\") || user.contains("@")) return user;
        if (!dom.isEmpty()) return dom + "\\" + user;
        return user;
    }

    // --- 保存/読み込み（CSV） ---
    private void loadSessionsFromDisk() throws IOException {
        Files.createDirectories(APP_DIR);

        if (!Files.exists(SESSIONS_CSV)) {
            sessions.add(new Session(
                    "lab",
                    true, "rdp", "",
                    "192.168.100.4", 3389,
                    "", "",
                    false, null, null, false, false
            ));
            saveSessionsToDisk();
            return;
        }

        List<String> lines = Files.readAllLines(SESSIONS_CSV, Charset.forName("UTF-8"));
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (line.startsWith("#")) continue;
            if (line.toLowerCase().startsWith("name,")) continue;

            String[] parts = line.split(",", -1);
            if (parts.length < 4) continue;

            int i = 0;
            String name = parts[i++].trim();

            // 旧形式: name,sshAlias,rdpHost,rdpPort,...
            // 中間:  name,useBastion,sshAlias,rdpHost,rdpPort,...
            // 新形式: name,useBastion,sshAlias,sshOptions,rdpHost,rdpPort,...
            boolean hasUseBastionCol = false;
            if (parts.length > 1) {
                String p1 = parts[1].trim().toLowerCase(Locale.ROOT);
                hasUseBastionCol = "true".equals(p1) || "false".equals(p1);
            }

            boolean useBastion = true;
            String sshAlias = "";
            String sshOptions = "";

            if (hasUseBastionCol) {
                useBastion = Boolean.parseBoolean(parts[i++].trim());
                sshAlias = (parts.length > i) ? parts[i++].trim() : "";

                // 新形式は 13 列（ヘッダ含め）想定：sshOptions を読む
                if (parts.length >= 13) {
                    sshOptions = (parts.length > i) ? parts[i++].trim() : "";
                } else {
                    sshOptions = "";
                }
            } else {
                useBastion = true;
                sshAlias = (parts.length > i) ? parts[i++].trim() : "";
                sshOptions = "";
            }

            String rdpHost = (parts.length > i) ? parts[i++].trim() : "";

            int rdpPort;
            try {
                rdpPort = Integer.parseInt((parts.length > i) ? parts[i++].trim() : "3389");
            } catch (NumberFormatException e) {
                continue;
            }

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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- UI補助 ---
    private void appendLog(String s) {
        Platform.runLater(() -> logArea.appendText(s + System.lineSeparator()));
    }

    private void setStatus(String s) {
        Platform.runLater(() -> statusLabel.setText(s));
    }

    private void alert(String msg) {
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
            stopMstscIfNeeded();

            // ★変更：stop時の taskkill も Hidden 実行に変更（コンソールが出ない）
            if (sshPid > 0) {
                try {
                    runHiddenAndWait("taskkill.exe", List.of("/PID", String.valueOf(sshPid), "/T", "/F"));
                } catch (Exception ignored) {}
            }

            try { deleteTempCredential("localhost"); } catch (Exception ignored) {}
            try { deleteTempCredential("127.0.0.1"); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        exec.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }

    // --- Session record ---
    public record Session(
            String name,
            boolean useBastion,
            String sshAlias,
            String sshOptions,
            String rdpHost, int rdpPort,
            String username, String domain,
            boolean fullscreen, Integer width, Integer height,
            boolean multimon, boolean span
    ) {
        @Override public String toString() { return name; }
    }
}
