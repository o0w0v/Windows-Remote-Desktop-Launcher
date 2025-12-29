package app;

import javax.swing.*;
import java.awt.*;

public final class AskPassMain {
    private AskPassMain() {}

    public static void main(String[] args) {
        // ssh.exe が渡す prompt をそのまま使う（なければ汎用）
        String prompt = (args != null && args.length > 0) ? String.join(" ", args) : "Password:";
        String pw = promptPassword(prompt);
        if (pw == null) {
            System.exit(1); // cancel
            return;
        }
        // ssh.exe は stdout から読む
        System.out.print(pw);
        System.out.flush();
        System.exit(0);
    }

    private static String promptPassword(String prompt) {
        try {
            // 最前面っぽくする
            JDialog dummy = new JDialog((Frame) null, true);
            dummy.setAlwaysOnTop(true);
            dummy.setLocationRelativeTo(null);

            JPasswordField pf = new JPasswordField(24);
            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.add(new JLabel(prompt), BorderLayout.NORTH);
            p.add(pf, BorderLayout.CENTER);

            int r = JOptionPane.showConfirmDialog(
                    dummy,
                    p,
                    "SSH Authentication",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
            );
            dummy.dispose();

            if (r != JOptionPane.OK_OPTION) return null;

            char[] chars = pf.getPassword();
            if (chars == null) return "";
            return new String(chars);
        } catch (Exception e) {
            return null;
        }
    }
}
