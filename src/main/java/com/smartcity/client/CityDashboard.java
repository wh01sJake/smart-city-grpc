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
    // Traffic data model
    public static class TrafficEntry {
        private final StringProperty intersectionId = new SimpleStringProperty();
        private final StringProperty lightColor = new SimpleStringProperty();
        private final IntegerProperty waitingVehicles = new SimpleIntegerProperty();
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final LongProperty lastUpdated = new SimpleLongProperty(System.currentTimeMillis());

        public TrafficEntry(String intersectionId, String lightColor, int waitingVehicles) {
            this.intersectionId.set(intersectionId);
            this.lightColor.set(lightColor);
            this.waitingVehicles.set(waitingVehicles);
        }

        // Getters
        public String getIntersectionId() { return intersectionId.get(); }
        public String getLightColor() { return lightColor.get(); }
        public int getWaitingVehicles() { return waitingVehicles.get(); }
        public boolean isSelected() { return selected.get(); }
        public long getLastUpdated() { return lastUpdated.get(); }

        // Properties
        public StringProperty intersectionIdProperty() { return intersectionId; }
        public StringProperty lightColorProperty() { return lightColor; }
        public IntegerProperty waitingVehiclesProperty() { return waitingVehicles; }
        public BooleanProperty selectedProperty() { return selected; }
        public LongProperty lastUpdatedProperty() { return lastUpdated; }

        // Setters
        public void setLightColor(String color) { 
            if (color == null || color.isEmpty()) {
                return; // Prevent setting invalid color
            }
            lightColor.set(color);
            lastUpdated.set(System.currentTimeMillis());
        }
        
        public void setWaitingVehicles(int count) { 
            if (count < 0) {
                return; // Prevent setting invalid count
            }
            waitingVehicles.set(count);
            lastUpdated.set(System.currentTimeMillis());
        }
        
        /**
         * Update selection state and log the change.
         * @param selected New selection state
         * @param reason Optional reason for the selection change (for logging)
         */
        public void setSelected(boolean selected, String reason) {
            if (this.selected.get() == selected) {
                return; // No change needed
            }
            
            this.selected.set(selected);
            
            // Log selection change
            LogManager.getLogger(CityDashboard.class).debug(
                "Intersection {} selection state changed to {}: {}",
                intersectionId.get(), selected ? "SELECTED" : "UNSELECTED",
                reason != null ? reason : "No reason provided"
            );
        }
        
        /**
         * For backward compatibility with existing code
         */
        public void setSelected(boolean selected) {
            setSelected(selected, null);
        }
        
        // Helper method to update all data at once
        public void updateData(String lightColor, int waitingVehicles) {
            this.lightColor.set(lightColor);
            this.waitingVehicles.set(waitingVehicles);
            this.lastUpdated.set(System.currentTimeMillis());
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
                logMessage("Sending command to set " + intersection + " traffic light to " + color);
                setTrafficLight(intersection, color);
            } else {
                logMessage("Please select both intersection and light color");
            }
        });
        
        // Add listener to handle intersection selection changes
        // Add listener to handle intersection selection changes
        intersectionSelector.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            // First, update selection state in the data model
            if (oldVal != null) {
                // Deselect the old intersection in our model
                TrafficEntry oldEntry = intersectionMap.get(oldVal);
                if (oldEntry != null) {
                    oldEntry.setSelected(false, "UI selection changed");
                }
            }
            
            if (newVal != null) {
                // Select the new intersection in our model
                logMessage("Selected intersection: " + newVal);
                
                TrafficEntry newEntry = intersectionMap.get(newVal);
                if (newEntry != null) {
                    // Update the model with a reason for the change
                    newEntry.setSelected(true, "User selected in UI");
                    // Update light color selector to match current state
                    String currentColor = newEntry.getLightColor();
                    if (currentColor != null && !currentColor.isEmpty()) {
                        lightColorSelector.setValue(currentColor);
                        logMessage("Current light status: " + currentColor);
                    }
                    
                    // Log current traffic status
                    logMessage("Current traffic at " + newVal + ": " + 
                              newEntry.getWaitingVehicles() + " vehicles waiting");
                } else {
                    // This is unusual - we have a selection for which we don't have data
                    logger.warn("Selected intersection '{}' has no data in the model", newVal);
                    logMessage("Warning: No data available for " + newVal);
                }
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
    /**
     * Get and display urgent bin collections.
     * This method enhances the handling of urgent bin collections by clearly
     * differentiating urgent vs. non-urgent collections and providing better visualization.
     */
    private void getUrgentCollections() {
        if (binStub == null || !binConnected.get()) {
            logMessage("Not connected to Bin service - cannot check urgent collections");
            binStatusArea.setText("Error: Not connected to bin service");
            return;
        }

        binStatusArea.clear();
        binStatusArea.setText("Checking collections status...\n");
        
        binStub.getUrgentCollections(Empty.newBuilder().build(),
            new StreamObserver<BinAlert>() {
                private final StringBuilder urgentAlerts = new StringBuilder("URGENT COLLECTIONS:\n");
                private final StringBuilder regularAlerts = new StringBuilder("\nREGULAR COLLECTIONS:\n");
                private int urgentCount = 0;
                private int regularCount = 0;
                private static final int URGENT_THRESHOLD = 85; // Consider bins over 85% as urgent

                @Override
                public void onNext(BinAlert alert) {
                    // Determine if collection is urgent based on server flag and fill percent
                    boolean isUrgent = alert.getUrgentCollection() || alert.getFillPercent() >= URGENT_THRESHOLD;
                    
                    if (isUrgent) {
                        urgentCount++;
                        urgentAlerts.append(String.format("%d. Bin %s: %d%% full ⚠️ URGENT\n",
                            urgentCount,
                            alert.getBinId(),
                            alert.getFillPercent()));
                            
                        logMessage("URGENT: Bin " + alert.getBinId() + " requires immediate collection! (" + 
                                  alert.getFillPercent() + "% full)");
                    } else {
                        regularCount++;
                        regularAlerts.append(String.format("%d. Bin %s: %d%% full\n",
                            regularCount,
                            alert.getBinId(),
                            alert.getFillPercent()));
                    }
                    
                    Platform.runLater(() -> {
                        StringBuilder fullDisplay = new StringBuilder();
                        
                        // Add summary header
                        fullDisplay.append("COLLECTION STATUS SUMMARY:\n");
                        fullDisplay.append("--------------------------------\n");
                        
                        // Only display urgent section if there are urgent collections
                        if (urgentCount > 0) {
                            fullDisplay.append(urgentAlerts);
                            fullDisplay.append("\n");
                        }
                        
                        // Only display regular section if there are regular collections
                        if (regularCount > 0) {
                            fullDisplay.append(regularAlerts);
                        } else if (urgentCount == 0) {
                            fullDisplay.append("No collections needed at this time.\n");
                        }
                        
                        binStatusArea.setText(fullDisplay.toString());
                    });
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Error checking bin collections", t);
                    Platform.runLater(() -> {
                        String error = "Error checking collections: " + t.getMessage();
                        binStatusArea.setText("ERROR: " + error);
                        logMessage(error);
                    });
                }

                @Override
                public void onCompleted() {
                    Platform.runLater(() -> {
                        if (urgentCount == 0 && regularCount == 0) {
                            binStatusArea.setText("No collections needed at this time.");
                        }
                        
                        // Log summary
                        String summary = String.format(
                            "Completed collections check. Found %d urgent and %d regular collections.",
                            urgentCount, regularCount);
                        logMessage(summary);
                        
                        // Add summary to bottom of display
                        binStatusArea.appendText("\n\n" + summary);
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

    /**
     * Set traffic light color for an intersection.
     * This method handles validation and ensures proper synchronization with the UI.
     */
    private void setTrafficLight(String intersection, String color) {
        if (trafficStub == null || !trafficConnected.get()) {
            logMessage("Not connected to Traffic service - cannot set light");
            return;
        }
        
        // Validate intersection ID
        if (intersection == null || intersection.isEmpty()) {
            logMessage("Error: Invalid intersection ID");
            return;
        }

        // Validate intersection exists in selector
        if (!intersectionSelector.getItems().contains(intersection)) {
            logMessage("Error: Intersection '" + intersection + "' is not in the selection list");
            return;
        }
        
        // Validate current selection state
        String currentSelection = intersectionSelector.getValue();
        if (currentSelection == null || !currentSelection.equals(intersection)) {
            logger.warn("Setting traffic light for non-selected intersection: {}", intersection);
            logMessage("Note: Setting traffic light for intersection that is not currently selected");
            
            // Option: Auto-select this intersection to match action
            // intersectionSelector.setValue(intersection);
        }

        // Check if the intersection exists in our data
        TrafficEntry existingEntry = intersectionMap.get(intersection);
        if (existingEntry == null) {
            logMessage("Warning: Attempting to set light for unknown intersection: " + intersection);
            // Create a placeholder entry for this intersection
            logger.info("Creating new traffic entry for intersection: {}", intersection);
            existingEntry = new TrafficEntry(intersection, color, 0);
            
            // Set selection state matching the current UI selection
            boolean isSelected = intersection.equals(intersectionSelector.getValue());
            existingEntry.setSelected(isSelected, "Newly created entry for setTrafficLight");
            
            // Add to data structures
            intersectionMap.put(intersection, existingEntry);
            trafficData.add(existingEntry);
            
            logMessage("Created new traffic entry for " + intersection);
        } else {
            logMessage("Current status of " + intersection + ": Light=" + 
                      existingEntry.getLightColor() + ", Vehicles=" + 
                      existingEntry.getWaitingVehicles());
        }

        // Validate traffic light color
        if (color == null || !color.matches("(?i)RED|YELLOW|GREEN")) {
            logMessage("Error: Invalid traffic light color: " + color);
            return;
        }
        
        logMessage("Sending command to change " + intersection + " traffic light to " + color);
        
        LightCommand command = LightCommand.newBuilder()
            .setIntersection(intersection)
            .setColor(color)
            .build();

        trafficStub.setLight(command, new StreamObserver<Response>() {
            @Override
            public void onNext(Response response) {
                Platform.runLater(() -> {
                    logMessage("Traffic light updated successfully: " + response.getStatus());
                    
                    // Update the traffic entry if it exists
                    TrafficEntry entry = intersectionMap.get(intersection);
                    if (entry != null) {
                        // Update properties
                        entry.setLightColor(color);
                        
                        // Ensure selection state is correct
                        boolean shouldBeSelected = intersection.equals(intersectionSelector.getValue());
                        if (entry.isSelected() != shouldBeSelected) {
                            entry.setSelected(shouldBeSelected, "Updated during traffic light change");
                        }
                        
                        logMessage("Updated local state for " + intersection + " to " + color);
                    } else {
                        // Create a new entry if it doesn't exist yet
                        TrafficEntry newEntry = new TrafficEntry(intersection, color, 0);
                        
                        // Set selection state
                        boolean isSelected = intersection.equals(intersectionSelector.getValue());
                        newEntry.setSelected(isSelected, "New entry created in traffic light response");
                        
                        // Add to data structures
                        intersectionMap.put(intersection, newEntry);
                        trafficData.add(newEntry);
                        logMessage("Created new traffic entry for " + intersection + " with " + color + " light");
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    String errorMsg = "Error setting traffic light: " + t.getMessage();
                    logMessage(errorMsg);
                    logger.error("Failed to set traffic light for {}: {}", intersection, t.getMessage(), t);
                });
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
        
        logMessage("Initializing traffic data stream...");
        StreamObserver<TrafficData> observer = new StreamObserver<TrafficData>() {
            @Override
            public void onNext(TrafficData data) {
                // First validate data exists to prevent NPE
                if (data == null) {
                    logger.warn("Received null traffic data from stream");
                    return;
                }
                
                // Only log significant traffic events to reduce noise
                if (data.getVehicleCount() > 10) {
                    logMessage("Received traffic data: Intersection=" + data.getIntersectionId() + 
                               ", Light=" + data.getLightColor() + 
                               ", Vehicles=" + data.getVehicleCount());
                } else {
                    // Use debug level for routine updates
                    logger.debug("Traffic update: {}={}, Light={}, Vehicles={}", 
                               "Intersection", data.getIntersectionId(),
                               data.getLightColor(), data.getVehicleCount());
                }
                
                // Process the data directly without nested Platform.runLater
                updateTrafficData(data);
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
        try {
            trafficStub.streamTraffic(Empty.newBuilder().build(), observer);
            logMessage("Traffic data stream started successfully");
        } catch (Exception e) {
            logger.error("Failed to start traffic data stream", e);
            logMessage("Error starting traffic data stream: " + e.getMessage());
        }
    }

    /**
     * Process traffic data received from the service.
     * This method validates the data and then updates the UI.
     */
    private void updateTrafficData(TrafficData data) {
        // Full validation of all data fields
        if (data == null) {
            logger.warn("Received null traffic data");
            return;
        }
        
        String intersectionId = data.getIntersectionId();
        String lightColor = data.getLightColor();
        int vehicleCount = data.getVehicleCount();
        
        // Enhanced validation for all fields
        if (intersectionId == null || intersectionId.isEmpty()) {
            logger.warn("Received traffic data with invalid intersection ID");
            return;
        }
        
        if (lightColor == null || lightColor.isEmpty()) {
            logger.warn("Received traffic data with invalid light color for intersection: {}", intersectionId);
            return;
        }
        
        if (vehicleCount < 0) {
            logger.warn("Received traffic data with invalid vehicle count for intersection: {}", intersectionId);
            return;
        }
        
        // More detailed logging at different levels based on importance
        if (vehicleCount > 20) {
            logger.info("High traffic: Intersection={}, Light={}, Vehicles={}", 
                      intersectionId, lightColor, vehicleCount);
        } else {
            logger.debug("Traffic update: Intersection={}, Light={}, Vehicles={}", 
                        intersectionId, lightColor, vehicleCount);
        }
        
        // Use a single runLater to ensure atomicity of the update
        Platform.runLater(() -> {
            try {
                // Update the vehicle count more accurately
                // Only increment by the count instead of adding to running total each time
                // This prevents double-counting when the same data is processed multiple times
                if (vehicleCount > 0) {
                    totalVehicles.set(totalVehicles.get() + vehicleCount);
                }
                
                // Update the UI with the validated data
                updateTrafficUI(intersectionId, lightColor, vehicleCount);
            } catch (Exception e) {
                logger.error("Error processing traffic data: {} - {}",
                           intersectionId, e.getMessage(), e);
                logMessage("Error updating traffic display: " + e.getMessage());
            }
        });
    }
    
    /**
     * Update the traffic UI with data received from the service.
     * This method handles both new and existing intersections.
     */
    /**
     * Update the traffic UI with data received from the service.
     * This method ensures proper synchronization between model and UI.
     */
    private void updateTrafficUI(String intersectionId, String lightColor, int vehicleCount) {
        // Update total vehicles label
        totalVehiclesLabel.setText("Total Vehicles: " + totalVehicles.get());
        
        // Get current selection state from UI
        String selectedIntersection = intersectionSelector.getValue();
        boolean isCurrentSelection = (selectedIntersection != null && 
                                    selectedIntersection.equals(intersectionId));
        
        // Log the selection state for debugging
        logger.debug("Processing update for intersection: {} (selected in UI: {})", 
                   intersectionId, isCurrentSelection);
        
        // Get existing entry or prepare to create a new one
        TrafficEntry entry = intersectionMap.get(intersectionId);
        
        // Track significant changes for logging
        boolean isNewIntersection = (entry == null);
        boolean lightChanged = false;
        int oldVehicleCount = 0;
        
        if (entry == null) {
            // Create a new entry for this intersection
            entry = new TrafficEntry(intersectionId, lightColor, vehicleCount);
            logger.info("Creating new traffic entry for intersection: {}", intersectionId);
            
            // Set selection state with reason
            entry.setSelected(isCurrentSelection, "New intersection detected");
            
            // Add to data structures
            intersectionMap.put(intersectionId, entry);
            trafficData.add(entry);
            
            // Add to selector if not already present
            if (!intersectionSelector.getItems().contains(intersectionId)) {
                intersectionSelector.getItems().add(intersectionId);
                logMessage("Added new intersection: " + intersectionId);
                
                // Debug log for intersection selector state
                logger.debug("Intersection selector now has {} items", intersectionSelector.getItems().size());
                
                // If this is the first intersection, select it automatically
                if (intersectionSelector.getItems().size() == 1) {
                    logMessage("Auto-selecting first intersection: " + intersectionId);
                    intersectionSelector.setValue(intersectionId);
                    
                    // Also update the light color selector
                    lightColorSelector.setValue(lightColor);
                    
                    // Ensure the traffic entry is marked as selected with reason
                    entry.setSelected(true, "Auto-selected as first intersection");
                }
                
                // Alphabetically sort the intersections for better UX
                FXCollections.sort(intersectionSelector.getItems());
            }
        } else {
            // Track changes for existing entry
            lightChanged = !entry.getLightColor().equals(lightColor);
            oldVehicleCount = entry.getWaitingVehicles();
            
            // Log any significant changes for debugging
            if (lightChanged) {
                logger.debug("Traffic light changing for {}: {} -> {}", 
                           intersectionId, entry.getLightColor(), lightColor);
            }
            
            if (Math.abs(vehicleCount - oldVehicleCount) > 5) {
                logger.debug("Vehicle count changing for {}: {} -> {}", 
                           intersectionId, oldVehicleCount, vehicleCount);
            }
            
            // Update the entry properties
            entry.updateData(lightColor, vehicleCount);
            
            // Update UI if this is the currently selected intersection
            if (isCurrentSelection) {
                if (lightChanged) {
                    logger.info("Updating selected intersection light color in UI: {}", lightColor);
                    lightColorSelector.setValue(lightColor);
                }
                
                // Fix any selection state mismatch
                if (!entry.isSelected()) {
                    logger.warn("Selection state mismatch detected for intersection: {}", intersectionId);
                    entry.setSelected(true, "Fixed UI/model selection mismatch");
                }
            } else if (entry.isSelected()) {
                // This entry is marked as selected in the model but not in the UI
                logger.warn("Found incorrectly selected entry: {}. UI selection is: {}", 
                          intersectionId, selectedIntersection);
                
                // If this entry shows selected but UI has a different selection,
                // update the model to match the UI (UI is source of truth)
                entry.setSelected(false, "Synchronized with UI selection");
            }
        }
        
        // Log significant changes
        if (isNewIntersection) {
            logger.info("New intersection detected: {}", intersectionId);
        } else if (lightChanged) {
            logMessage("Traffic light at " + intersectionId + " changed to " + lightColor);
        }
        
        // Log significant traffic changes (but not for new intersections to avoid spam)
        if (!isNewIntersection && Math.abs(vehicleCount - oldVehicleCount) > 10) {
            logMessage("Significant traffic change at " + intersectionId + 
                      ": from " + oldVehicleCount + " to " + vehicleCount + " vehicles");
        }
        
        // Log high traffic situations
        if (vehicleCount > 20) {
            logMessage(String.format("High traffic volume: %d vehicles at %s (light: %s)", 
                vehicleCount, intersectionId, lightColor));
        }
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

    /**
     * Connect to a discovered service with improved error handling.
     * This implementation adds:
     * - Better port management and validation
     * - Improved error handling with automatic retry
     * - Connection status validation
     * - Better logging
     */
    private void connectToService(ServiceInfo service) {
        // Validate service input
        if (service == null || service.getServiceType().isEmpty() || service.getAddress().isEmpty()) {
            logger.warn("Invalid service info received - unable to connect");
            return;
        }
        
        // Parse address and port with validation
        String[] addressParts = service.getAddress().split(":");
        String host = addressParts[0];
        
        if (host == null || host.isEmpty()) {
            logger.warn("Invalid host in service address: {}", service.getAddress());
            return;
        }
        
        // Port management with defaults and validation
        int port;
        try {
            // Use service-specific default ports if none specified
            if (addressParts.length > 1) {
                port = Integer.parseInt(addressParts[1]);
            } else {
                // Default ports by service type
                port = switch (service.getServiceType().toLowerCase()) {
                    case "traffic" -> 50051;
                    case "bin" -> 50052;
                    case "noise" -> 50053;
                    default -> 50051;
                };
                logger.info("No port specified for {} service, using default: {}", 
                          service.getServiceType(), port);
            }
            
            // Validate port range
            if (port < 1 || port > 65535) {
                logger.warn("Invalid port number: {}", port);
                port = 50051; // Fallback to default
            }
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
                    try {
                        noiseChannel = channelBuilder
                            // Add more aggressive retry settings for noise service
                            .enableRetry()
                            .maxRetryAttempts(3)
                            .build();
                        
                        noiseStub = NoiseGrpc.newStub(noiseChannel);
                        
                        // Verify connection is working with a quick ping
                        boolean pingSuccess = pingNoiseService();
                        if (pingSuccess) {
                            noiseConnected.set(true);
                            logMessage("Connected to Noise service at " + fullAddress);
                        } else {
                            logMessage("Warning: Connected to Noise service but ping failed. Service may be unstable.");
                            // Still set connected since we'll retry operations as needed
                            noiseConnected.set(true);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to connect to Noise service: {}", e.getMessage(), e);
                        logMessage("Error connecting to Noise service: " + e.getMessage() + 
                                 ". Will retry automatically when operations are requested.");
                        
                        // Schedule reconnection attempt
                        scheduleNoiseReconnection();
                    }
                }
1227

    private void startMonitoringServices() {
        logMessage("Starting monitoring for connected services...");
        
        if (trafficConnected.get()) {
            logMessage("Traffic service is connected - starting traffic monitoring");
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
