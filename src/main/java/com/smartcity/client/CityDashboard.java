package com.smartcity.client;

import com.smartcity.*;
import com.smartcity.Alert;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CityDashboard - Main client application for Smart City monitoring and control
 * Provides a JavaFX-based GUI for interacting with the Smart City gRPC services
 */
public class CityDashboard extends Application {
    private static final Logger logger = LogManager.getLogger(CityDashboard.class);
    private static final String REGISTRY_ADDRESS = "localhost:50050";

    // =========== Data models ===========
    
    // Traffic data model
    public static class TrafficEntry {
        private final StringProperty intersectionId = new SimpleStringProperty();
        private final StringProperty lightColor = new SimpleStringProperty();
        private final IntegerProperty waitingVehicles = new SimpleIntegerProperty();

        public TrafficEntry(String intersectionId, String lightColor, int waitingVehicles) {
            this.intersectionId.set(intersectionId);
            this.lightColor.set(lightColor);
            this.waitingVehicles.set(waitingVehicles);
        }

        // Getters
        public String getIntersectionId() { return intersectionId.get(); }
        public String getLightColor() { return lightColor.get(); }
        public int getWaitingVehicles() { return waitingVehicles.get(); }

        // Properties
        public StringProperty intersectionIdProperty() { return intersectionId; }
        public StringProperty lightColorProperty() { return lightColor; }
        public IntegerProperty waitingVehiclesProperty() { return waitingVehicles; }

        // Setters
        public void setLightColor(String color) { lightColor.set(color); }
        public void setWaitingVehicles(int count) { waitingVehicles.set(count); }
    }

    // =========== Service connection states ===========
    private final BooleanProperty registryConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty trafficConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty binConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty noiseConnected = new SimpleBooleanProperty(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    // =========== gRPC channels and stubs ===========
    private ManagedChannel registryChannel;
    private ManagedChannel trafficChannel;
    private ManagedChannel binChannel;
    private ManagedChannel noiseChannel;
    
    private RegistryGrpc.RegistryStub registryStub;
    private TrafficGrpc.TrafficStub trafficStub;
    private BinGrpc.BinStub binStub;
    private NoiseGrpc.NoiseStub noiseStub;

    // =========== UI Components ===========
    // Traffic tab
    private TableView<TrafficEntry> trafficTable;
    private ComboBox<String> intersectionSelector;
    private ComboBox<String> lightColorSelector;
    private Label totalVehiclesLabel;
    
    // Bin tab
    private ComboBox<String> zoneSelector;
    private TextArea binStatusArea;
    
    // Noise tab
    private ComboBox<String> noiseZoneSelector;
    private Spinner<Double> dayLimitSpinner;
    private Spinner<Double> nightLimitSpinner;
    private TextArea noiseStatusArea;
    
    // System tab
    private ListView<String> servicesList;
    private TextArea systemLogArea;
    
    // Status bar
    private Label statusLabel;
    private Label registryStatusLabel;
    private Label trafficStatusLabel;
    private Label binStatusLabel;
    private Label noiseStatusLabel;

    // =========== Data collections ===========
    private final ObservableList<TrafficEntry> trafficData = FXCollections.observableArrayList();
    private final Map<String, TrafficEntry> intersectionMap = new HashMap<>();
    private final IntegerProperty totalVehicles = new SimpleIntegerProperty(0);

    // =========== Background tasks and stream observers ===========
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
    private StreamObserver<NoiseData> noiseStreamObserver;
    private Future<?> trafficSummaryTask;
    private Future<?> noiseMonitoringTask;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Smart City Dashboard");
        
        // Build the UI
        BorderPane root = new BorderPane();
        root.setCenter(createMainContent());
        root.setBottom(createStatusBar());
        root.setPadding(new Insets(10));
        
        Scene scene = new Scene(root, 1024, 768);
        primaryStage.setScene(scene);
        
        // Set up listeners for service connection states
        setupConnectionListeners();
        
        // Connect to services
        connectToServices().thenRun(() -> {
            if (registryConnected.get()) {
                startMonitoringServices();
            }
        });
        
        primaryStage.show();
        
        // Add window close handler
        primaryStage.setOnCloseRequest(e -> stop());
    }

