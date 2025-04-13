package com.smartcity.client;

import com.smartcity.*;
import com.smartcity.Alert;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
// Add at the top of the file after the imports
public class CityDashboard extends Application {
    // Model classes
    public static class IntersectionStatusItem {
        private final StringProperty intersectionId = new SimpleStringProperty();
        private final StringProperty lightColor = new SimpleStringProperty();
        private final IntegerProperty waitingVehicles = new SimpleIntegerProperty();

        public IntersectionStatusItem(String intersectionId, String lightColor, int waitingVehicles) {
            this.intersectionId.set(intersectionId);
            this.lightColor.set(lightColor);
            this.waitingVehicles.set(waitingVehicles);
        }

        // Getters and setters
        public String getIntersectionId() { return intersectionId.get(); }
        public StringProperty intersectionIdProperty() { return intersectionId; }

        public String getLightColor() { return lightColor.get(); }
        public StringProperty lightColorProperty() { return lightColor; }
        public void setLightColor(String color) { this.lightColor.set(color); }

        public int getWaitingVehicles() { return waitingVehicles.get(); }
        public IntegerProperty waitingVehiclesProperty() { return waitingVehicles; }
        public void setWaitingVehicles(int count) { this.waitingVehicles.set(count); }
    }
    // UI components
    private TableView<IntersectionStatusItem> trafficTable;
    private Label totalVehiclesLabel;
    private TextArea alertsTextArea;
    private ListView<String> servicesList;
    private Label trafficServiceStatusLabel;
    private Label noiseServiceStatusLabel;
    private Label registryServiceStatusLabel;
    private ComboBox<String> intersectionSelector;
    
    // gRPC components
    private ManagedChannel trafficChannel;
    private TrafficGrpc.TrafficStub trafficStub;
    private ManagedChannel noiseChannel;
    private NoiseGrpc.NoiseStub noiseStub;
    private ManagedChannel registryChannel;
    private RegistryGrpc.RegistryStub registryStub;
    // Service connection status
    private final BooleanProperty trafficConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty noiseConnected = new SimpleBooleanProperty(false);
    private final BooleanProperty registryConnected = new SimpleBooleanProperty(false);
    private final AtomicBoolean dataStreamsStarted = new AtomicBoolean(false);
    
    // Thread management
    private Thread trafficSummaryThread;
    private Thread noiseMonitoringThread;
    private volatile boolean threadsShouldStop = false;
    private volatile StreamObserver<NoiseData> noiseStreamObserver;
    private volatile StreamObserver<TrafficData> trafficStreamObserver;
    private final ObservableList<IntersectionStatusItem> intersectionData = FXCollections.observableArrayList();
    private final Map<String, IntersectionStatusItem> intersectionMap = new HashMap<>();
    private final AtomicInteger totalVehicles = new AtomicInteger(0);
    
    // Connection state tracking
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Smart City Dashboard");
        
        // Initialize UI components
        initUI();
        
        // Set up the main layout
        VBox mainLayout = createMainLayout();
        
        // Create scene and show the stage
        Scene scene = new Scene(mainLayout, 900, 600);
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Set up listeners for service connection status
        setupConnectionListeners();
        
