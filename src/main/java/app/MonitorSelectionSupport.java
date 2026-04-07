package app;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.stage.Screen;

public final class MonitorSelectionSupport {

    public record MonitorOption(String rdpId, int labelNumber, Rectangle2D bounds) {}

    public record SelectionResult(List<MonitorOption> monitors, Set<String> selectedRdpIds, boolean mstscIdsReliable) {
        public String toCsv() {
            return selectedRdpIds.stream().collect(Collectors.joining(","));
        }
    }

    private static final Pattern MSTSC_MONITOR_LINE = Pattern.compile("(?m)^\\s*(\\d+)\\b");

    private MonitorSelectionSupport() {}

    public static SelectionResult showDialog(Window owner, String mstscExe, String currentCsv) {
        List<MonitorOption> monitors = discoverMonitors(mstscExe);
        Set<String> selectedIds = parseCsv(currentCsv);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Select Monitors");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Label help = new Label("Click displays to choose where the RDP session should open. Selected monitors use full-screen multi-monitor mode.");
        help.setWrapText(true);

        Pane preview = buildPreview(monitors, selectedIds);
        ScrollPane scroll = new ScrollPane(preview);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.setPrefViewportWidth(760);
        scroll.setPrefViewportHeight(320);

        Label hint = new Label("If monitor IDs cannot be read safely from mstsc, the app falls back to local display order.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #555;");

        VBox root = new VBox(12, help, scroll, hint);
        root.setPrefWidth(800);
        dialog.getDialogPane().setContent(root);

        dialog.getDialogPane().getScene().setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case ESCAPE -> dialog.setResult(ButtonType.CANCEL);
            }
        });

        return dialog.showAndWait()
                .filter(ButtonType.OK::equals)
                .map(bt -> new SelectionResult(monitors, new LinkedHashSet<>(selectedIds), monitorIdsReliable(monitors)))
                .orElse(null);
    }

    private static Pane buildPreview(List<MonitorOption> monitors, Set<String> selectedIds) {
        Pane pane = new Pane();
        pane.setPrefSize(760, 320);
        pane.setMinSize(760, 320);

        if (monitors.isEmpty()) {
            pane.getChildren().add(new Label("No local monitors found."));
            return pane;
        }

        double minX = monitors.stream().mapToDouble(m -> m.bounds().getMinX()).min().orElse(0);
        double minY = monitors.stream().mapToDouble(m -> m.bounds().getMinY()).min().orElse(0);
        double maxX = monitors.stream().mapToDouble(m -> m.bounds().getMaxX()).max().orElse(1);
        double maxY = monitors.stream().mapToDouble(m -> m.bounds().getMaxY()).max().orElse(1);

        double availableWidth = 700;
        double availableHeight = 240;
        double scale = Math.min(availableWidth / Math.max(1, (maxX - minX)), availableHeight / Math.max(1, (maxY - minY)));
        scale = Math.max(scale, 0.05);

        double contentWidth = (maxX - minX) * scale;
        double contentHeight = (maxY - minY) * scale;
        double offsetX = 30 + (availableWidth - contentWidth) / 2.0;
        double offsetY = 20 + (availableHeight - contentHeight) / 2.0;

        for (MonitorOption monitor : monitors) {
            double x = offsetX + (monitor.bounds().getMinX() - minX) * scale;
            double y = offsetY + (monitor.bounds().getMinY() - minY) * scale;
            double w = Math.max(80, monitor.bounds().getWidth() * scale);
            double h = Math.max(50, monitor.bounds().getHeight() * scale);

            Rectangle rect = new Rectangle(w, h);
            rect.setArcWidth(12);
            rect.setArcHeight(12);
            updateFill(rect, selectedIds.contains(monitor.rdpId()));

            Label label = new Label(String.valueOf(monitor.labelNumber()));
            label.setFont(Font.font(24));
            label.setTextFill(selectedIds.contains(monitor.rdpId()) ? Color.WHITE : Color.BLACK);

            StackPane tile = new StackPane(rect, label);
            tile.setLayoutX(x);
            tile.setLayoutY(y);
            tile.setOnMouseClicked(event -> {
                if (selectedIds.contains(monitor.rdpId())) {
                    selectedIds.remove(monitor.rdpId());
                } else {
                    selectedIds.add(monitor.rdpId());
                }
                boolean selected = selectedIds.contains(monitor.rdpId());
                updateFill(rect, selected);
                label.setTextFill(selected ? Color.WHITE : Color.BLACK);
            });

            pane.getChildren().add(tile);
        }

        return pane;
    }

    private static void updateFill(Rectangle rect, boolean selected) {
        rect.setFill(selected ? Color.web("#1477c9") : Color.web("#d8d8d8"));
        rect.setStroke(selected ? Color.web("#1477c9") : Color.web("#c1c1c1"));
        rect.setStrokeWidth(1.5);
    }

    private static List<MonitorOption> discoverMonitors(String mstscExe) {
        List<Screen> screens = new ArrayList<>(Screen.getScreens());
        screens.sort(Comparator.comparingDouble((Screen s) -> s.getBounds().getMinX())
                .thenComparingDouble(s -> s.getBounds().getMinY()));

        List<String> mstscIds = queryMstscMonitorIds(mstscExe, screens.size());
        List<MonitorOption> monitors = new ArrayList<>();
        for (int i = 0; i < screens.size(); i++) {
            Rectangle2D bounds = screens.get(i).getBounds();
            String rdpId = i < mstscIds.size() ? mstscIds.get(i) : String.valueOf(i);
            monitors.add(new MonitorOption(rdpId, i + 1, bounds));
        }
        return monitors;
    }

    private static List<String> queryMstscMonitorIds(String mstscExe, int expectedCount) {
        Process process = null;
        try {
            process = new ProcessBuilder(mstscExe, "/l")
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }

            String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
            Set<String> ids = new LinkedHashSet<>();
            Matcher matcher = MSTSC_MONITOR_LINE.matcher(output);
            while (matcher.find()) {
                ids.add(matcher.group(1));
                if (ids.size() >= expectedCount) {
                    break;
                }
            }
            return new ArrayList<>(ids);
        } catch (Exception ignored) {
            return List.of();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean monitorIdsReliable(List<MonitorOption> monitors) {
        if (monitors.isEmpty()) {
            return false;
        }
        for (int i = 0; i < monitors.size(); i++) {
            if (!String.valueOf(i).equals(monitors.get(i).rdpId())) {
                return true;
            }
        }
        return false;
    }
}
