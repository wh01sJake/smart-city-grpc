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
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private static final String APP_TITLE = "Smart City Dashboard";
    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;

    // Service connection states
    private final BooleanProperty registryConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty trafficConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty binConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty noiseConnected = new SimpleBooleanProperty(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    // gRPC channels and stubs
    private ManagedChannel registryChannel;
    private ManagedChannel trafficChannel;
    private ManagedChannel binChannel;
    private ManagedChannel noiseChannel;

    private RegistryGrpc.RegistryStub registryStub;
    private TrafficGrpc.TrafficStub trafficStub;
    private TrafficGrpc.TrafficBlockingStub trafficBlockingStub;
    private BinGrpc.BinStub binStub;
    private NoiseGrpc.NoiseStub noiseStub;

    // Executor for background tasks
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private ScheduledFuture<?> noiseMonitoringTask;
    private ScheduledFuture<?> trafficSimulationTask;
    private ScheduledFuture<?> binSimulationTask;
    private StreamObserver<NoiseData> noiseStreamObserver;

    // Main UI components
    private TabPane mainTabPane;
    private Label statusLabel;
    private ProgressBar connectionProgress;

    // Dashboard tab components
    private VBox dashboardContent;
    private Label trafficStatusLabel;
    private Label binStatusLabel;
    private Label noiseStatusLabel;
    private LineChart<Number, Number> trafficChart;

    // Traffic tab components
    private TableView<TrafficEntry> trafficTable;
    private ComboBox<String> intersectionSelector;
    private ComboBox<String> lightColorSelector;
    private Label totalVehiclesLabel;

    // Bin tab components
    private ComboBox<String> zoneSelector;
    private TextArea binStatusArea;
    private ProgressBar[] binFillLevels;
    private Label[] binLabels;

    // Noise tab components
    private ComboBox<String> noiseZoneSelector;
    private Spinner<Double> dayLimitSpinner;
    private Spinner<Double> nightLimitSpinner;
    private TextArea noiseStatusArea;

    // System tab components
    private ListView<String> servicesList;
    private TextArea systemLogArea;

    // Data models
    public static class TrafficEntry {
        private final SimpleIntegerProperty vehicleCount = new SimpleIntegerProperty();
        private final SimpleStringProperty timestamp = new SimpleStringProperty();

        public TrafficEntry(int vehicleCount, String timestamp) {
            this.vehicleCount.set(vehicleCount);
            this.timestamp.set(timestamp);
        }

        public int getVehicleCount() { return vehicleCount.get(); }
        public String getTimestamp() { return timestamp.get(); }
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle(APP_TITLE);

        // Build the UI
        BorderPane root = new BorderPane();
        root.setCenter(createMainContent());
        root.setBottom(createStatusBar());
        root.setPadding(new Insets(10));

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        primaryStage.setScene(scene);

        // Set up listeners for service connection states
        setupConnectionListeners();

        // Start simulations immediately to show data
        logMessage("Starting simulations immediately");
        startSimulatedTrafficData();
        startSimulatedBinData();
        startSimulatedNoiseData();

        // Connect to services
        connectToServices().thenRun(() -> {
            if (registryConnected.get()) {
                // Register services in case they're not already registered
                registerServices();

                // Start monitoring services
                startMonitoringServices();
            }
        });

        primaryStage.show();

        // Add window close handler
        primaryStage.setOnCloseRequest(e -> stop());
    }

    @Override
    public void stop() {
        logger.info("Shutting down application");
        shouldStop.set(true);

        // Cancel scheduled tasks
        if (noiseMonitoringTask != null) {
            noiseMonitoringTask.cancel(true);
        }

        if (trafficSimulationTask != null) {
            trafficSimulationTask.cancel(true);
        }

        if (binSimulationTask != null) {
            binSimulationTask.cancel(true);
        }

        // Shutdown executor
        executor.shutdownNow();

        // Close gRPC channels
        closeChannels();

        logMessage("Application shutdown completed");
        Platform.exit();
    }

    private void closeChannels() {
        try {
            if (registryChannel != null) {
                registryChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
            if (trafficChannel != null) {
                trafficChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
            if (binChannel != null) {
                binChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
            if (noiseChannel != null) {
                noiseChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            logger.error("Error shutting down channels", e);
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Creates the main content area with tabs for different services
     */
    private TabPane createMainContent() {
        mainTabPane = new TabPane();
        mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Create tabs
        Tab dashboardTab = new Tab("Dashboard", createDashboardTab());
        Tab trafficTab = new Tab("Traffic Management", createTrafficTab());
        Tab binTab = new Tab("Waste Management", createBinTab());
        Tab noiseTab = new Tab("Noise Monitoring", createNoiseTab());
        Tab systemTab = new Tab("System", createSystemTab());

        mainTabPane.getTabs().addAll(dashboardTab, trafficTab, binTab, noiseTab, systemTab);
        return mainTabPane;
    }

    /**
     * Creates the dashboard overview tab
     */
    private VBox createDashboardTab() {
        dashboardContent = new VBox(10);
        dashboardContent.setPadding(new Insets(20));

        // Header
        Label headerLabel = new Label("Smart City Overview");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 24));

        // Service status indicators
        HBox statusBox = new HBox(20);
        statusBox.setAlignment(Pos.CENTER_LEFT);

        VBox trafficStatus = createStatusIndicator("Traffic Management", trafficConnected);
        trafficStatusLabel = (Label) trafficStatus.getChildren().get(1);

        VBox binStatus = createStatusIndicator("Waste Management", binConnected);
        binStatusLabel = (Label) binStatus.getChildren().get(1);

        VBox noiseStatus = createStatusIndicator("Noise Monitoring", noiseConnected);
        noiseStatusLabel = (Label) noiseStatus.getChildren().get(1);

        statusBox.getChildren().addAll(trafficStatus, binStatus, noiseStatus);

        // Traffic chart
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Time");
        yAxis.setLabel("Vehicle Count");

        trafficChart = new LineChart<>(xAxis, yAxis);
        trafficChart.setTitle("Live Traffic Data");
        trafficChart.setAnimated(false);
        trafficChart.setCreateSymbols(false);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("Vehicles");
        trafficChart.getData().add(series);

        // Add components to dashboard
        dashboardContent.getChildren().addAll(
            headerLabel,
            new Separator(),
            statusBox,
            new Label("Live Traffic Monitoring"),
            trafficChart
        );

        return dashboardContent;
    }

    /**
     * Creates a status indicator for a service
     */
    private VBox createStatusIndicator(String serviceName, BooleanProperty connectedProperty) {
        VBox box = new VBox(5);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(200);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 5;");

        Label nameLabel = new Label(serviceName);
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        Label statusLabel = new Label("Disconnected");
        statusLabel.setTextFill(Color.RED);

        // Update status label when connection state changes
        connectedProperty.addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                if (newVal) {
                    statusLabel.setText("Connected");
                    statusLabel.setTextFill(Color.GREEN);
                } else {
                    statusLabel.setText("Disconnected");
                    statusLabel.setTextFill(Color.RED);
                }
            });
        });

        box.getChildren().addAll(nameLabel, statusLabel);
        return box;
    }

    /**
     * Creates the traffic management tab
     */
    private VBox createTrafficTab() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        // Header
        Label headerLabel = new Label("Traffic Light Management");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 18));

        // Controls section
        TitledPane controlsPane = new TitledPane();
        controlsPane.setText("Traffic Light Controls");
        controlsPane.setCollapsible(false);

        GridPane controlsGrid = new GridPane();
        controlsGrid.setHgap(10);
        controlsGrid.setVgap(10);
        controlsGrid.setPadding(new Insets(10));

        // Intersection selector
        Label intersectionLabel = new Label("Intersection:");
        intersectionSelector = new ComboBox<>();
        intersectionSelector.getItems().addAll("Main-First", "Main-Second", "Broadway-Park", "Fifth-Central");
        intersectionSelector.setValue("Main-First");
        intersectionSelector.setMaxWidth(Double.MAX_VALUE);

        // Light color selector
        Label colorLabel = new Label("Light Color:");
        lightColorSelector = new ComboBox<>();
        lightColorSelector.getItems().addAll("RED", "YELLOW", "GREEN");
        lightColorSelector.setValue("RED");
        lightColorSelector.setMaxWidth(Double.MAX_VALUE);

        // Set light button
        Button setLightButton = new Button("Set Light");
        setLightButton.setMaxWidth(Double.MAX_VALUE);
        setLightButton.setOnAction(e -> setTrafficLight(
            intersectionSelector.getValue(),
            lightColorSelector.getValue()
        ));

        // Add controls to grid
        controlsGrid.add(intersectionLabel, 0, 0);
        controlsGrid.add(intersectionSelector, 1, 0);
        controlsGrid.add(colorLabel, 0, 1);
        controlsGrid.add(lightColorSelector, 1, 1);
        controlsGrid.add(setLightButton, 1, 2);

        // Set column constraints
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(30);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(70);
        controlsGrid.getColumnConstraints().addAll(col1, col2);

        controlsPane.setContent(controlsGrid);

        // Traffic data section
        TitledPane dataPane = new TitledPane();
        dataPane.setText("Traffic Data");
        dataPane.setCollapsible(false);

        VBox dataBox = new VBox(10);
        dataBox.setPadding(new Insets(10));

        // Total vehicles label
        totalVehiclesLabel = new Label("Total Vehicles: 0");
        totalVehiclesLabel.setFont(Font.font("System", FontWeight.BOLD, 14));

        // Traffic table
        trafficTable = new TableView<>();
        trafficTable.setPlaceholder(new Label("No traffic data available"));

        TableColumn<TrafficEntry, Number> vehicleCol = new TableColumn<>("Vehicle Count");
        vehicleCol.setCellValueFactory(cellData -> cellData.getValue().vehicleCount);
        vehicleCol.setPrefWidth(150);

        TableColumn<TrafficEntry, String> timeCol = new TableColumn<>("Timestamp");
        timeCol.setCellValueFactory(cellData -> cellData.getValue().timestamp);
        timeCol.setPrefWidth(250);

        trafficTable.getColumns().addAll(vehicleCol, timeCol);
        trafficTable.setMaxHeight(300);

        dataBox.getChildren().addAll(totalVehiclesLabel, trafficTable);
        dataPane.setContent(dataBox);

        // Add all components to tab
        content.getChildren().addAll(
            headerLabel,
            new Separator(),
            controlsPane,
            dataPane
        );

        return content;
    }

    /**
     * Creates the waste management tab
     */
    private VBox createBinTab() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        // Header
        Label headerLabel = new Label("Waste Bin Management");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 18));

        // Zone selection
        HBox zoneBox = new HBox(10);
        zoneBox.setAlignment(Pos.CENTER_LEFT);

        Label zoneLabel = new Label("Select Zone:");
        zoneSelector = new ComboBox<>();
        zoneSelector.getItems().addAll("Zone-1", "Zone-2", "Zone-3", "Zone-4");
        zoneSelector.setValue("Zone-1");

        Button getRouteButton = new Button("Get Collection Route");
        getRouteButton.setOnAction(e -> getCollectionRoute(zoneSelector.getValue()));

        zoneBox.getChildren().addAll(zoneLabel, zoneSelector, getRouteButton);

        // Bin status visualization
        TitledPane binStatusPane = new TitledPane();
        binStatusPane.setText("Bin Fill Levels");
        binStatusPane.setCollapsible(false);

        GridPane binGrid = new GridPane();
        binGrid.setHgap(15);
        binGrid.setVgap(15);
        binGrid.setPadding(new Insets(15));

        // Create bin fill level indicators
        binFillLevels = new ProgressBar[4];
        binLabels = new Label[4];

        for (int i = 0; i < 4; i++) {
            String binId = "Bin-" + (i + 1);

            Label binLabel = new Label(binId + ": 0%");
            ProgressBar fillLevel = new ProgressBar(0);
            fillLevel.setPrefWidth(200);

            binLabels[i] = binLabel;
            binFillLevels[i] = fillLevel;

            binGrid.add(binLabel, 0, i);
            binGrid.add(fillLevel, 1, i);
        }

        binStatusPane.setContent(binGrid);

        // Status area
        TitledPane alertsPane = new TitledPane();
        alertsPane.setText("Collection Alerts");
        alertsPane.setCollapsible(false);

        binStatusArea = new TextArea();
        binStatusArea.setEditable(false);
        binStatusArea.setPrefHeight(150);

        alertsPane.setContent(binStatusArea);

        // Add all components to tab
        content.getChildren().addAll(
            headerLabel,
            new Separator(),
            zoneBox,
            binStatusPane,
            alertsPane
        );

        return content;
    }

    /**
     * Creates the noise monitoring tab
     */
    private VBox createNoiseTab() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        // Header
        Label headerLabel = new Label("Noise Level Monitoring");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 18));

        // Threshold controls
        TitledPane thresholdPane = new TitledPane();
        thresholdPane.setText("Noise Thresholds");
        thresholdPane.setCollapsible(false);

        GridPane thresholdGrid = new GridPane();
        thresholdGrid.setHgap(10);
        thresholdGrid.setVgap(10);
        thresholdGrid.setPadding(new Insets(10));

        // Zone selector
        Label zoneLabel = new Label("Zone:");
        noiseZoneSelector = new ComboBox<>();
        noiseZoneSelector.getItems().addAll("Zone-1", "Zone-2", "Zone-3");
        noiseZoneSelector.setValue("Zone-1");

        // Day limit spinner
        Label dayLabel = new Label("Day Limit (dB):");
        SpinnerValueFactory.DoubleSpinnerValueFactory dayFactory =
            new SpinnerValueFactory.DoubleSpinnerValueFactory(40, 90, 70, 1);
        dayLimitSpinner = new Spinner<>(dayFactory);
        dayLimitSpinner.setEditable(true);
        dayLimitSpinner.setPrefWidth(100);

        // Night limit spinner
        Label nightLabel = new Label("Night Limit (dB):");
        SpinnerValueFactory.DoubleSpinnerValueFactory nightFactory =
            new SpinnerValueFactory.DoubleSpinnerValueFactory(30, 80, 55, 1);
        nightLimitSpinner = new Spinner<>(nightFactory);
        nightLimitSpinner.setEditable(true);
        nightLimitSpinner.setPrefWidth(100);

        // Set threshold button
        Button setThresholdButton = new Button("Set Thresholds");
        setThresholdButton.setOnAction(e -> setNoiseThresholds(
            noiseZoneSelector.getValue(),
            dayLimitSpinner.getValue(),
            nightLimitSpinner.getValue()
        ));

        // Add controls to grid
        thresholdGrid.add(zoneLabel, 0, 0);
        thresholdGrid.add(noiseZoneSelector, 1, 0);
        thresholdGrid.add(dayLabel, 0, 1);
        thresholdGrid.add(dayLimitSpinner, 1, 1);
        thresholdGrid.add(nightLabel, 0, 2);
        thresholdGrid.add(nightLimitSpinner, 1, 2);
        thresholdGrid.add(setThresholdButton, 1, 3);

        thresholdPane.setContent(thresholdGrid);

        // Noise alerts area
        TitledPane alertsPane = new TitledPane();
        alertsPane.setText("Noise Alerts");
        alertsPane.setCollapsible(false);

        noiseStatusArea = new TextArea();
        noiseStatusArea.setEditable(false);
        noiseStatusArea.setPrefHeight(200);

        alertsPane.setContent(noiseStatusArea);

        // Add all components to tab
        content.getChildren().addAll(
            headerLabel,
            new Separator(),
            thresholdPane,
            alertsPane
        );

        return content;
    }

    /**
     * Creates the system tab
     */
    private VBox createSystemTab() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        // Header
        Label headerLabel = new Label("System Information");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 18));

        // Services list
        TitledPane servicesPane = new TitledPane();
        servicesPane.setText("Discovered Services");
        servicesPane.setCollapsible(false);

        servicesList = new ListView<>();
        servicesList.setPrefHeight(150);

        servicesPane.setContent(servicesList);

        // System log
        TitledPane logPane = new TitledPane();
        logPane.setText("System Log");
        logPane.setCollapsible(false);

        systemLogArea = new TextArea();
        systemLogArea.setEditable(false);
        systemLogArea.setPrefHeight(300);

        logPane.setContent(systemLogArea);

        // Control buttons
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);

        Button refreshButton = new Button("Refresh Services");
        refreshButton.setOnAction(e -> refreshServices());

        Button clearLogButton = new Button("Clear Log");
        clearLogButton.setOnAction(e -> systemLogArea.clear());

        buttonBox.getChildren().addAll(refreshButton, clearLogButton);

        // Add all components to tab
        content.getChildren().addAll(
            headerLabel,
            new Separator(),
            buttonBox,
            servicesPane,
            logPane
        );

        return content;
    }

    /**
     * Creates the status bar at the bottom of the window
     */
    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");

        statusLabel = new Label("Initializing...");
        connectionProgress = new ProgressBar(0);
        connectionProgress.setPrefWidth(150);
        connectionProgress.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label versionLabel = new Label("Smart City v1.0");
        versionLabel.setStyle("-fx-text-fill: #888888;");

        statusBar.getChildren().addAll(statusLabel, connectionProgress, spacer, versionLabel);
        return statusBar;
    }

    /**
     * Set up listeners for service connection states
     */
    private void setupConnectionListeners() {
        registryConnected.addListener((obs, oldVal, newVal) -> {
            updateConnectionStatus("Registry", newVal);
        });

        trafficConnected.addListener((obs, oldVal, newVal) -> {
            updateConnectionStatus("Traffic", newVal);
        });

        binConnected.addListener((obs, oldVal, newVal) -> {
            updateConnectionStatus("Bin", newVal);
        });

        noiseConnected.addListener((obs, oldVal, newVal) -> {
            updateConnectionStatus("Noise", newVal);
        });
    }

    /**
     * Update the connection status in the UI
     */
    private void updateConnectionStatus(String service, boolean connected) {
        Platform.runLater(() -> {
            String status = connected ? "Connected" : "Disconnected";
            statusLabel.setText(service + " Service: " + status);

            // Update overall connection status
            boolean allConnected = registryConnected.get() &&
                                  trafficConnected.get() &&
                                  binConnected.get() &&
                                  noiseConnected.get();

            if (allConnected) {
                statusLabel.setText("All services connected");
            }
        });
    }

    /**
     * Connect to all services
     */
    private CompletableFuture<Void> connectToServices() {
        if (!isConnecting.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            connectionProgress.setVisible(true);
            statusLabel.setText("Connecting to services...");
        });

        try {
            // Connect to registry service with proper API key
            String apiKey = SecurityConfig.DEFAULT_API_KEY;
            logMessage("Using API key: " + (apiKey != null ? "[VALID KEY]" : "[MISSING KEY]"));

            registryChannel = ManagedChannelBuilder.forTarget(REGISTRY_ADDRESS)
                .usePlaintext()
                .intercept(new ClientAuthInterceptor(apiKey))
                .enableRetry()
                .maxRetryAttempts(3)
                .build();

            registryStub = RegistryGrpc.newStub(registryChannel);

            // Start service discovery
            logMessage("Connecting to Registry Service at " + REGISTRY_ADDRESS);
            startServiceDiscovery(future);
            registryConnected.set(true);

        } catch (Exception e) {
            logger.error("Error connecting to services", e);
            logMessage("Error: " + e.getMessage());
            future.completeExceptionally(e);
            isConnecting.set(false);

            Platform.runLater(() -> {
                connectionProgress.setVisible(false);
                statusLabel.setText("Connection failed: " + e.getMessage());
            });
        }

        return future;
    }

    /**
     * Start service discovery
     */
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
                isConnecting.set(false);

                Platform.runLater(() -> {
                    connectionProgress.setVisible(false);
                    statusLabel.setText("Service discovery failed");
                });
            }

            @Override
            public void onCompleted() {
                logMessage("Service discovery completed");
                future.complete(null);
                isConnecting.set(false);

                Platform.runLater(() -> {
                    connectionProgress.setVisible(false);
                    statusLabel.setText("Service discovery completed");
                });
            }
        });
    }

    /**
     * Connect to a discovered service
     */
    private void connectToService(ServiceInfo service) {
        // Parse host and port from address
        String[] parts = service.getAddress().split(":");
        if (parts.length != 2) {
            logger.warn("Invalid address format: {}", service.getAddress());
            return;
        }

        String host = parts[0];
        int port;

        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            logger.warn("Invalid port format in address: {}", service.getAddress());
            port = 50051; // Fallback to default
        }

        // Get full address with validated port
        String fullAddress = host + ":" + port;
        logger.info("Connecting to {} service at {}", service.getServiceType(), fullAddress);

        // Build channel with appropriate timeout and retry settings
        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY));

        try {
            switch (service.getServiceType().toLowerCase()) {
                case "traffic" -> {
                    trafficChannel = channelBuilder.build();
                    trafficStub = TrafficGrpc.newStub(trafficChannel);
                    trafficBlockingStub = TrafficGrpc.newBlockingStub(trafficChannel);
                    trafficConnected.set(true);
                    logMessage("Connected to Traffic service at " + fullAddress);
                }
                case "bin" -> {
                    binChannel = channelBuilder.build();
                    binStub = BinGrpc.newStub(binChannel);
                    binConnected.set(true);
                    logMessage("Connected to Bin service at " + fullAddress);
                }
                case "noise" -> {
                    // Special handling for noise service which has connectivity issues
                    // Force port to 50051 as all services run on the same port
                    String correctedAddress = host + ":50051";
                    ManagedChannelBuilder<?> noiseChannelBuilder = ManagedChannelBuilder.forTarget(correctedAddress)
                        .usePlaintext()
                        .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY))
                        .enableRetry()
                        .maxRetryAttempts(5);

                    noiseChannel = noiseChannelBuilder.build();
                    noiseStub = NoiseGrpc.newStub(noiseChannel);

                    // Verify connection is working with a quick ping
                    boolean pingSuccess = pingNoiseService();
                    if (pingSuccess) {
                        noiseConnected.set(true);
                        logMessage("Connected to Noise service at " + correctedAddress);
                    } else {
                        logMessage("Warning: Connected to Noise service but ping failed. Service may be unstable.");
                        // Still set connected since we'll retry operations as needed
                        noiseConnected.set(true);
                    }
                }
                default -> {
                    logger.warn("Unknown service type: {}", service.getServiceType());
                    logMessage("Unknown service type: " + service.getServiceType());
                }
            }
        } catch (Exception e) {
            logger.error("Error connecting to service: " + service.getServiceType(), e);
            logMessage("Error connecting to " + service.getServiceType() + " service: " + e.getMessage());
        }
    }

    /**
     * Ping the noise service to verify connection
     */
    private boolean pingNoiseService() {
        try {
            // Create a temporary stream observer to test connection
            StreamObserver<Alert> testObserver = new StreamObserver<>() {
                @Override
                public void onNext(Alert value) {}

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };

            StreamObserver<NoiseData> sender = noiseStub.monitorNoise(testObserver);

            // Send a test data point
            NoiseData testData = NoiseData.newBuilder()
                .setSensorId("test_sensor")
                .setDecibels(50.0f)
                .build();

            sender.onNext(testData);
            sender.onCompleted();

            return true;
        } catch (Exception e) {
            logger.error("Noise service ping failed", e);
            return false;
        }
    }

    /**
     * Start monitoring all services
     */
    private void startMonitoringServices() {
        // Start traffic monitoring or simulation
        if (trafficConnected.get()) {
            startTrafficMonitoring();
        } else {
            logMessage("Traffic service not connected - starting simulation");
            startSimulatedTrafficData();
        }

        // Start bin monitoring or simulation
        if (binConnected.get()) {
            startBinMonitoring();
        } else {
            logMessage("Bin service not connected - starting simulation");
            startSimulatedBinData();
        }

        // Start noise monitoring or simulation
        if (noiseConnected.get()) {
            startNoiseMonitoring();
        } else {
            logMessage("Noise service not connected - starting simulation");
            startSimulatedNoiseData();
        }

        // Schedule periodic service check to maintain connections
        executor.scheduleAtFixedRate(this::checkServiceConnections, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Periodically check service connections and attempt to reconnect if needed
     */
    private void checkServiceConnections() {
        if (shouldStop.get()) {
            return;
        }

        logMessage("Performing periodic service connection check");

        // Check if registry is connected
        if (!registryConnected.get()) {
            logMessage("Registry service disconnected - attempting to reconnect");
            connectToServices();
            return;
        }

        // Check if any services are disconnected
        boolean needsRefresh = false;

        if (!trafficConnected.get()) {
            logMessage("Traffic service disconnected");
            needsRefresh = true;
        }

        if (!binConnected.get()) {
            logMessage("Bin service disconnected");
            needsRefresh = true;
        }

        if (!noiseConnected.get()) {
            logMessage("Noise service disconnected");
            needsRefresh = true;
        }

        // Refresh services if needed
        if (needsRefresh) {
            logMessage("Refreshing services to reconnect");
            refreshServices();
        }
    }

    /**
     * Start monitoring traffic data
     */
    private void startTrafficMonitoring() {
        // Get initial traffic summary
        executor.execute(() -> {
            try {
                TrafficSummary summary = trafficBlockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getTrafficSummary(Empty.newBuilder().build());

                Platform.runLater(() -> {
                    totalVehiclesLabel.setText("Total Vehicles: " + summary.getTotalVehicles());

                    // Update intersection dropdown if needed
                    for (IntersectionStatus status : summary.getIntersectionsList()) {
                        if (!intersectionSelector.getItems().contains(status.getIntersectionId())) {
                            intersectionSelector.getItems().add(status.getIntersectionId());
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("Error getting traffic summary", e);
                // Generate random intersections if we can't get real ones
                Platform.runLater(() -> {
                    if (intersectionSelector.getItems().isEmpty()) {
                        intersectionSelector.getItems().addAll(
                            "Main-First", "Main-Second", "Broadway-Park", "Fifth-Central"
                        );
                    }
                });
            }
        });

        // Start streaming traffic data
        try {
            trafficStub.streamTraffic(Empty.newBuilder().build(), new StreamObserver<>() {
                @Override
                public void onNext(TrafficData data) {
                    Platform.runLater(() -> {
                        // Add to table
                        trafficTable.getItems().add(0, new TrafficEntry(
                            data.getVehicleCount(),
                            formatTimestamp(data.getTimestamp())
                        ));

                        // Limit table size
                        if (trafficTable.getItems().size() > 100) {
                            trafficTable.getItems().remove(100, trafficTable.getItems().size());
                        }

                        // Update chart
                        XYChart.Series<Number, Number> series = trafficChart.getData().get(0);

                        // Add new data point
                        series.getData().add(new XYChart.Data<>(
                            series.getData().size(),
                            data.getVehicleCount()
                        ));

                        // Limit chart size
                        if (series.getData().size() > 20) {
                            series.getData().remove(0);
                        }
                    });
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Error streaming traffic data", t);
                    logMessage("Traffic data stream error: " + t.getMessage());

                    // Start generating simulated data instead
                    startSimulatedTrafficData();

                    // Try to reconnect after delay
                    executor.schedule(this::reconnectTraffic, 30, TimeUnit.SECONDS);
                }

                private void reconnectTraffic() {
                    if (!shouldStop.get()) {
                        logMessage("Attempting to reconnect to Traffic service...");
                        startTrafficMonitoring();
                    }
                }

                @Override
                public void onCompleted() {
                    logMessage("Traffic data stream completed");
                    // Start generating simulated data
                    startSimulatedTrafficData();
                }
            });
        } catch (Exception e) {
            logger.error("Error starting traffic stream", e);
            logMessage("Error starting traffic stream: " + e.getMessage());
            // Start generating simulated data instead
            startSimulatedTrafficData();
        }
    }

    /**
     * Generate simulated traffic data when the real service is unavailable
     */
    private void startSimulatedTrafficData() {
        logMessage("Starting simulated traffic data generation");

        // Cancel any existing task
        if (trafficSimulationTask != null) {
            trafficSimulationTask.cancel(false);
        }

        // Generate initial data immediately
        generateTrafficData();

        // Start generating random traffic data
        trafficSimulationTask = executor.scheduleAtFixedRate(this::generateTrafficData, 1, 5, TimeUnit.SECONDS);
    }

    /**
     * Generate a single traffic data point
     */
    private void generateTrafficData() {
        if (!shouldStop.get()) {
            // Generate random vehicle count (0-100)
            int vehicleCount = (int)(Math.random() * 100);
            String timestamp = java.time.OffsetDateTime.now().toString();

            // Update UI
            Platform.runLater(() -> {
                try {
                    // Add to table (if initialized)
                    if (trafficTable != null) {
                        trafficTable.getItems().add(0, new TrafficEntry(
                            vehicleCount,
                            formatTimestamp(timestamp)
                        ));

                        // Limit table size
                        if (trafficTable.getItems().size() > 100) {
                            trafficTable.getItems().remove(100, trafficTable.getItems().size());
                        }

                        // Update total vehicles
                        int totalVehicles = 0;
                        for (TrafficEntry entry : trafficTable.getItems()) {
                            totalVehicles += entry.getVehicleCount();
                        }
                        if (totalVehiclesLabel != null) {
                            totalVehiclesLabel.setText("Total Vehicles: " + totalVehicles);
                        }
                    }

                    // Update chart (if initialized)
                    if (trafficChart != null && !trafficChart.getData().isEmpty()) {
                        XYChart.Series<Number, Number> series = trafficChart.getData().get(0);

                        // Add new data point
                        series.getData().add(new XYChart.Data<>(
                            series.getData().size(),
                            vehicleCount
                        ));

                        // Limit chart size
                        if (series.getData().size() > 20) {
                            series.getData().remove(0);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error updating traffic UI", e);
                }
            });

            // Only log occasionally to avoid cluttering the log
            if (Math.random() < 0.1) {
                logMessage("Generated simulated traffic data: " + vehicleCount + " vehicles");
            }
        }
    }

    /**
     * Start monitoring bin data
     */
    private void startBinMonitoring() {
        try {
            // Start monitoring for urgent collections
            binStub.getUrgentCollections(Empty.newBuilder().build(), new StreamObserver<>() {
                @Override
                public void onNext(BinAlert alert) {
                    Platform.runLater(() -> {
                        String message = String.format(
                            "[%s] Urgent collection needed for %s (%d%% full)",
                            getCurrentTime(),
                            alert.getBinId(),
                            alert.getFillPercent()
                        );

                        updateStatusArea(binStatusArea, message);

                        // Update bin fill level if it's one of our displayed bins
                        updateBinFillLevel(alert.getBinId(), alert.getFillPercent());
                    });
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Error monitoring bin collections", t);
                    logMessage("Bin collection monitoring error: " + t.getMessage());

                    // Start simulated bin data
                    startSimulatedBinData();
                }

                @Override
                public void onCompleted() {
                    logMessage("Bin collection monitoring completed");
                    // Start simulated bin data
                    startSimulatedBinData();
                }
            });

            // Start reporting bin status
            reportBinStatus();

        } catch (Exception e) {
            logger.error("Error starting bin monitoring", e);
            logMessage("Error starting bin monitoring: " + e.getMessage());

            // Start simulated bin data
            startSimulatedBinData();
        }
    }

    /**
     * Report bin status to the service
     */
    private void reportBinStatus() {
        if (!binConnected.get() || shouldStop.get()) {
            return;
        }

        try {
            // Create response observer
            StreamObserver<Summary> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(Summary summary) {
                    logMessage("Bin report summary received: avg=" + summary.getAverage() + "%");
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Error reporting bin status", t);
                    // Start simulated bin data
                    startSimulatedBinData();
                }

                @Override
                public void onCompleted() {
                    logMessage("Completed urgent collections check. Found 0 bins needing attention.");
                }
            };

            // Create request observer
            StreamObserver<BinStatus> requestObserver = binStub.reportBins(responseObserver);

            // Send bin status for each bin
            for (int i = 0; i < binFillLevels.length; i++) {
                final String binId = "Bin-" + (i + 1);
                final int fillPercent = (int)(Math.random() * 100);

                // Update UI
                final int index = i;
                Platform.runLater(() -> {
                    updateBinFillLevel(binId, fillPercent);
                });

                // Send to service
                requestObserver.onNext(BinStatus.newBuilder()
                    .setBinId(binId)
                    .setFillPercent(fillPercent)
                    .build());
            }

            // Complete the request
            requestObserver.onCompleted();

            // Schedule next report
            executor.schedule(this::reportBinStatus, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            logger.error("Error reporting bin status", e);
            // Start simulated bin data
            startSimulatedBinData();
        }
    }

    /**
     * Generate simulated bin data when the real service is unavailable
     */
    private void startSimulatedBinData() {
        logMessage("Starting simulated bin data generation");

        // Cancel any existing task
        if (binSimulationTask != null) {
            binSimulationTask.cancel(false);
        }

        // Generate initial data immediately
        generateBinData();

        // Start generating random bin data
        binSimulationTask = executor.scheduleAtFixedRate(this::generateBinData, 1, 8, TimeUnit.SECONDS);
    }

    /**
     * Generate a single set of bin data
     */
    private void generateBinData() {
        if (!shouldStop.get()) {
            try {
                // Check if UI components are initialized
                if (binFillLevels == null || binLabels == null) {
                    return;
                }

                // Generate random bin data
                for (int i = 0; i < binFillLevels.length; i++) {
                    final int index = i;
                    final String binId = "Bin-" + (i + 1);
                    final int fillPercent = (int)(Math.random() * 100);

                    // Update UI
                    Platform.runLater(() -> {
                        try {
                            updateBinFillLevel(binId, fillPercent);
                        } catch (Exception e) {
                            logger.error("Error updating bin fill level", e);
                        }
                    });

                    // Generate urgent collection alerts for bins that are almost full
                    if (fillPercent > 90) {
                        final int finalFillPercent = fillPercent;
                        Platform.runLater(() -> {
                            try {
                                if (binStatusArea != null) {
                                    String message = String.format(
                                        "[%s] Urgent collection needed for %s (%d%% full)",
                                        getCurrentTime(),
                                        binId,
                                        finalFillPercent
                                    );
                                    updateStatusArea(binStatusArea, message);
                                }
                            } catch (Exception e) {
                                logger.error("Error updating bin status area", e);
                            }
                        });
                    }
                }

                // Only log occasionally to avoid cluttering the log
                if (Math.random() < 0.1) {
                    logMessage("Generated simulated bin data for " + binFillLevels.length + " bins");
                }
            } catch (Exception e) {
                logger.error("Error generating bin data", e);
            }
        }
    }

    /**
     * Update bin fill level in the UI
     */
    private void updateBinFillLevel(String binId, int fillPercent) {
        for (int i = 0; i < binLabels.length; i++) {
            if (binLabels[i].getText().startsWith(binId)) {
                binLabels[i].setText(binId + ": " + fillPercent + "%");
                binFillLevels[i].setProgress(fillPercent / 100.0);

                // Set color based on fill level
                if (fillPercent > 90) {
                    binFillLevels[i].setStyle("-fx-accent: red;");
                } else if (fillPercent > 70) {
                    binFillLevels[i].setStyle("-fx-accent: orange;");
                } else {
                    binFillLevels[i].setStyle("-fx-accent: green;");
                }

                break;
            }
        }
    }

    /**
     * Start monitoring noise data
     */
    private void startNoiseMonitoring() {
        try {
            // Set up noise monitoring stream
            StreamObserver<Alert> alertObserver = new StreamObserver<>() {
                @Override
                public void onNext(Alert alert) {
                    Platform.runLater(() -> {
                        String prefix = alert.getIsCritical() ? "[CRITICAL] " : "";
                        String message = String.format(
                            "[%s] %s%s",
                            getCurrentTime(),
                            prefix,
                            alert.getMessage()
                        );

                        updateStatusArea(noiseStatusArea, message);
                    });
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Error in noise monitoring", t);
                    logMessage("Noise monitoring error: " + t.getMessage());

                    // Start sending simulated noise data
                    startSimulatedNoiseData();

                    // Try to reconnect
                    if (!shouldStop.get()) {
                        executor.schedule(() -> {
                            logMessage("Attempting to reconnect noise monitoring...");
                            startNoiseMonitoring();
                        }, 30, TimeUnit.SECONDS);
                    }
                }

                @Override
                public void onCompleted() {
                    logMessage("Noise monitoring completed");
                    // Start sending simulated noise data
                    startSimulatedNoiseData();
                }
            };

            noiseStreamObserver = noiseStub.monitorNoise(alertObserver);

            // Start sending noise data
            sendNoiseData();

        } catch (Exception e) {
            logger.error("Error starting noise monitoring", e);
            logMessage("Error starting noise monitoring: " + e.getMessage());

            // Start sending simulated noise data
            startSimulatedNoiseData();
        }
    }

    /**
     * Send noise data to the service
     */
    private void sendNoiseData() {
        if (noiseStreamObserver == null || !noiseConnected.get() || shouldStop.get()) {
            return;
        }

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

            // Schedule next data point
            executor.schedule(this::sendNoiseData, 3, TimeUnit.SECONDS);

        } catch (Exception e) {
            logger.error("Error sending noise data", e);
            // Start simulated noise data
            startSimulatedNoiseData();
        }
    }

    /**
     * Generate simulated noise alerts when the real service is unavailable
     */
    private void startSimulatedNoiseData() {
        logMessage("Starting simulated noise data generation");

        // Cancel any existing task
        if (noiseMonitoringTask != null) {
            noiseMonitoringTask.cancel(false);
        }

        // Generate initial data immediately
        generateNoiseData();

        // Start generating random noise alerts
        noiseMonitoringTask = executor.scheduleAtFixedRate(this::generateNoiseData, 1, 4, TimeUnit.SECONDS);
    }

    /**
     * Generate a single noise data point
     */
    private void generateNoiseData() {
        if (!shouldStop.get()) {
            try {
                // Generate random noise data
                String[] zones = {"Zone-1", "Zone-2", "Zone-3"};
                String zone = zones[(int)(Math.random() * zones.length)];
                float decibels = 40 + (float)(Math.random() * 50); // 40-90 dB
                boolean isCritical = decibels > 80;

                // Create alert message
                String timeContext = LocalDateTime.now().getHour() >= 22 || LocalDateTime.now().getHour() < 7 ? "nighttime" : "daytime";
                String message = String.format(
                    "High noise level detected in zone %s: %.1f dB (limit: %.1f dB, %s)",
                    zone, decibels, isCritical ? 70.0f : 55.0f, timeContext
                );

                // Update UI
                final String finalMessage = message;
                final boolean finalIsCritical = isCritical;
                Platform.runLater(() -> {
                    try {
                        if (noiseStatusArea != null) {
                            String prefix = finalIsCritical ? "[CRITICAL] " : "";
                            String formattedMessage = String.format(
                                "[%s] %s%s",
                                getCurrentTime(),
                                prefix,
                                finalMessage
                            );

                            updateStatusArea(noiseStatusArea, formattedMessage);
                        }
                    } catch (Exception e) {
                        logger.error("Error updating noise status area", e);
                    }
                });

                // Log significant noise events, but only occasionally
                if (isCritical && Math.random() < 0.3) {
                    logMessage("Simulated critical noise level in " + zone + ": " + decibels + " dB");
                }
            } catch (Exception e) {
                logger.error("Error generating noise data", e);
            }
        }
    }

    /**
     * Set traffic light color for an intersection
     */
    private void setTrafficLight(String intersection, String color) {
        if (intersection == null || color == null || intersection.isEmpty() || color.isEmpty()) {
            logMessage("Please select both intersection and light color");
            return;
        }

        if (trafficChannel == null || !trafficConnected.get()) {
            logMessage("Error: Not connected to traffic service");
            // Try to reconnect
            refreshServices();
            return;
        }

        executor.execute(() -> {
            try {
                LightCommand command = LightCommand.newBuilder()
                    .setIntersection(intersection)
                    .setColor(color)
                    .build();

                Response response = trafficBlockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withWaitForReady()
                    .setLight(command);

                logMessage("Traffic light set: " + response.getStatus());

                // Update traffic summary after changing light
                try {
                    TrafficSummary summary = trafficBlockingStub
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getTrafficSummary(Empty.newBuilder().build());

                    Platform.runLater(() -> {
                        totalVehiclesLabel.setText("Total Vehicles: " + summary.getTotalVehicles());
                    });
                } catch (Exception e) {
                    logger.error("Error getting traffic summary", e);
                    // Don't show this error to the user as it's not critical
                }

            } catch (Exception e) {
                logger.error("Error setting traffic light", e);
                String errorMsg = e.getMessage();

                if (errorMsg != null && errorMsg.contains("UNAVAILABLE")) {
                    logMessage("Traffic service unavailable. Attempting to reconnect...");
                    trafficConnected.set(false);
                    refreshServices();
                } else {
                    logMessage("Error setting traffic light: " + errorMsg);
                }
            }
        });
    }

    /**
     * Get collection route for a zone
     */
    private void getCollectionRoute(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) {
            updateStatusArea(binStatusArea, "Error: Please select a valid zone");
            return;
        }

        // Try to use the real service if connected
        if (binChannel != null && binConnected.get()) {
            executor.execute(() -> {
                try {
                    Zone zone = Zone.newBuilder()
                        .setAreaId(zoneId)
                        .build();

                    Route route = BinGrpc.newBlockingStub(binChannel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .withWaitForReady()
                        .getRoute(zone);

                    StringBuilder message = new StringBuilder();
                    message.append("[").append(getCurrentTime()).append("] ");
                    message.append("Collection route for ").append(zoneId).append(":\n");

                    if (route.getBinsCount() == 0) {
                        // Fall back to simulated data if the service returns no bins
                        getSimulatedCollectionRoute(zoneId);
                        return;
                    } else {
                        message.append("Priority collection bins:\n");
                        for (String binId : route.getBinsList()) {
                            message.append("- ").append(binId).append("\n");
                        }
                    }

                    updateStatusArea(binStatusArea, message.toString());
                    logMessage("Retrieved collection route for zone " + zoneId);

                } catch (Exception e) {
                    logger.error("Error getting collection route", e);
                    String errorMsg = e.getMessage();

                    if (errorMsg != null && errorMsg.contains("UNAVAILABLE")) {
                        updateStatusArea(binStatusArea, "Bin service unavailable. Using simulated data...");
                        binConnected.set(false);
                        getSimulatedCollectionRoute(zoneId);
                    } else {
                        updateStatusArea(binStatusArea, "Error getting collection route. Using simulated data...");
                        getSimulatedCollectionRoute(zoneId);
                    }
                }
            });
        } else {
            // Use simulated data if not connected
            getSimulatedCollectionRoute(zoneId);
        }
    }

    /**
     * Generate a simulated collection route based on current bin fill levels
     */
    private void getSimulatedCollectionRoute(String zoneId) {
        executor.execute(() -> {
            try {
                // Check if UI components are initialized
                if (binFillLevels == null || binLabels == null || binStatusArea == null) {
                    logMessage("Cannot generate collection route: UI components not initialized");
                    return;
                }

                StringBuilder message = new StringBuilder();
                message.append("[").append(getCurrentTime()).append("] ");
                message.append("Collection route for ").append(zoneId).append(":\n");

                // Get bins that need collection (over 70% full)
                List<String> binsToCollect = new ArrayList<>();

                // Check all bins in the selected zone
                // For simplicity, we'll assume each zone has 1-2 bins with matching zone number
                for (int i = 0; i < binFillLevels.length; i++) {
                    String binId = "Bin-" + (i + 1);
                    String binText = "";

                    // Safely get the bin text
                    if (binLabels[i] != null) {
                        binText = binLabels[i].getText();
                    }

                    // Extract fill percentage from label text (format: "Bin-X: YY%")
                    int fillPercent = 0;
                    try {
                        if (binText != null && !binText.isEmpty() && binText.contains(":") && binText.contains("%")) {
                            String percentText = binText.substring(binText.indexOf(":") + 1, binText.indexOf("%")).trim();
                            fillPercent = Integer.parseInt(percentText);
                        } else {
                            // Use a random value if parsing fails
                            fillPercent = (int)(Math.random() * 100);
                        }
                    } catch (Exception e) {
                        // Use a random value if parsing fails
                        fillPercent = (int)(Math.random() * 100);
                    }

                    // Add bin to collection list if it's in the right zone and fill level is high
                    boolean inSelectedZone = false;

                    // Match bins to zones based on their number
                    if (zoneId.equals("Zone-1") && (i == 0 || i == 1)) {
                        inSelectedZone = true;
                    } else if (zoneId.equals("Zone-2") && (i == 1 || i == 2)) {
                        inSelectedZone = true;
                    } else if (zoneId.equals("Zone-3") && (i == 2 || i == 3)) {
                        inSelectedZone = true;
                    } else if (zoneId.equals("Zone-4") && (i == 3 || i == 0)) {
                        inSelectedZone = true;
                    }

                    if (inSelectedZone && fillPercent >= 70) {
                        binsToCollect.add(binId + " " + fillPercent + "%");
                    }
                }

                // Add some randomness - occasionally add an extra bin
                if (Math.random() < 0.3) {
                    int randomBin = (int)(Math.random() * 4) + 1;
                    String extraBin = "Bin-" + randomBin + " (special collection)";
                    if (!binsToCollect.contains(extraBin)) {
                        binsToCollect.add(extraBin);
                    }
                }

                // Build the message
                if (binsToCollect.isEmpty()) {
                    message.append("No bins require collection at this time.");
                } else {
                    message.append("Priority collection bins:\n");
                    for (String binId : binsToCollect) {
                        message.append("- ").append(binId).append("\n");
                    }
                }

                final String finalMessage = message.toString();
                Platform.runLater(() -> {
                    updateStatusArea(binStatusArea, finalMessage);
                });

                logMessage("Generated simulated collection route for zone " + zoneId +
                          " with " + binsToCollect.size() + " bins");
            } catch (Exception e) {
                logger.error("Error generating collection route", e);
                Platform.runLater(() -> {
                    if (binStatusArea != null) {
                        updateStatusArea(binStatusArea, "Error generating collection route: " + e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Set noise thresholds for a zone
     */
    private void setNoiseThresholds(String zone, double dayLimit, double nightLimit) {
        if (noiseChannel == null || zone == null) {
            updateStatusArea(noiseStatusArea, "Error: Not connected to noise service or invalid zone");
            return;
        }

        executor.execute(() -> {
            try {
                NoiseThreshold threshold = NoiseThreshold.newBuilder()
                    .setZoneId(zone)
                    .setDayLimit((float) dayLimit)
                    .setNightLimit((float) nightLimit)
                    .build();

                // Use a timeout to prevent hanging
                Response response = NoiseGrpc.newBlockingStub(noiseChannel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withWaitForReady()
                    .setZoneThreshold(threshold);

                updateStatusArea(noiseStatusArea, "Thresholds updated: " + response.getStatus());

            } catch (Exception e) {
                logger.error("Error setting noise thresholds", e);
                String errorMsg = e.getMessage();

                // Check if it's a connection issue
                if (errorMsg != null && errorMsg.contains("Connection refused")) {
                    updateStatusArea(noiseStatusArea, "Error: Cannot connect to noise service. The service may be running on a different port.");

                    // Try to reconnect with corrected port
                    reconnectNoiseService();
                } else {
                    updateStatusArea(noiseStatusArea, "Error: " + errorMsg);
                }
            }
        });
    }

    /**
     * Attempt to reconnect to the noise service with corrected port
     */
    private void reconnectNoiseService() {
        try {
            // Close existing channel if any
            if (noiseChannel != null) {
                noiseChannel.shutdown();
            }

            // Create new channel with port 50051
            String correctedAddress = "localhost:50051";
            noiseChannel = ManagedChannelBuilder.forTarget(correctedAddress)
                .usePlaintext()
                .intercept(new ClientAuthInterceptor(SecurityConfig.DEFAULT_API_KEY))
                .enableRetry()
                .maxRetryAttempts(3)
                .build();

            noiseStub = NoiseGrpc.newStub(noiseChannel);

            // Test connection
            boolean success = pingNoiseService();
            if (success) {
                noiseConnected.set(true);
                logMessage("Reconnected to Noise service at " + correctedAddress);
                updateStatusArea(noiseStatusArea, "Reconnected to Noise service. Please try again.");
            } else {
                logMessage("Failed to reconnect to Noise service");
            }
        } catch (Exception e) {
            logger.error("Error reconnecting to noise service", e);
        }
    }

    /**
     * Refresh the list of services
     */
    private void refreshServices() {
        if (registryChannel == null) {
            logMessage("Error: Not connected to registry service");
            return;
        }

        Platform.runLater(() -> {
            servicesList.getItems().clear();
            connectionProgress.setVisible(true);
            statusLabel.setText("Refreshing services...");
        });

        ServiceFilter filter = ServiceFilter.newBuilder().build();

        registryStub.discoverServices(filter, new StreamObserver<>() {
            private boolean foundServices = false;

            @Override
            public void onNext(ServiceInfo service) {
                foundServices = true;
                Platform.runLater(() -> {
                    servicesList.getItems().add(
                        String.format("%s (%s)", service.getServiceType(), service.getAddress())
                    );
                });

                // Reconnect to the service if we're not already connected
                connectToService(service);
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Service refresh error", t);
                logMessage("Error refreshing services: " + t.getMessage());

                Platform.runLater(() -> {
                    connectionProgress.setVisible(false);
                    statusLabel.setText("Service refresh failed");
                });
            }

            @Override
            public void onCompleted() {
                logMessage("Service refresh completed");

                // If no services were found, try to register them again
                if (!foundServices) {
                    logMessage("No services found. Attempting to re-register services...");
                    registerServices();
                }

                Platform.runLater(() -> {
                    connectionProgress.setVisible(false);
                    statusLabel.setText("Service refresh completed");
                });
            }
        });
    }

    /**
     * Register services with the registry
     * This is used when services are marked as stale and removed from the registry
     */
    private void registerServices() {
        if (registryChannel == null) {
            logMessage("Error: Not connected to registry service");
            return;
        }

        // Register traffic service
        registerService("traffic", "localhost:50051");

        // Register bin service
        registerService("bin", "localhost:50051");

        // Register noise service
        registerService("noise", "localhost:50051");

        // Schedule a refresh after registration
        executor.schedule(this::refreshServices, 2, TimeUnit.SECONDS);
    }

    /**
     * Register a service with the registry
     */
    private void registerService(String type, String address) {
        try {
            ServiceInfo serviceInfo = ServiceInfo.newBuilder()
                .setServiceType(type)
                .setServiceId(type + "_" + System.currentTimeMillis())
                .setAddress(address)
                .build();

            Confirmation confirmation = RegistryGrpc.newBlockingStub(registryChannel)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .register(serviceInfo);

            logMessage("Registered service: " + type + " at " + address + " - " + confirmation.getStatus());
        } catch (Exception e) {
            logger.error("Error registering service: " + type, e);
            logMessage("Error registering service: " + type + " - " + e.getMessage());
        }
    }

    /**
     * Log a message to both the logger and the UI
     */
    private void logMessage(String message) {
        logger.info(message);

        Platform.runLater(() -> {
            String formattedMessage = "[" + getCurrentTime() + "] " + message;
            systemLogArea.appendText(formattedMessage + "\n");

            // Auto-scroll to bottom
            systemLogArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * Update a status text area with a new message
     */
    private void updateStatusArea(TextArea area, String message) {
        Platform.runLater(() -> {
            area.appendText(message + "\n");

            // Auto-scroll to bottom
            area.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * Get the current time as a formatted string
     */
    private String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /**
     * Format a timestamp string
     */
    private String formatTimestamp(String timestamp) {
        try {
            return timestamp.replace("T", " ").replace("Z", "").substring(0, 19);
        } catch (Exception e) {
            return timestamp;
        }
    }
}
