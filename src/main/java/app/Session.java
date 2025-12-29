package app;

public record Session(
        String name,
        boolean useBastion,
        String sshAlias,
        String sshOptions,
        String rdpHost,
        int rdpPort,
        String username,
        String domain,
        boolean fullscreen,
        Integer width,
        Integer height,
        boolean multimon,
        boolean span
) {
    @Override public String toString() { return name; }
}