    // =========== UI Creation Methods ===========
    
    private TabPane createMainContent() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        tabPane.getTabs().addAll(
            createTrafficTab(),
            createBinTab(),
            createNoiseTab(),
            createSystemTab()
        );
        
        return tabPane;
    }
    
    private Tab createTrafficTab() {
        Tab tab = new Tab("Traffic Management");
        
        // Traffic table for displaying intersections
        trafficTable = new TableView<>();
        setupTrafficTable();
        VBox.setVgrow(trafficTable, Priority.ALWAYS);
        
        // Controls for changing traffic lights
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(10));
        
        intersectionSelector = new ComboBox<>();
        
        lightColorSelector = new ComboBox<>();
        lightColorSelector.getItems().addAll("RED", "YELLOW", "GREEN");
        
        Button setLightButton = new Button("Set Light");
        setLightButton.setOnAction(e -> {
            String intersection = intersectionSelector.getValue();
            String color = lightColorSelector.getValue();
            if (intersection != null && color != null) {
                setTrafficLight(intersection, color);
            } else {
                logMessage("Please select both intersection and light color");
            }
        });
        
        totalVehiclesLabel = new Label("Total Vehicles: 0");
        totalVehiclesLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        
        // Bind total vehicles property to label
        totalVehicles.addListener((obs, oldVal, newVal) -> 
            Platform.runLater(() -> totalVehiclesLabel.setText("Total Vehicles: " + newVal))
        );
        
        controls.getChildren().addAll(
            new Label("Intersection:"),
            intersectionSelector,
            new Label("Light Color:"),
            lightColorSelector,
            setLightButton,
            new Separator(),
            totalVehiclesLabel
        );
        
        // Layout
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(new VBox(trafficTable), controls);
        splitPane.setDividerPositions(0.7);
        
        tab.setContent(splitPane);
        return tab;
    }
    
    private void setupTrafficTable() {
        TableColumn<TrafficEntry, String> idCol = new TableColumn<>("Intersection");
        idCol.setCellValueFactory(new PropertyValueFactory<>("intersectionId"));
        
        TableColumn<TrafficEntry, String> colorCol = new TableColumn<>("Light Color");
        colorCol.setCellValueFactory(new PropertyValueFactory<>("lightColor"));
        colorCol.setCellFactory(column -> new TableCell<TrafficEntry, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item.toUpperCase()) {
                        case "RED" -> setStyle("-fx-background-color: #ffcccc;");
                        case "GREEN" -> setStyle("-fx-background-color: #ccffcc;");
                        case "YELLOW" -> setStyle("-fx-background-color: #ffffcc;");
                        default -> setStyle("");
                    }
                }
            }
        });
        
        TableColumn<TrafficEntry, Number> vehiclesCol = new TableColumn<>("Waiting Vehicles");
        vehiclesCol.setCellValueFactory(new PropertyValueFactory<>("waitingVehicles"));
        
        trafficTable.getColumns().addAll(idCol, colorCol, vehiclesCol);
        trafficTable.setItems(trafficData);
    }
    
    private Tab createBinTab() {
        Tab tab = new Tab("Waste Management");
        
        // Bin status area
        binStatusArea = new TextArea();
        binStatusArea.setEditable(false);
        binStatusArea.setWrapText(true);
        VBox.setVgrow(binStatusArea, Priority.ALWAYS);
        
        // Bin controls
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(10));
        
        zoneSelector = new ComboBox<>();
        zoneSelector.getItems().addAll("Zone-A", "Zone-B", "Zone-C");
        
        Button getRouteButton = new Button("Get Collection Route");
        getRouteButton.setOnAction(e -> {
            String zone = zoneSelector.getValue();
            if (zone != null) {
                getCollectionRoute(zone);
            } else {
                logMessage("Please select a zone first");
            }
        });
        
        Button urgentCollectionsButton = new Button("Show Urgent Collections");
        urgentCollectionsButton.setOnAction(e -> getUrgentCollections());
        
        controls.getChildren().addAll(
            new Label("Select Zone:"),
            zoneSelector,
            getRouteButton,
            urgentCollectionsButton
        );
        
        // Layout
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(new VBox(binStatusArea), controls);
        splitPane.setDividerPositions(0.7);
        
        tab.setContent(splitPane);
        return tab;
    }
    
    private Tab createNoiseTab() {
        Tab tab = new Tab("Noise Monitoring");
        
        // Noise status area
        noiseStatusArea = new TextArea();
        noiseStatusArea.setEditable(false);
        noiseStatusArea.setWrapText(true);
        VBox.setVgrow(noiseStatusArea, Priority.ALWAYS);
        
        // Noise controls
        VBox controls = new VBox(10);
        controls.setPadding(new Insets(10));
        
        noiseZoneSelector = new ComboBox<>();
        noiseZoneSelector.getItems().addAll("Zone-1", "Zone-2", "Zone-3");
        
        dayLimitSpinner = new Spinner<>(40.0, 90.0, 70.0, 0.5);
        dayLimitSpinner.setEditable(true);
        
        nightLimitSpinner = new Spinner<>(30.0, 80.0, 55.0, 0.5);
        nightLimitSpinner.setEditable(true);
        
        Button setThresholdButton = new Button("Set Thresholds");
        setThresholdButton.setOnAction(e -> {
            String zone = noiseZoneSelector.getValue();
            if (zone != null) {
                setNoiseThresholds(
                    zone,
                    dayLimitSpinner.getValue(),
                    nightLimitSpinner.getValue()
                );
            } else {
                logMessage("Please select a zone first");
            }
        });
        
        controls.getChildren().addAll(
            new Label("Select Zone:"),
            noiseZoneSelector,
            new Label("Day Limit (dB):"),
            dayLimitSpinner,
            new Label("Night Limit (dB):"),
            nightLimitSpinner,
            setThresholdButton
        );
        
        // Layout
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(new VBox(noiseStatusArea), controls);
        splitPane.setDividerPositions(0.7);
        
        tab.setContent(splitPane);
        return tab;
    }
    
    private Tab createSystemTab() {
        Tab tab = new Tab("System Status");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        Label servicesLabel = new Label("Active Services");
        servicesLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        servicesList = new ListView<>();
        VBox.setVgrow(servicesList, Priority.ALWAYS);
        
        Button refreshButton = new Button("Refresh Services");
        refreshButton.setOnAction(e -> refreshServices());
        
        Label logLabel = new Label("System Log");
        logLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        systemLogArea = new TextArea();
        systemLogArea.setEditable(false);
        systemLogArea.setWrapText(true);
        VBox.setVgrow(systemLogArea, Priority.ALWAYS);
        
        content.getChildren().addAll(
            servicesLabel,
            servicesList,
            refreshButton,
            new Separator(),
            logLabel,
            systemLogArea
        );
        
        tab.setContent(content);
        return tab;
    }
    
    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-width: 1 0 0 0; -fx-border-color: #cccccc;");

        // Create service status labels
        registryStatusLabel = new Label("Registry Service: Disconnected");
        trafficStatusLabel = new Label("Traffic Service: Disconnected");
        binStatusLabel = new Label("Bin Service: Disconnected");
        noiseStatusLabel = new Label("Noise Service: Disconnected");

        // Set initial colors
        registryStatusLabel.setTextFill(Color.RED);
        trafficStatusLabel.setTextFill(Color.RED);
        binStatusLabel.setTextFill(Color.RED);
        noiseStatusLabel.setTextFill(Color.RED);

        // Add separators between status labels
        Separator sep1 = new Separator(Orientation.VERTICAL);
        Separator sep2 = new Separator(Orientation.VERTICAL);
        Separator sep3 = new Separator(Orientation.VERTICAL);

        // General status message area
        statusLabel = new Label("Initializing...");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        // Add all components to status bar
        statusBar.getChildren().addAll(
            statusLabel,
            new Separator(Orientation.VERTICAL),
            registryStatusLabel,
            sep1,
            trafficStatusLabel,
            sep2,
            binStatusLabel,
            sep3,
            noiseStatusLabel
        );

        return statusBar;
    }

    private void updateStatusLabel(Label label, String serviceName, boolean connected) {
        Platform.runLater(() -> {
            label.setText(serviceName + ": " + (connected ? "Connected" : "Disconnected"));
            label.setTextFill(connected ? Color.GREEN : Color.RED);
        });
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }
    
    private void logMessage(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        Platform.runLater(() -> {
            systemLogArea.appendText("[" + timestamp + "] " + message + "\n");
            updateStatus(message);
        });
        logger.info(message);
    }
    
    // Method to handle getting urgent bin collections
    private void getUrgentCollections() {
        if (binStub == null || !binConnected.get()) {
            logMessage("Not connected to Bin service - cannot check urgent collections");
            binStatusArea.setText("Error: Not connected to bin service");
            return;
        }

        binStatusArea.clear();
        binStatusArea.setText("Checking urgent collections...\n");
        
        binStub.getUrgentCollections(Empty.newBuilder().build(),
            new StreamObserver<BinAlert>() {
                private final StringBuilder alerts = new StringBuilder("Urgent Collections:\n");
                private int count = 0;

                @Override
                public void onNext(BinAlert alert) {
                    count++;
                    Platform.runLater(() -> {
                        alerts.append(String.format("%d. Bin %s: %d%% full%s\n",
                            count,
                            alert.getBinId(),
                            alert.getFillPercent(),
                            alert.getUrgentCollection() ? " (URGENT)" : ""));
                        
                        binStatusArea.setText(alerts.toString());
                        
                        if (alert.getUrgentCollection()) {
                            logMessage("URGENT: Bin " + alert.getBinId() + " requires immediate collection!");
                        }
                    });
                }

                @Override
                public void onError(Throwable t) {
                    Platform.runLater(() -> {
                        String error = "Error checking urgent collections: " + t.getMessage();
                        binStatusArea.appendText("\nError: " + error);
                        logMessage(error);
                    });
                }

                @Override
                public void onCompleted() {
                    Platform.runLater(() -> {
                        if (count == 0) {
                            binStatusArea.setText("No urgent collections needed at this time.");
                        }
                        logMessage("Completed urgent collections check. Found " + count + " bins needing attention.");
                    });
                }
            });
    }

    // Method to get the collection route for a specific zone
    private void getCollectionRoute(String zone) {
        if (binStub == null || !binConnected.get()) {
            logMessage("Not connected to Bin service - cannot get collection route");
            binStatusArea.setText("Error: Not connected to bin service");
            return;
        }

        if (zone == null || zone.isEmpty()) {
            logMessage("Please select a zone first");
            return;
        }

        Zone request = Zone.newBuilder()
            .setAreaId(zone)
            .build();
        
        binStub.getRoute(request, new StreamObserver<Route>() {
            @Override
            public void onNext(Route route) {
                Platform.runLater(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Collection Route for ").append(zone).append(":\n");
                    
                    if (route.getBinsList().isEmpty()) {
                        sb.append("No bins to collect in this zone.");
                    } else {
                        for (int i = 0; i < route.getBinsList().size(); i++) {
                            sb.append(i+1).append(". ").append(route.getBins(i)).append("\n");
                        }
                    }
                    
                    binStatusArea.setText(sb.toString());
                    logMessage("Retrieved collection route for zone " + zone);
                });
            }

            @Override
            public void onError(Throwable t) {
                logMessage("Error getting route for zone " + zone + ": " + t.getMessage());
                Platform.runLater(() -> 
                    binStatusArea.setText("Error getting collection route: " + t.getMessage()));
            }

            @Override
            public void onCompleted() {
                // Route retrieval completed
            }
        });
    }

    private void setTrafficLight(String intersection, String color) {
        if (trafficStub == null || !trafficConnected.get()) {
            logMessage("Not connected to Traffic service - cannot set light");
            return;
        }

        LightCommand command = LightCommand.newBuilder()
            .setIntersection(intersection)
            .setColor(color)
            .build();

        trafficStub.setLight(command, new StreamObserver<Response>() {
            @Override
            public void onNext(Response response) {
                Platform.runLater(() -> {
                    logMessage("Traffic light updated: " + response.getStatus());
                    
                    // Update the traffic entry if it exists
                    TrafficEntry entry = intersectionMap.get(intersection);
                    if (entry != null) {
                        entry.setLightColor(color);
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> 
                    logMessage("Error setting traffic light: " + t.getMessage()));
            }

            @Override
            public void onCompleted() {
                // Light update completed
            }
        });
    }

    private void setNoiseThresholds(String zone, double dayLimit, double nightLimit) {
        if (noiseChannel == null || zone == null) {
            updateStatusArea(noiseStatusArea, "Error: Not connected to noise service or invalid zone");
            return;
        }

        NoiseGrpc.NoiseBlockingStub stub = NoiseGrpc.newBlockingStub(noiseChannel);
        try {
            Response response = stub.setZoneThreshold(NoiseThreshold.newBuilder()
                .setZoneId(zone)
                .setDayLimit((float) dayLimit)
                .setNightLimit((float) nightLimit)
                .build());
            
            updateStatusArea(noiseStatusArea, "Thresholds updated: " + response.getStatus());
        } catch (Exception e) {
            logger.error("Error setting noise thresholds", e);
            updateStatusArea(noiseStatusArea, "Error: " + e.getMessage());
        }
    }

    private void updateStatusArea(TextArea area, String message) {
        Platform.runLater(() -> {
            area.setText(message + "\n" + area.getText());
            if (area.getText().length() > 5000) {
                area.setText(area.getText().substring(0, 5000));
            }
        });
    }

    private void startTrafficDataStream() {
        if (trafficStub == null || !trafficConnected.get()) {
            logMessage("Cannot start traffic monitoring - service not connected");
            return;
        }

        StreamObserver<TrafficData> observer = new StreamObserver<TrafficData>() {
            @Override
            public void onNext(TrafficData data) {
                Platform.runLater(() -> updateTrafficData(data));
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    logMessage("Error in traffic data stream: " + t.getMessage());
                    if (!shouldStop.get()) {
                        // Attempt to reconnect after a delay
                        executor.schedule(() -> {
                            if (!shouldStop.get()) {
                                logMessage("Attempting to reconnect to traffic stream...");
                                startTrafficDataStream();
                            }
                        }, 5, TimeUnit.SECONDS);
                    }
                });
            }

            @Override
            public void onCompleted() {
                logMessage("Traffic data stream completed");
            }
        };

        // Start the stream
        trafficStub.streamTraffic(Empty.newBuilder().build(), observer);
        logMessage("Started traffic data stream");
    }

    private void updateTrafficData(TrafficData data) {
        int vehicleCount = data.getVehicleCount();
        totalVehicles.set(totalVehicles.get() + vehicleCount);
        
        Platform.runLater(() -> {
            totalVehiclesLabel.setText("Total Vehicles: " + totalVehicles.get());
            
            // Only log significant changes to avoid spam
            if (vehicleCount > 20) {
                logMessage(String.format("High traffic volume: %d vehicles detected", vehicleCount));
            }
        });
    }

    private void setupConnectionListeners() {
        registryConnected.addListener((obs, oldVal, newVal) -> 
            updateStatusLabel(registryStatusLabel, "Registry Service", newVal));
        
        trafficConnected.addListener((obs, oldVal, newVal) -> 
            updateStatusLabel(trafficStatusLabel, "Traffic Service", newVal));
        
        binConnected.addListener((obs, oldVal, newVal) -> 
            updateStatusLabel(binStatusLabel, "Bin Service", newVal));
        
        noiseConnected.addListener((obs, oldVal, newVal) -> 
            updateStatusLabel(noiseStatusLabel, "Noise Service", newVal));
    }

    private CompletableFuture<Void> connectToServices() {
        if (!isConnecting.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            registryChannel = ManagedChannelBuilder.forTarget(REGISTRY_ADDRESS)
                .usePlaintext()
                .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY))
                .build();
            registryStub = RegistryGrpc.newStub(registryChannel);
            
            // Start service discovery
            logMessage("Connecting to Registry Service at " + REGISTRY_ADDRESS);
            startServiceDiscovery(future);
            registryConnected.set(true);
            
        } catch (Exception e) {
            logger.error("Failed to connect to Registry Service", e);
            logMessage("Error connecting to Registry Service: " + e.getMessage());
            future.completeExceptionally(e);
            isConnecting.set(false);
        }

        return future.whenComplete((v, t) -> isConnecting.set(false));
    }

    private void startServiceDiscovery(CompletableFuture<Void> future) {
        ServiceFilter filter = ServiceFilter.newBuilder().build();
        registryStub.discoverServices(filter, new StreamObserver<ServiceInfo>() {
            @Override
            public void onNext(ServiceInfo service) {
                Platform.runLater(() -> {
                    servicesList.getItems().add(
                        String.format("%s (%s)", service.getServiceType(), service.getAddress())
                    );
                    connectToService(service);
                });
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Service discovery error", t);
                logMessage("Error discovering services: " + t.getMessage());
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                logMessage("Service discovery completed");
                future.complete(null);
            }
        });
    }

    private void connectToService(ServiceInfo service) {
        String[] addressParts = service.getAddress().split(":");
        String host = addressParts[0];
        int port = addressParts.length > 1 ? Integer.parseInt(addressParts[1]) : 50051;
        
        switch (service.getServiceType().toLowerCase()) {
            case "traffic" -> {
                trafficChannel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY))
                    .build();
                trafficStub = TrafficGrpc.newStub(trafficChannel);
                trafficConnected.set(true);
                logMessage("Connected to Traffic service at " + service.getAddress());
            }
            case "bin" -> {
                binChannel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY))
                    .build();
                binStub = BinGrpc.newStub(binChannel);
                binConnected.set(true);
                logMessage("Connected to Bin service at " + service.getAddress());
            }
            case "noise" -> {
                noiseChannel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY))
                    .build();
                noiseStub = NoiseGrpc.newStub(noiseChannel);
                noiseConnected.set(true);
                logMessage("Connected to Noise service at " + service.getAddress());
            }
        }
    }

    private void startMonitoringServices() {
        if (trafficConnected.get()) {
            startTrafficDataStream();
        } else {
            logMessage("Traffic service not connected - monitoring disabled");
        }
        
        if (noiseConnected.get()) {
            startNoiseMonitoring();
        } else {
            logMessage("Noise service not connected - monitoring disabled");
        }
    }

    private void startNoiseMonitoring() {
        if (noiseStub == null || !noiseConnected.get()) {
            logMessage("Cannot start noise monitoring - service not connected");
            return;
        }

        // Create bidirectional stream for noise monitoring
        StreamObserver<Alert> alertObserver = new StreamObserver<Alert>() {
            @Override
            public void onNext(Alert alert) {
                Platform.runLater(() -> {
                    String prefix = alert.getIsCritical() ? "CRITICAL: " : "";
                    noiseStatusArea.appendText(prefix + alert.getMessage() + "\n");
                    logMessage("Noise alert: " + alert.getMessage());
                });
            }

            @Override
            public void onError(Throwable t) {
                logMessage("Error in noise monitoring: " + t.getMessage());
                // Try to reconnect after a delay
                if (!shouldStop.get()) {
                    executor.schedule(this::retryNoiseMonitoring, 5, TimeUnit.SECONDS);
                }
            }

            private void retryNoiseMonitoring() {
                if (!shouldStop.get()) {
                    logMessage("Attempting to reconnect to noise service...");
                    startNoiseMonitoring();
                }
            }

            @Override
            public void onCompleted() {
                logMessage("Noise monitoring stream completed");
            }
        };

        noiseStreamObserver = noiseStub.monitorNoise(alertObserver);
        
        // Send simulated noise data periodically
        noiseMonitoringTask = executor.scheduleAtFixedRate(() -> {
            if (!shouldStop.get() && noiseConnected.get()) {
                try {
                    // Simulate random noise data from different zones
                    String[] zones = {"Zone-1", "Zone-2", "Zone-3"};
                    String sensorId = zones[(int)(Math.random() * zones.length)] + "_sensor1";
                    float decibels = 40 + (float)(Math.random() * 50); // 40-90 dB
                    
                    NoiseData data = NoiseData.newBuilder()
                        .setSensorId(sensorId)
                        .setDecibels(decibels)
                        .build();
                    
                    noiseStreamObserver.onNext(data);
                    
                    // Log significant noise events
                    if (decibels > 80) {
                        logMessage("High noise level detected at " + sensorId + ": " + decibels + " dB");
                    }
                } catch (Exception e) {
                    logger.error("Error sending noise data", e);
                    if (noiseStreamObserver != null) {
                        try {
                            noiseStreamObserver.onCompleted();
                        } catch (Exception ignored) {}
                    }
                    startNoiseMonitoring(); // Restart the stream
                }
            }
        }, 2, 10, TimeUnit.SECONDS);
        
        logMessage("Started noise monitoring");
    }

    private void refreshServices() {
        servicesList.getItems().clear();
        startServiceDiscovery(new CompletableFuture<>());
    }

    private synchronized void updateTrafficSummary(TrafficSummary summary) {
        totalVehicles.set(summary.getTotalVehicles());
        
        for (IntersectionStatus status : summary.getIntersectionsList()) {
            String id = status.getIntersectionId();
            TrafficEntry entry = intersectionMap.get(id);
            
            if (entry == null) {
                entry = new TrafficEntry(
                    id,
                    status.getCurrentColor(),
                    status.getWaitingVehicles()
                );
                intersectionMap.put(id, entry);
                trafficData.add(entry);
                
                // Update intersection selector
                Platform.runLater(() -> {
                    if (!intersectionSelector.getItems().contains(id)) {
                        intersectionSelector.getItems().add(id);
                    }
                });
            } else {
                entry.setLightColor(status.getCurrentColor());
                entry.setWaitingVehicles(status.getWaitingVehicles());
            }
        }
    }
    
    @Override
    public void stop() {
        shouldStop.set(true);
        
        // Cancel all scheduled tasks
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Clean up stream observers
        if (noiseStreamObserver != null) {
            try {
                noiseStreamObserver.onCompleted();
            } catch (Exception ignored) {}
        }
        
        // Close all channels
        shutdownChannel(registryChannel);
        shutdownChannel(trafficChannel);
        shutdownChannel(binChannel);
        shutdownChannel(noiseChannel);
        
        logger.info("Application shutdown completed");
    }

    private void shutdownChannel(ManagedChannel channel) {
        if (channel != null) {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
