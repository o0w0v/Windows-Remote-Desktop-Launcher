package app;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final String localBind;
    private final String loopbackHostForRdp;
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
    private volatile String lastTempCredKey = null;

    public Connection(String localBind, String loopbackHostForRdp, String mstscExe, Path appDir, Path appKnownHosts) {
        this.localBind = localBind;
        this.loopbackHostForRdp = loopbackHostForRdp;
        this.mstscExe = mstscExe;
        this.appDir = appDir;
        this.appKnownHosts = appKnownHosts;
    }

    public void connect(Session s0, String rawUser, String rawDomain, String rawPass, Ui ui) {
        String u = rawUser == null ? "" : rawUser.trim();
        String d = rawDomain == null ? "" : rawDomain.trim();

        if (u.isEmpty() && s0.username() != null && !s0.username().isBlank()) u = s0.username().trim();
        if (d.isEmpty() && s0.domain() != null && !s0.domain().isBlank()) d = s0.domain().trim();

        final String userForRdp = buildUsernameForCmdkey(u, d);
        final boolean hasUser = userForRdp != null && !userForRdp.isBlank();
        final boolean hasPass = rawPass != null && !rawPass.isEmpty();

        ui.log("[INFO] Login mode: " + (hasUser ? (hasPass ? "AUTO (user+pass)" : "PROMPT (user only)") : "DEFAULT (mstsc)"));
        ui.log("[INFO] userForRdp=" + (userForRdp == null ? "null" : "'" + userForRdp + "'") + " passLen=" + (rawPass == null ? "null" : rawPass.length()));
        ui.log("[INFO] hasUser=" + hasUser + " hasPass=" + hasPass);

        if (!hasUser && hasPass) {
            ui.alert("Password only is not supported. Enter a username too.");
            return;
        }

        ui.setInputsDisabled(true);
        ui.setConnected(true);
        ui.status("Connecting...");
        ui.log("[INFO] Connect: " + s0.name()
                + " (Bastion=" + (s0.useBastion() ? s0.sshAlias() : "OFF")
                + ", RDG=" + (s0.useRdGateway() ? s0.rdGatewayHost() : "OFF")
                + ", RDP=" + s0.rdpHost() + ":" + s0.rdpPort() + ")");

        exec.submit(() -> {
            boolean tempCredUsed = false;
            Path tempRdpFile = null;

            String rdpHostToUse = s0.rdpHost();
            int rdpPortToUse = s0.rdpPort();

            try {
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
                            s0.jumpHosts(),
                            s0.sshOptions(),
                            s0.rdpHost(),
                            s0.rdpPort()
                    );

                    sshPid = ssh.pid();
                    ui.log("[INFO] Bastion tunnel started. PID=" + sshPid);
                    ui.log("[INFO] SSH logs: " + ssh.outLog() + " / " + ssh.errLog());

                    if (!SshHelpers.waitLocalPortOpen(localBind, localPort, Duration.ofSeconds(120))) {
                        String tail = SshHelpers.tailTextFile(ssh.errLog(), 120);
                        throw new IllegalStateException("SSH tunnel did not open a local port: "
                                + localBind + ":" + localPort + "\n" + tail);
                    }

                    rdpHostToUse = localBind;
                    rdpPortToUse = localPort;
                }

                List<String> credKeys = new ArrayList<>();
                if (hasUser && hasPass) {
                    credKeys.add(rdpHostToUse);

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

                if (hasUser && !hasPass) {
                    tempRdpFile = createTempRdpFile(rdpHostToUse, rdpPortToUse, userForRdp, s0, true);
                    mstscProc = new ProcessBuilder(mstscExe, tempRdpFile.toAbsolutePath().toString()).start();
                } else if (s0.useRdGateway() || hasSelectedMonitors(s0)) {
                    tempRdpFile = createTempRdpFile(rdpHostToUse, rdpPortToUse, hasUser ? userForRdp : null, s0, false);
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
                    try {
                        Files.deleteIfExists(tempRdpFile);
                    } catch (Exception ignored) {
                    }
                }

                if (tempCredUsed) {
                    String keys = lastTempCredKey;
                    if (keys != null && !keys.isBlank()) {
                        for (String k : keys.split("\\R")) {
                            String kk = k.trim();
                            if (kk.isEmpty()) continue;
                            try {
                                CredentialManager.deleteTempCredential(kk);
                            } catch (Exception ignored) {
                            }
                        }
                        ui.log("[INFO] Temporary credentials removed.");
                    }
                }
                lastTempCredKey = null;

                try {
                    stopSshIfNeeded(ui);
                } catch (Exception ignored) {
                }

                ui.runOnFx(() -> {
                    ui.setInputsDisabled(false);
                    ui.setConnected(false);
                });

                ui.status("Ready");
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

                String keys = lastTempCredKey;
                if (keys != null && !keys.isBlank()) {
                    for (String k : keys.split("\\R")) {
                        String kk = k.trim();
                        if (kk.isEmpty()) continue;
                        try {
                            CredentialManager.deleteTempCredential(kk);
                            ui.log("[INFO] Temporary credentials removed: TERMSRV/" + kk);
                        } catch (Exception ignored) {
                        }
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
        } catch (Exception ignored) {
        }
        exec.shutdownNow();
    }

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
                try {
                    pid = p.pid();
                } catch (Throwable ignored) {
                }

                if (pid > 0) {
                    HiddenExec.runHiddenAndWait("taskkill.exe", List.of("/PID", String.valueOf(pid), "/T", "/F"));
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
            int w = s.width() != null ? s.width() : 1600;
            int h = s.height() != null ? s.height() : 900;
            cmd.add("/w:" + w);
            cmd.add("/h:" + h);
        }
        if (s.multimon()) cmd.add("/multimon");
        if (s.span()) cmd.add("/span");

        return new ProcessBuilder(cmd).start();
    }

    private static Path createTempRdpFile(String host, int port, String username, Session s, boolean promptForCredentials) throws IOException {
        Path p = Files.createTempFile("rdp-launcher-", ".rdp");

        int w = s.width() != null ? s.width() : 1600;
        int h = s.height() != null ? s.height() : 900;

        StringBuilder sb = new StringBuilder();
        sb.append("full address:s:").append(host).append(":").append(port).append("\r\n");
        if (username != null && !username.isBlank()) {
            sb.append("username:s:").append(username).append("\r\n");
        }
        sb.append("prompt for credentials:i:").append(promptForCredentials ? 1 : 0).append("\r\n");
        sb.append("authentication level:i:2").append("\r\n");
        sb.append("enablecredsspsupport:i:1").append("\r\n");
        sb.append("redirectclipboard:i:1").append("\r\n");

        if (s.useRdGateway()) {
            sb.append("gatewayprofileusagemethod:i:1").append("\r\n");
            sb.append("gatewayusagemethod:i:1").append("\r\n");
            sb.append("gatewayhostname:s:").append(s.rdGatewayHost()).append("\r\n");
            sb.append("gatewaycredentialssource:i:").append(s.rdGatewayUseCurrentUser() ? 2 : 4).append("\r\n");
            sb.append("promptcredentialonce:i:").append(s.rdGatewayShareCreds() ? 1 : 0).append("\r\n");
        } else {
            sb.append("gatewayusagemethod:i:0").append("\r\n");
        }

        if (hasSelectedMonitors(s)) {
            sb.append("screen mode id:i:2").append("\r\n");
            sb.append("use multimon:i:1").append("\r\n");
            sb.append("selectedmonitors:s:").append(s.selectedMonitors()).append("\r\n");
        } else if (s.fullscreen()) {
            sb.append("screen mode id:i:2").append("\r\n");
        } else {
            sb.append("screen mode id:i:1").append("\r\n");
            sb.append("desktopwidth:i:").append(w).append("\r\n");
            sb.append("desktopheight:i:").append(h).append("\r\n");
        }

        if (!hasSelectedMonitors(s) && s.multimon()) sb.append("use multimon:i:1").append("\r\n");
        if (!hasSelectedMonitors(s) && s.span()) sb.append("span monitors:i:1").append("\r\n");

        Files.writeString(p, sb.toString(), Charset.forName("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);
        return p;
    }

    private static String buildUsernameForCmdkey(String rawUser, String rawDomain) {
        String user = rawUser == null ? "" : rawUser.trim();
        String dom = rawDomain == null ? "" : rawDomain.trim();

        if (user.isEmpty()) return "";
        if (user.contains("\\") || user.contains("@")) return user;
        if (!dom.isEmpty()) return dom + "\\" + user;
        return user;
    }

    private static boolean hasSelectedMonitors(Session s) {
        return s.selectedMonitors() != null && !s.selectedMonitors().isBlank();
    }
}