        // Connect to services
        connectToServices().thenRun(() -> {
            // Start streaming data once services are connected
            if (!dataStreamsStarted.getAndSet(true)) {
                startDataStreams();
            }
        });
    }
    
    private void initUI() {
        // Initialize traffic table
        trafficTable = new TableView<>();
        
        TableColumn<IntersectionStatusItem, String> idColumn = new TableColumn<>("Intersection ID");
        idColumn.setCellValueFactory(new PropertyValueFactory<>("intersectionId"));
        
        TableColumn<IntersectionStatusItem, String> colorColumn = new TableColumn<>("Light Color");
        colorColumn.setCellValueFactory(new PropertyValueFactory<>("lightColor"));
        colorColumn.setCellFactory(column -> new TableCell<IntersectionStatusItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item.toUpperCase()) {
                        case "RED":
                            setStyle("-fx-background-color: #ffcccc;");
                            break;
                        case "GREEN":
                            setStyle("-fx-background-color: #ccffcc;");
                            break;
                        case "YELLOW":
                            setStyle("-fx-background-color: #ffffcc;");
                            break;
                        default:
                            setStyle("");
                    }
                }
            }
        });
        
        TableColumn<IntersectionStatusItem, Number> vehiclesColumn = new TableColumn<>("Waiting Vehicles");
        vehiclesColumn.setCellValueFactory(new PropertyValueFactory<>("waitingVehicles"));
        
        trafficTable.getColumns().addAll(idColumn, colorColumn, vehiclesColumn);
        trafficTable.setItems(intersectionData);
        
        // Initialize other UI components
        totalVehiclesLabel = new Label("Total Vehicles: 0");
        totalVehiclesLabel.setFont(Font.font(16));
        
        alertsTextArea = new TextArea();
        alertsTextArea.setEditable(false);
        alertsTextArea.setWrapText(true);
        
        servicesList = new ListView<>();
        
        // Initialize the intersection selector
        intersectionSelector = new ComboBox<>();
        
        // Initialize service status labels
        trafficServiceStatusLabel = new Label("Traffic Service: Disconnected");
        trafficServiceStatusLabel.setTextFill(Color.RED);
        
        noiseServiceStatusLabel = new Label("Noise Service: Disconnected");
        noiseServiceStatusLabel.setTextFill(Color.RED);
        
        registryServiceStatusLabel = new Label("Registry Service: Disconnected");
        registryServiceStatusLabel.setTextFill(Color.RED);
    }
    
    private VBox createMainLayout() {
        // Create header
        Label titleLabel = new Label("Smart City Dashboard");
        titleLabel.setFont(Font.font(24));
        titleLabel.setPadding(new Insets(10, 0, 10, 0));
        
        // Create traffic section
        VBox trafficSection = new VBox(10);
        Label trafficLabel = new Label("Traffic Management");
        trafficLabel.setFont(Font.font(18));
        
        HBox trafficControls = new HBox(10);
        // Use the class field intersectionSelector that was initialized in initUI()
        ComboBox<String> colorSelector = new ComboBox<>(
                FXCollections.observableArrayList("RED", "YELLOW", "GREEN"));
        Button setLightButton = new Button("Set Light");
        
        setLightButton.setOnAction(e -> {
            String selectedIntersection = intersectionSelector.getValue();
            String selectedColor = colorSelector.getValue();
            if (selectedIntersection != null && selectedColor != null) {
                setTrafficLight(selectedIntersection, selectedColor);
            } else {
                addAlert("Please select both intersection and light color");
            }
        });
        
        trafficControls.getChildren().addAll(
                new Label("Intersection:"), 
                intersectionSelector, 
                new Label("Light:"), 
                colorSelector, 
                setLightButton
        );
        
        trafficSection.getChildren().addAll(
                trafficLabel, 
                trafficControls, 
                totalVehiclesLabel, 
                trafficTable
        );
        
        // Create alerts section
        VBox alertsSection = new VBox(10);
        Label alertsLabel = new Label("System Alerts");
        alertsLabel.setFont(Font.font(18));
        alertsSection.getChildren().addAll(alertsLabel, alertsTextArea);
        
        // Create services section
        VBox servicesSection = new VBox(10);
        Label servicesLabel = new Label("Available Services");
        servicesLabel.setFont(Font.font(18));
        
        // Service status indicators
        VBox statusBox = new VBox(5);
        statusBox.setPadding(new Insets(10, 0, 10, 0));
        statusBox.getChildren().addAll(
            registryServiceStatusLabel,
            trafficServiceStatusLabel,
            noiseServiceStatusLabel
        );
        
        servicesSection.getChildren().addAll(servicesLabel, statusBox, servicesList);
        // Create tab layout
        TabPane tabPane = new TabPane();
        
        Tab trafficTab = new Tab("Traffic", trafficSection);
        trafficTab.setClosable(false);
        
        Tab alertsTab = new Tab("Alerts", alertsSection);
        alertsTab.setClosable(false);
        
        Tab servicesTab = new Tab("Services", servicesSection);
        servicesTab.setClosable(false);
        
        tabPane.getTabs().addAll(trafficTab, alertsTab, servicesTab);
        
        // Create main layout
        VBox mainLayout = new VBox();
        mainLayout.getChildren().addAll(titleLabel, tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        
        // Set growth constraints for individual tabs
        VBox.setVgrow(trafficTable, Priority.ALWAYS);
        VBox.setVgrow(alertsTextArea, Priority.ALWAYS);
        VBox.setVgrow(servicesList, Priority.ALWAYS);
        
        mainLayout.setPadding(new Insets(10));
        return mainLayout;
    }
    
    private void setupConnectionListeners() {
        // Update UI when service connection status changes
        trafficConnected.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                if (newValue) {
                    trafficServiceStatusLabel.setText("Traffic Service: Connected");
                    trafficServiceStatusLabel.setTextFill(Color.GREEN);
                } else {
                    trafficServiceStatusLabel.setText("Traffic Service: Disconnected");
                    trafficServiceStatusLabel.setTextFill(Color.RED);
                }
            });
        });
        
        noiseConnected.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                if (newValue) {
                    noiseServiceStatusLabel.setText("Noise Service: Connected");
                    noiseServiceStatusLabel.setTextFill(Color.GREEN);
                } else {
                    noiseServiceStatusLabel.setText("Noise Service: Disconnected");
                    noiseServiceStatusLabel.setTextFill(Color.RED);
                }
            });
        });
        
        registryConnected.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(() -> {
                if (newValue) {
                    registryServiceStatusLabel.setText("Registry Service: Connected");
                    registryServiceStatusLabel.setTextFill(Color.GREEN);
                } else {
                    registryServiceStatusLabel.setText("Registry Service: Disconnected");
                    registryServiceStatusLabel.setTextFill(Color.RED);
                }
            });
        });
    }
    
    private CompletableFuture<Void> connectToServices() {
        // Prevent multiple concurrent connection attempts
        if (!isConnecting.compareAndSet(false, true)) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            result.complete(null);
            return result;
        }
        
        CompletableFuture<Void> registryFuture = new CompletableFuture<>();
        CompletableFuture<Void> trafficFuture = new CompletableFuture<>();
        CompletableFuture<Void> noiseFuture = new CompletableFuture<>();
        
        // Connect to Registry service
        try {
            registryChannel = ManagedChannelBuilder.forAddress("localhost", 50051)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .build();
            registryStub = RegistryGrpc.newStub(registryChannel);
            
            // Test registry connection with a ping request
            registryStub.discoverServices(
                    ServiceFilter.newBuilder().build(),
                    new StreamObserver<ServiceInfo>() {
                        @Override
                        public void onNext(ServiceInfo serviceInfo) {
                            // Registry is working if we can get any service info
                            if (!registryConnected.get()) {
                                Platform.runLater(() -> {
                                    addAlert("Connected to Registry service");
                                    registryConnected.set(true);
                                    registryFuture.complete(null);
                                });
                            }
                        }
                        
                        @Override
                        public void onError(Throwable t) {
                            Platform.runLater(() -> {
                                addAlert("Error connecting to Registry service: " + t.getMessage());
                                registryFuture.completeExceptionally(t);
                                scheduleServiceRetry("registry");
                            });
                        }
                        
                        @Override
                        public void onCompleted() {
                            // If we never got any services, complete the future anyway
                            if (!registryFuture.isDone()) {
                                Platform.runLater(() -> {
                                    registryConnected.set(true);
                                    registryFuture.complete(null);
                                });
                            }
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        addAlert("Failed to connect to Registry service: " + e.getMessage());
                        registryFuture.completeExceptionally(e);
                        scheduleServiceRetry("registry");
                    });
                }
                
                // Add timeout for registry connection
                new Thread(() -> {
                    try {
                        Thread.sleep(10000); // 10 second timeout
                        if (!registryFuture.isDone()) {
                            Platform.runLater(() -> {
                                addAlert("Registry service connection timed out");
                                registryFuture.completeExceptionally(new Exception("Connection timeout"));
                                scheduleServiceRetry("registry");
                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
                
                // Once registry is connected, discover and connect to services
                registryFuture.thenAccept(v -> {
                    // Discover Traffic service
                    discoverAndConnectTrafficService(trafficFuture);
                    
                    // Discover Noise service
                    discoverAndConnectNoiseService(noiseFuture);
                });
                
                return CompletableFuture.allOf(registryFuture, trafficFuture, noiseFuture)
                    .whenComplete((v, ex) -> {
                        // Reset connection state when all connections are complete (success or failure)
                        isConnecting.set(false);
                    });
            }
            
            private void discoverAndConnectTrafficService(CompletableFuture<Void> future) {
                registryStub.discoverServices(
                        ServiceFilter.newBuilder().setServiceType("traffic").build(),
                        new StreamObserver<ServiceInfo>() {
                            @Override
                            public void onNext(ServiceInfo serviceInfo) {
                                String[] addressParts = serviceInfo.getAddress().split(":");
                                String host = addressParts[0];
                                int port = Integer.parseInt(addressParts[1]);
                                
                                // Connect to Traffic service
                                trafficChannel = ManagedChannelBuilder.forAddress(host, port)
                                        .usePlaintext()
                                        .keepAliveTime(30, TimeUnit.SECONDS)
                                        .keepAliveTimeout(10, TimeUnit.SECONDS)
                                        .build();
                                trafficStub = TrafficGrpc.newStub(trafficChannel);
                                
                                Platform.runLater(() -> {
                                    addAlert("Connected to Traffic service at " + serviceInfo.getAddress());
                                    servicesList.getItems().add("Traffic: " + serviceInfo.getAddress());
                                    trafficConnected.set(true);
                                    future.complete(null);
                                });
                            }
                            
                            @Override
                            public void onError(Throwable t) {
                                Platform.runLater(() -> {
                                    addAlert("Error discovering Traffic service: " + t.getMessage());
                                    future.completeExceptionally(t);
                                });
                            }
                            
                            @Override
                            public void onCompleted() {
                                if (!future.isDone()) {
                                    Platform.runLater(() -> {
                                        addAlert("No Traffic service found");
                                        future.complete(null);
                                    });
                                }
                            }
                        });
            }
            
            private void discoverAndConnectNoiseService(CompletableFuture<Void> future) {
                registryStub.discoverServices(
                        ServiceFilter.newBuilder().setServiceType("noise").build(),
                        new StreamObserver<ServiceInfo>() {
                            @Override
                            public void onNext(ServiceInfo serviceInfo) {
                                String[] addressParts = serviceInfo.getAddress().split(":");
                                String host = addressParts[0];
                                int port = Integer.parseInt(addressParts[1]);
                                
                                // Connect to Noise service
                                noiseChannel = ManagedChannelBuilder.forAddress(host, port)
                                        .usePlaintext()
                                        .keepAliveTime(30, TimeUnit.SECONDS)
                                        .keepAliveTimeout(10, TimeUnit.SECONDS)
                                        .build();
                                noiseStub = NoiseGrpc.newStub(noiseChannel);
                                
                                Platform.runLater(() -> {
                                    addAlert("Connected to Noise service at " + serviceInfo.getAddress());
                                    servicesList.getItems().add("Noise: " + serviceInfo.getAddress());
                                    noiseConnected.set(true);
                                    future.complete(null);
                                });
                            }
                            
                            @Override
                            public void onError(Throwable t) {
                                Platform.runLater(() -> {
                                    addAlert("Error discovering Noise service: " + t.getMessage());
                                    future.completeExceptionally(t);
                                });
                            }
                            
                            @Override
                            public void onCompleted() {
                                if (!future.isDone()) {
                                    Platform.runLater(() -> {
                                        addAlert("No Noise service found");
                                        future.complete(null);
                                    });
                                }
                            }
                        });
            }
            
            private void scheduleServiceRetry(String serviceType) {
                // Reset connection flag to allow retry
                isConnecting.set(false);
                
                new Thread(() -> {
                    try {
                        Thread.sleep(5000); // Wait 5 seconds before retry
                        Platform.runLater(() -> addAlert("Retrying connection to " + serviceType + " service..."));
                        connectToServices();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
    
    private void startDataStreams() {
        // Reset thread state if restarting
        threadsShouldStop = false;
        
        // Wait a bit for services to be discovered
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Give time for service discovery
                
                // Start traffic data streaming - only needs to be initialized once
                if (trafficStub != null && trafficConnected.get()) {
                    startTrafficDataStream();
                    startTrafficSummaryPolling();
                } else {
                    Platform.runLater(() -> addAlert("Traffic service not available. Please restart the application."));
                }
                
                // Start noise monitoring if service is available
                if (noiseStub != null && noiseConnected.get()) {
                    startNoiseMonitoring();
                } else {
                    Platform.runLater(() -> addAlert("Noise service not available. Some features will be disabled."));
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private void startTrafficDataStream() {
        // Start continuous traffic data stream
        StreamObserver<TrafficData> observer = new StreamObserver<TrafficData>() {
            @Override
            public void onNext(TrafficData data) {
                Platform.runLater(() -> updateTrafficData(data));
            }
            
            @Override
            public void onError(Throwable t) {
                Platform.runLater(() -> {
                    addAlert("Error streaming traffic data: " + t.getMessage());
                    // Attempt to reconnect after a delay
                    if (!threadsShouldStop) {
                        new Thread(() -> {
                            try {
                                Thread.sleep(5000);
                                if (!threadsShouldStop) {
                                    Platform.runLater(() -> addAlert("Reconnecting to traffic data stream..."));
                                    startTrafficDataStream();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                });
            }
            
            @Override
            public void onCompleted() {
                Platform.runLater(() -> addAlert("Traffic stream completed"));
            }
        };
        
        // Store the observer reference for proper cleanup during shutdown
        this.trafficStreamObserver = observer;
        
        // Start the stream
        trafficStub.streamTraffic(Empty.newBuilder().build(), observer);
    }
    
    private void startTrafficSummaryPolling() {
        // Stop existing thread if running
        if (trafficSummaryThread != null && trafficSummaryThread.isAlive()) {
            trafficSummaryThread.interrupt();
        }
        
        // Create new thread for periodic traffic summary polling
        trafficSummaryThread = new Thread(() -> {
            while (!threadsShouldStop) {
                try {
                    // Check if traffic service is still available
                    if (trafficStub == null || !trafficConnected.get()) {
                        // Service disconnected, try to reconnect
                        Platform.runLater(() -> addAlert("Traffic service disconnected, attempting to reconnect..."));
                        break;
                    }
                    
                    // Request traffic summary periodically
                    trafficStub.getTrafficSummary(Empty.newBuilder().build(), new StreamObserver<TrafficSummary>() {
                        public void onNext(TrafficSummary summary) {
                            Platform.runLater(() -> updateTrafficSummary(summary));
                        }
                        
                        @Override
                        public void onError(Throwable t) {
                            Platform.runLater(() -> addAlert("Error getting traffic summary: " + t.getMessage()));
                        }
                        
                        @Override
                        public void onCompleted() {
                            // Completed getting traffic summary
                        }
                    });
                    
                    Thread.sleep(10000); // Update summary every 10 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Platform.runLater(() -> addAlert("Error in traffic summary: " + e.getMessage()));
                    try {
                        Thread.sleep(5000); // Wait 5 seconds before retry on error
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        
        trafficSummaryThread.setDaemon(true);
        trafficSummaryThread.start();
    }
    
    private void startNoiseMonitoring() {
        // Stop existing thread if running
        if (noiseMonitoringThread != null && noiseMonitoringThread.isAlive()) {
            noiseMonitoringThread.interrupt();
        }
        
        // Create thread to handle noise sensor stream
        noiseMonitoringThread = new Thread(() -> {
            // Check if noise service is still available
            if (noiseStub == null || !noiseConnected.get()) {
                Platform.runLater(() -> addAlert("Noise service unavailable, cannot start monitoring"));
                return;
            }
            
            // Create a stream observer for noise monitoring
            StreamObserver<NoiseData> observer = noiseStub.monitorNoise(
                new StreamObserver<Alert>() {
                    @Override
                    public void onNext(Alert alert) {
                        Platform.runLater(() -> {
                            String messageType = alert.getIsCritical() ? "CRITICAL NOISE ALERT" : "Noise Alert";
                            addAlert(messageType + ": " + alert.getMessage());
                        });
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        Platform.runLater(() -> {
                            addAlert("Error in noise monitoring: " + t.getMessage());
                            // Attempt to reconnect after delay
                            if (!threadsShouldStop) {
                                try {
                                    Thread.sleep(5000);
                                    if (!threadsShouldStop) {
                                        Platform.runLater(() -> addAlert("Reconnecting to noise monitoring..."));
                                        startNoiseMonitoring();
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
                    }
                    
                    @Override
                    public void onCompleted() {
                        Platform.runLater(() -> addAlert("Noise monitoring completed"));
                    }
                }
            );
            
            // Store reference for proper cleanup
            synchronized (this) {
                this.noiseStreamObserver = observer;
            }
            
            // Simulate noise data for demonstration purposes
            while (!threadsShouldStop) {
                try {
                    // Create simulated noise data
                    String[] sensorIds = {"residential-1", "downtown-1", "industrial-1"};
                    for (String sensorId : sensorIds) {
                        // Random noise between 40-90 dB
                        float noiseLevel = 40 + (float)(Math.random() * 50);
                        
                        NoiseData data = NoiseData.newBuilder()
                            .setSensorId(sensorId)
                            .setDecibels(noiseLevel)
                            .build();
                        
                        // Send to server
                        noiseStreamObserver.onNext(data);
                        
                        // Small delay between sensors
                        Thread.sleep(1000);
                    }
                    
                    // Wait before next batch of readings
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Platform.runLater(() -> addAlert("Error sending noise data: " + e.getMessage()));
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            // Close the stream when done
            try {
                synchronized (this) {
                    if (noiseStreamObserver != null) {
                        noiseStreamObserver.onCompleted();
                    }
                }
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        });
        
        noiseMonitoringThread.setDaemon(true);
        noiseMonitoringThread.start();
    
    private synchronized void updateTrafficSummary(TrafficSummary summary) {
        if (totalVehicles.get() == 0) {
            totalVehicles.set(summary.getTotalVehicles());
            totalVehiclesLabel.setText("Total Vehicles: " + totalVehicles.get());
        }
        
        // Create a set of new intersection IDs to track new additions
        boolean intersectionsAdded = false;
        
        for (IntersectionStatus status : summary.getIntersectionsList()) {
            String id = status.getIntersectionId();
            IntersectionStatusItem item = intersectionMap.get(id);
            
            if (item == null) {
                item = new IntersectionStatusItem(
                        id, 
                        status.getCurrentColor(), 
                        status.getWaitingVehicles()
                );
                intersectionMap.put(id, item);
                intersectionData.add(item);
                intersectionsAdded = true;
            } else {
                item.setLightColor(status.getCurrentColor());
                item.setWaitingVehicles(status.getWaitingVehicles());
            }
        }
        // Update the intersection selector combobox if new intersections were added
        if (intersectionsAdded) {
            // Get the current selection so we can preserve it
            String currentSelection = intersectionSelector.getValue();
            
            // Clear and repopulate the combo box
            intersectionSelector.getItems().clear();
            for (String id : intersectionMap.keySet()) {
                intersectionSelector.getItems().add(id);
            }
            
            // Restore previous selection if possible
            if (currentSelection != null && intersectionSelector.getItems().contains(currentSelection)) {
                intersectionSelector.setValue(currentSelection);
            } else if (!intersectionSelector.getItems().isEmpty()) {
                // Otherwise select the first item
                intersectionSelector.setValue(intersectionSelector.getItems().get(0));
            }
        }
    }
    private synchronized void updateTrafficData(TrafficData data) {
        int vehicleCount = data.getVehicleCount();
        int current = totalVehicles.addAndGet(vehicleCount);
        totalVehiclesLabel.setText("Total Vehicles: " + current);
        
        // Only log alerts for significant traffic changes to avoid spamming
        if (vehicleCount > 20) {
            addAlert("Traffic update: " + vehicleCount + " new vehicles at " + data.getTimestamp());
        }
    }
    
    
    private void setTrafficLight(String intersection, String color) {
        if (trafficStub != null && trafficConnected.get()) {
            LightCommand command = LightCommand.newBuilder()
                    .setIntersection(intersection)
                    .setColor(color)
                    .build();
            
            trafficStub.setLight(command, new StreamObserver<Response>() {
                @Override
                public void onNext(Response response) {
                    Platform.runLater(() -> addAlert("Light set: " + response.getStatus()));
                }
                
                @Override
                public void onError(Throwable t) {
                    Platform.runLater(() -> addAlert("Error setting light: " + t.getMessage()));
                }
                
                @Override
                public void onCompleted() {
                    // Completed setting light
                }
            });
        } else {
            addAlert("Cannot set light - not connected to Traffic service");
        }
    }
    
    private void addAlert(String message) {
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME);
        alertsTextArea.appendText("[" + timestamp + "] " + message + "\n");
    }
    
    @Override
    public void stop() {
        // Signal threads to stop
        threadsShouldStop = true;
        
        // Clean up stream observers first
        try {
            synchronized (this) {
                if (noiseStreamObserver != null) {
                    try {
                        noiseStreamObserver.onCompleted();
                    } catch (Exception e) {
                        // Ignore errors during shutdown
                    }
                    noiseStreamObserver = null;
                }
                
                if (trafficStreamObserver != null) {
                    try {
                        // No completion needed for traffic as it's server-to-client only
                    } catch (Exception e) {
                        // Ignore errors during shutdown
                    }
                    trafficStreamObserver = null;
                }
            }
        } catch (Exception e) {
            // Ensure we continue shutdown even if stream cleanup fails
        }
        
        // Stop the background threads
        if (trafficSummaryThread != null) {
            trafficSummaryThread.interrupt();
        }
        
        if (noiseMonitoringThread != null) {
            noiseMonitoringThread.interrupt();
        }
        
        // Clean shutdown of gRPC channels
        if (trafficChannel != null) {
            try {
                trafficChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (noiseChannel != null) {
            try {
                noiseChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (registryChannel != null) {
            try {
                registryChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
