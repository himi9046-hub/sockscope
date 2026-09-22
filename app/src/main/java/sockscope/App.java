package sockscope;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class App extends Application {

    private static final PseudoClass OFFLINE = PseudoClass.getPseudoClass("offline");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Color DOWN = Color.web("#e8a33d");
    private static final Color UP = Color.web("#6cb8a4");

    private final ProcessTable table = new ProcessTable(60);
    private final ConnectionLog log = new ConnectionLog(500);
    private final HostNames names = new HostNames(10_000);
    private final ObservableList<ProcessTable.Row> rows = FXCollections.observableArrayList();
    private final ObservableList<ConnectionLog.Entry> conns = FXCollections.observableArrayList();
    private final TableView<ProcessTable.Row> procView = new TableView<>(rows);
    private final TableView<ConnectionLog.Entry> connView = new TableView<>(conns);
    private final CheckBox grouped = new CheckBox("Group by program");
    private final Canvas graph = new Canvas(240, 44);
    private final Label down = new Label("0 B/s");
    private final Label up = new Label("0 B/s");
    private final Label connTitle = new Label();
    private final Label status = new Label();
    private CollectorClient client;

    @Override
    public void start(Stage stage) {
        var socket = Path.of(Objects.requireNonNullElse(System.getenv("SOCKSCOPE_SOCKET"), "/run/sockscope.sock"));

        procView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        procView.setPlaceholder(new Label("No network activity yet"));
        procView.getColumns().add(column("Process", 220, ProcessTable.Row::comm, false));
        procView.getColumns().add(column("PID", 80, r -> r.count() > 1 ? r.count() + " procs" : String.valueOf(r.pid()), true));
        procView.getColumns().add(column("Down", 110, r -> Bytes.rate(r.down()), true, "down"));
        procView.getColumns().add(column("Up", 110, r -> Bytes.rate(r.up()), true, "up"));
        procView.getColumns().add(column("Total down", 110, r -> Bytes.format(r.totalDown()), true));
        procView.getColumns().add(column("Total up", 110, r -> Bytes.format(r.totalUp()), true));
        procView.getColumns().add(column("Cgroup", 90, r -> r.cgroup() == 0 ? "mixed" : String.valueOf(r.cgroup()), true));
        procView.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> refreshConnections());

        connView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        connView.setPlaceholder(new Label("No TCP connections seen yet"));
        connView.getColumns().add(column("Time", 80, e -> CLOCK.format(Instant.ofEpochSecond(e.time())), false));
        connView.getColumns().add(column("Process", 140, ConnectionLog.Entry::program, false));
        connView.getColumns().add(column("Dir", 50, e -> e.conn().dir(), false));
        connView.getColumns().add(column("Host", 200, e -> names.nameOf(e.conn().remote()), false));
        connView.getColumns().add(column("Address", 200, e -> e.conn().remote() + ":" + e.conn().rport(), false));
        connView.getColumns().add(column("State", 70, e -> e.conn().closed() ? "closed" : "open", false));
        connView.getColumns().add(column("Time open", 90, e -> e.conn().closed() ? millis(e.conn().ms()) : "", true));
        connView.getColumns().add(column("Down", 90, e -> e.conn().closed() ? Bytes.format(e.conn().rx()) : "", true, "down"));
        connView.getColumns().add(column("Up", 90, e -> e.conn().closed() ? Bytes.format(e.conn().tx()) : "", true, "up"));

        grouped.setSelected(true);
        grouped.selectedProperty().addListener((obs, old, on) -> refreshProcesses());

        connTitle.getStyleClass().add("section");
        var connPane = new VBox(connTitle, connView);
        VBox.setVgrow(connView, Priority.ALWAYS);
        var split = new SplitPane(procView, connPane);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.58);

        var root = new BorderPane(split);
        root.setTop(new VBox(header(), toolbar()));
        root.setBottom(status);
        status.getStyleClass().add("status");

        var scene = new Scene(root, 1000, 680);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("app.css")).toExternalForm());
        stage.setTitle("sockscope");
        stage.setScene(scene);
        stage.show();

        refreshConnections();
        setConnected(false, socket);
        client = new CollectorClient(socket, Duration.ofSeconds(2),
                sample -> Platform.runLater(() -> show(sample)),
                connected -> Platform.runLater(() -> setConnected(connected, socket)));
        client.start();
    }

    private HBox header() {
        var title = new Label("sockscope");
        title.getStyleClass().add("title");
        var subtitle = new Label("network traffic per process");
        subtitle.getStyleClass().add("dim");

        down.getStyleClass().addAll("big", "down");
        up.getStyleClass().addAll("big", "up");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var box = new HBox(28, new VBox(title, subtitle), spacer, graph, stat("DOWN", down), stat("UP", up));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("header");
        return box;
    }

    private HBox toolbar() {
        var box = new HBox(grouped);
        box.getStyleClass().add("toolbar");
        return box;
    }

    private static VBox stat(String name, Label value) {
        var label = new Label(name);
        label.getStyleClass().add("dim");
        var box = new VBox(label, value);
        box.setMinWidth(110);
        return box;
    }

    private void show(Sample sample) {
        if (sample.dns() != null) {
            names.add(sample.dns());
            connView.refresh();
            return;
        }
        if (sample.conn() != null) {
            log.add(sample.time(), sample.conn());
            refreshConnections();
            return;
        }
        table.update(sample);
        refreshProcesses();
        var now = table.now();
        down.setText(Bytes.format(now.down()) + "/s");
        up.setText(Bytes.format(now.up()) + "/s");
        drawGraph();
    }

    private void refreshProcesses() {
        var selected = procView.getSelectionModel().getSelectedItem();
        rows.setAll(grouped.isSelected() ? table.byProgram() : table.rows());
        if (selected != null) {
            rows.stream()
                    .filter(r -> grouped.isSelected() ? r.comm().equals(selected.comm()) : r.pid() == selected.pid())
                    .findFirst()
                    .ifPresent(procView.getSelectionModel()::select);
        }
    }

    private void refreshConnections() {
        var selected = procView.getSelectionModel().getSelectedItem();
        var program = selected == null ? null : selected.comm();
        conns.setAll(log.latest(program));
        connTitle.setText(program == null ? "Connections" : "Connections of " + program);
    }

    private void drawGraph() {
        var g = graph.getGraphicsContext2D();
        double w = graph.getWidth();
        double h = graph.getHeight();
        g.clearRect(0, 0, w, h);
        g.setStroke(Color.web("#262a30"));
        g.setLineWidth(1);
        g.strokeLine(0, h - 0.5, w, h - 0.5);

        var points = table.history();
        long peak = 16 * 1024;
        for (var p : points) {
            peak = Math.max(peak, Math.max(p.down(), p.up()));
        }
        line(g, points.stream().mapToLong(ProcessTable.Point::down).toArray(), peak, DOWN);
        line(g, points.stream().mapToLong(ProcessTable.Point::up).toArray(), peak, UP);
    }

    private void line(GraphicsContext g, long[] values, long peak, Color color) {
        if (values.length < 2) {
            return;
        }
        double w = graph.getWidth();
        double h = graph.getHeight() - 2;
        double step = w / (ProcessTable.HISTORY - 1);
        double x = w - step * (values.length - 1);
        g.setStroke(color);
        g.setLineWidth(1.5);
        g.beginPath();
        for (int i = 0; i < values.length; i++) {
            double y = h - (double) values[i] / peak * (h - 1);
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
            x += step;
        }
        g.stroke();
    }

    private static String millis(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private void setConnected(boolean connected, Path socket) {
        status.setText(connected ? "Connected to " + socket : "Waiting for the collector at " + socket);
        status.pseudoClassStateChanged(OFFLINE, !connected);
    }

    private static <T> TableColumn<T, String> column(String name, double width, Function<T, String> value,
            boolean numeric, String... classes) {
        var col = new TableColumn<T, String>(name);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(value.apply(c.getValue())));
        if (numeric) {
            col.getStyleClass().add("numeric");
        }
        col.getStyleClass().addAll(classes);
        return col;
    }

    @Override
    public void stop() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
