package app;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class Connection {

    public interface Ui {
        void log(String s);
        void status(String s);
        void alert(String s);
        void setInputsDisabled(boolean disabled);
        void setConnected(boolean connected);
        void runOnFx(Runnable r);
        void clearPassword();
    }

    private final String localBind;            // 例: "127.0.0.1"
    private final String loopbackHostForRdp;   // 例: "localhost" (互換用)
    private final String mstscExe;
    private final Path appDir;
    private final Path appKnownHosts;

    private final ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rdp-launcher-conn");
        t.setDaemon(true);
        return t;
    });

    private volatile int sshPid = -1;
    private volatile Process mstscProc = null;

    // 直近で登録した一時資格情報のキー一覧（改行区切り）
    private volatile String lastTempCredKey = null;

    public Connection(String localBind, String loopbackHostForRdp, String mstscExe, Path appDir, Path appKnownHosts) {
        this.localBind = localBind;
        this.loopbackHostForRdp = loopbackHostForRdp;
        this.mstscExe = mstscExe;
        this.appDir = appDir;
        this.appKnownHosts = appKnownHosts;
    }

    public void connect(Session s0, String rawUser, String rawDomain, String rawPass, Ui ui) {
        // UI入力が空なら、セッションに保存されている Username/Domain を使う
        String u = (rawUser == null) ? "" : rawUser.trim();
        String d = (rawDomain == null) ? "" : rawDomain.trim();

        if (u.isEmpty() && s0.username() != null && !s0.username().isBlank()) u = s0.username().trim();
        if (d.isEmpty() && s0.domain() != null && !s0.domain().isBlank()) d = s0.domain().trim();

        final String userForRdp = buildUsernameForCmdkey(u, d);
        final boolean hasUser = userForRdp != null && !userForRdp.isBlank();
        final boolean hasPass = rawPass != null && !rawPass.isEmpty();

        ui.log("[INFO] Login mode: " + (hasUser ? (hasPass ? "AUTO (user+pass)" : "PROMPT (user only)") : "DEFAULT (mstsc)"));
        ui.log("[INFO] userForRdp=" + (userForRdp == null ? "null" : "'" + userForRdp + "'") + " passLen=" + (rawPass == null ? "null" : rawPass.length()));
        ui.log("[INFO] hasUser=" + hasUser + " hasPass=" + hasPass);

        if (!hasUser && hasPass) {
            ui.alert("Password だけ入力されています。Username を入力してください。");
            return;
        }

        ui.setInputsDisabled(true);
        ui.setConnected(true);
        ui.status("Connecting...");
        ui.log("[INFO] Connect: " + s0.name()
                + " (Bastion=" + (s0.useBastion() ? s0.sshAlias() : "OFF")
                + ", RDP=" + s0.rdpHost() + ":" + s0.rdpPort() + ")");

        exec.submit(() -> {
            boolean tempCredUsed = false;
            Path tempRdpFile = null;

            String rdpHostToUse = null;
            int rdpPortToUse = -1;

            try {
                rdpHostToUse = s0.rdpHost();
                rdpPortToUse = s0.rdpPort();

                // --- SSH tunnel ---
                if (s0.useBastion()) {
                    Files.createDirectories(appDir);

                    int localPort = SshHelpers.findFreePort(localBind);
                    ui.log("[INFO] Using local port: " + localPort);

                    SshHelpers.SshStartResult ssh = SshHelpers.startSshTunnelSmart(
                            appDir,
                            appKnownHosts,
                            localBind,
                            localPort,
                            s0.sshAlias(),
                            s0.sshOptions(),
                            s0.rdpHost(),
                            s0.rdpPort()
                    );

                    sshPid = ssh.pid();
                    ui.log("[INFO] Bastion tunnel started. PID=" + sshPid);
                    ui.log("[INFO] SSH logs: " + ssh.outLog() + " / " + ssh.errLog());

                    if (!SshHelpers.waitLocalPortOpen(localBind, localPort, Duration.ofSeconds(120))) {
                        String tail = SshHelpers.tailTextFile(ssh.errLog(), 120);
                        throw new IllegalStateException("踏み台トンネルのローカルポートが開きません: "
                                + localBind + ":" + localPort + "\n" + tail);
                    }

                    // ★重要：ここで mstsc 接続先を 127.0.0.1 に固定して揺れを消す
                    // （localhost だと環境によって ::1 側に揺れて cmdkey と合わないことがある）
                    rdpHostToUse = localBind;   // 例: "127.0.0.1"
                    rdpPortToUse = localPort;
                }

                // --- Credential handling ---
                // ★重要：cmdkey は「hostのみ（ポート無し）」が効きやすい。候補を増やすほど外す。
                // 踏み台ON時は 127.0.0.1 を本命にして、互換で localhost も入れる（必要最小限）
                List<String> credKeys = new ArrayList<>();
                if (hasUser && hasPass) {
                    // 本命
                    credKeys.add(rdpHostToUse);

                    // 互換：踏み台ONなら loopbackHostForRdp(例: localhost) も入れる
                    if (s0.useBastion()
                            && loopbackHostForRdp != null
                            && !loopbackHostForRdp.isBlank()
                            && !loopbackHostForRdp.equalsIgnoreCase(rdpHostToUse)) {
                        credKeys.add(loopbackHostForRdp);
                    }

                    ui.log("[INFO] Cred keys (TERMSRV) = " + credKeys);

                    List<String> added = new ArrayList<>();
                    for (String k : credKeys) {
                        try {
                            CredentialManager.addTempCredential(k, userForRdp, rawPass);
                            added.add(k);
                            ui.log("[INFO] Temporary credentials set: TERMSRV/" + k);
                        } catch (Exception e) {
                            ui.log("[WARN] cmdkey failed for TERMSRV/" + k + ": " + e.getMessage());
                        }
                    }

                    if (!added.isEmpty()) {
                        tempCredUsed = true;
                        lastTempCredKey = String.join("\n", added);
                    } else {
                        ui.log("[WARN] No credentials were added (all cmdkey attempts failed).");
                    }
                }

                ui.status("RDP running");

                // --- mstsc launch ---
                //  - AUTO(user+pass) は /v: で起動（prompt設定に邪魔されない）
                //  - PROMPT(user only) は .rdp で username を入れてプロンプト表示
                if (hasUser && !hasPass) {
                    tempRdpFile = createTempRdpFile(rdpHostToUse, rdpPortToUse, userForRdp, s0);
                    mstscProc = new ProcessBuilder(mstscExe, tempRdpFile.toAbsolutePath().toString()).start();
                } else {
                    mstscProc = launchMstsc(mstscExe, rdpHostToUse, rdpPortToUse, s0);
                }
                ui.runOnFx(ui::clearPassword);

                int exitCode = mstscProc.waitFor();
                ui.log("[INFO] mstsc exited with code: " + exitCode);

            } catch (Exception ex) {
                ui.log("[ERROR] " + ex.getMessage());
                ui.status("Error");
                ui.runOnFx(() -> ui.alert(ex.getMessage()));
            } finally {
                mstscProc = null;

                if (tempRdpFile != null) {
                    try { Files.deleteIfExists(tempRdpFile); } catch (Exception ignored) {}
                }

                // 一時資格情報を確実に削除（登録した分だけ）
                if (tempCredUsed) {
                    String keys = lastTempCredKey;
                    if (keys != null && !keys.isBlank()) {
                        for (String k : keys.split("\\R")) {
                            String kk = k.trim();
                            if (kk.isEmpty()) continue;
                            try { CredentialManager.deleteTempCredential(kk); } catch (Exception ignored) {}
                        }
                        ui.log("[INFO] Temporary credentials removed.");
                    }
                }
                lastTempCredKey = null;

                try { stopSshIfNeeded(ui); } catch (Exception ignored) {}

                ui.runOnFx(() -> {
                    ui.setInputsDisabled(false);
                    ui.setConnected(false);
                });

                if (!"Error".equalsIgnoreCase(getSafeStatus(ui))) ui.status("Ready");
            }
        });
    }

    public void disconnect(Ui ui) {
        ui.log("[INFO] Disconnect requested");
        ui.status("Disconnecting...");
        ui.setInputsDisabled(true);

        exec.submit(() -> {
            try {
                stopMstscIfNeeded(ui);

                // 直近で登録した資格情報を確実に掃除
                String keys = lastTempCredKey;
                if (keys != null && !keys.isBlank()) {
                    for (String k : keys.split("\\R")) {
                        String kk = k.trim();
                        if (kk.isEmpty()) continue;
                        try {
                            CredentialManager.deleteTempCredential(kk);
                            ui.log("[INFO] Temporary credentials removed: TERMSRV/" + kk);
                        } catch (Exception ignored) {}
                    }
                    lastTempCredKey = null;
                }

                stopSshIfNeeded(ui);
            } catch (Exception ex) {
                ui.log("[ERROR] " + ex.getMessage());
            } finally {
                ui.runOnFx(() -> {
                    ui.setInputsDisabled(false);
                    ui.setConnected(false);
                });
                ui.status("Ready");
            }
        });
    }

    public void shutdown() {
        try {
            Process p = mstscProc;
            if (p != null) p.destroyForcibly();
        } catch (Exception ignored) {}
        exec.shutdownNow();
    }

    // ------------------ helpers ------------------

    private void stopSshIfNeeded(Ui ui) throws IOException, InterruptedException {
        int pid = sshPid;
        if (pid <= 0) return;
        sshPid = -1;
        SshHelpers.stopSshIfNeeded(pid);
        ui.log("[INFO] Bastion tunnel stop requested. PID=" + pid);
    }

    private void stopMstscIfNeeded(Ui ui) {
        Process p = mstscProc;
        if (p == null) return;
        try {
            ui.log("[INFO] Closing mstsc...");
            if (p.isAlive()) {
                long pid = -1;
                try { pid = p.pid(); } catch (Throwable ignored) {}

                if (pid > 0) {
                    HiddenExec.runHiddenAndWait(
                            "taskkill.exe",
                            List.of("/PID", String.valueOf(pid), "/T", "/F")
                    );
                } else {
                    p.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
        } finally {
            mstscProc = null;
            ui.log("[INFO] Disconnect done.");
        }
    }

    private static Process launchMstsc(String mstscExe, String host, int port, Session s) throws IOException {
        String target = host + ":" + port;

        List<String> cmd = new ArrayList<>();
        cmd.add(mstscExe);
        cmd.add("/v:" + target);

        if (s.fullscreen()) {
            cmd.add("/f");
        } else {
            int w = (s.width() != null) ? s.width() : 1600;
            int h = (s.height() != null) ? s.height() : 900;
            cmd.add("/w:" + w);
            cmd.add("/h:" + h);
        }
        if (s.multimon()) cmd.add("/multimon");
        if (s.span()) cmd.add("/span");

        return new ProcessBuilder(cmd).start();
    }

    private static Path createTempRdpFile(String host, int port, String username, Session s) throws IOException {
        Path p = Files.createTempFile("rdp-launcher-", ".rdp");

        int w = (s.width()  != null) ? s.width()  : 1600;
        int h = (s.height() != null) ? s.height() : 900;

        StringBuilder sb = new StringBuilder();
        sb.append("full address:s:").append(host).append(":").append(port).append("\r\n");
        sb.append("username:s:").append(username).append("\r\n");

        // PROMPT(user only) 用: 資格情報入力を必ず出す
        sb.append("prompt for credentials:i:1").append("\r\n");

        sb.append("authentication level:i:2").append("\r\n");
        sb.append("enablecredsspsupport:i:1").append("\r\n");

        // ★詳細設定を反映
        if (s.fullscreen()) {
            sb.append("screen mode id:i:2").append("\r\n");
        } else {
            sb.append("screen mode id:i:1").append("\r\n");
            sb.append("desktopwidth:i:").append(w).append("\r\n");
            sb.append("desktopheight:i:").append(h).append("\r\n");
        }

        if (s.multimon()) sb.append("use multimon:i:1").append("\r\n");
        if (s.span())     sb.append("span monitors:i:1").append("\r\n");

        Files.writeString(p, sb.toString(), Charset.forName("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
        return p;
    }


    private static String buildUsernameForCmdkey(String rawUser, String rawDomain) {
        String user = rawUser == null ? "" : rawUser.trim();
        String dom  = rawDomain == null ? "" : rawDomain.trim();

        if (user.isEmpty()) return "";
        if (user.contains("\\") || user.contains("@")) return user;

        if (!dom.isEmpty()) return dom + "\\" + user;
        return user;
    }

    private static String getSafeStatus(Ui ui) {
        return "";
    }
}
