package sockscope;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class App extends Application {

    private static final PseudoClass OFFLINE = PseudoClass.getPseudoClass("offline");

    private final ProcessTable table = new ProcessTable(60);
    private final ObservableList<ProcessTable.Row> rows = FXCollections.observableArrayList();
    private final TableView<ProcessTable.Row> view = new TableView<>(rows);
    private final Label down = new Label("0 B/s");
    private final Label up = new Label("0 B/s");
    private final Label status = new Label();
    private CollectorClient client;

    @Override
    public void start(Stage stage) {
        var socket = Path.of(Objects.requireNonNullElse(System.getenv("SOCKSCOPE_SOCKET"), "/run/sockscope.sock"));

        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        view.setPlaceholder(new Label("No network activity yet"));
        view.getColumns().add(column("Process", 220, ProcessTable.Row::comm, false));
        view.getColumns().add(column("PID", 80, r -> String.valueOf(r.pid()), true));
        view.getColumns().add(column("Down", 110, r -> Bytes.rate(r.down()), true, "down"));
        view.getColumns().add(column("Up", 110, r -> Bytes.rate(r.up()), true, "up"));
        view.getColumns().add(column("Total down", 110, r -> Bytes.format(r.totalDown()), true));
        view.getColumns().add(column("Total up", 110, r -> Bytes.format(r.totalUp()), true));
        view.getColumns().add(column("Cgroup", 90, r -> String.valueOf(r.cgroup()), true));

        var root = new BorderPane(view);
        root.setTop(header());
        root.setBottom(status);
        status.getStyleClass().add("status");

        var scene = new Scene(root, 960, 600);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("app.css")).toExternalForm());
        stage.setTitle("sockscope");
        stage.setScene(scene);
        stage.show();

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
        var box = new HBox(36, new VBox(title, subtitle), spacer, stat("DOWN", down), stat("UP", up));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("header");
        return box;
    }

    private static VBox stat(String name, Label value) {
        var label = new Label(name);
        label.getStyleClass().add("dim");
        return new VBox(label, value);
    }

    private void show(Sample sample) {
        var selected = view.getSelectionModel().getSelectedItem();
        table.update(sample);
        rows.setAll(table.rows());
        if (selected != null) {
            rows.stream().filter(r -> r.pid() == selected.pid()).findFirst().ifPresent(view.getSelectionModel()::select);
        }
        down.setText(Bytes.format(table.down()) + "/s");
        up.setText(Bytes.format(table.up()) + "/s");
    }

    private void setConnected(boolean connected, Path socket) {
        status.setText(connected ? "Connected to " + socket : "Waiting for the collector at " + socket);
        status.pseudoClassStateChanged(OFFLINE, !connected);
    }

    private static TableColumn<ProcessTable.Row, String> column(String name, double width,
            Function<ProcessTable.Row, String> value, boolean numeric, String... classes) {
        var col = new TableColumn<ProcessTable.Row, String>(name);
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
