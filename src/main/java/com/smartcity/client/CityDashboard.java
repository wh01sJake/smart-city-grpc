package com.smartcity.client;

import com.smartcity.*;
import com.smartcity.Alert;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.TimeUnit;


public class CityDashboard extends Application {
    private ManagedChannel channel;
    private RegistryGrpc.RegistryBlockingStub registryStub;
    private final ObservableList<String> serviceList = FXCollections.observableArrayList();

    public CityDashboard() {
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();
        registryStub = RegistryGrpc.newBlockingStub(channel);
    }

    @Override
    public void start(Stage stage) {
        TabPane tabs = new TabPane(
                createTrafficTab(),
                createBinsTab(),
                createNoiseTab(),
                createServicesTab()
        );

        stage.setScene(new Scene(tabs, 800, 600));
        stage.setTitle("Smart City Dashboard");
        stage.show();
        refreshServices();
    }

    // 1. Traffic Service Tab
    private Tab createTrafficTab() {
        Button redBtn = new Button("Set Red Light");
        Button greenBtn = new Button("Set Green Light");
        TextArea trafficLog = new TextArea();

        redBtn.setOnAction(e -> setTrafficLight("RED", trafficLog));
        greenBtn.setOnAction(e -> setTrafficLight("GREEN", trafficLog));

        return new Tab("Traffic", new VBox(10, redBtn, greenBtn, trafficLog));
    }

    private void setTrafficLight(String color, TextArea log) {
        try {
            TrafficGrpc.TrafficBlockingStub stub = TrafficGrpc.newBlockingStub(channel);
            Response response = stub.setLight(LightCommand.newBuilder()
                    .setIntersection("main_st")
                    .setColor(color)
                    .build());

            Platform.runLater(() ->
                    log.appendText("Light changed to " + color + ": " + response.getStatus() + "\n")
            );
        } catch (StatusRuntimeException e) {
            Platform.runLater(() ->
                    log.appendText("Error: " + e.getStatus().getDescription() + "\n")
            );
        }
    }

    // 2. Bin Service Tab
    private Tab createBinsTab() {
        Button reportBtn = new Button("Simulate Bin Report");
        TextArea binLog = new TextArea();

        reportBtn.setOnAction(e -> simulateBinReport(binLog));

        return new Tab("Bins", new VBox(10, reportBtn, binLog));
    }

    private void simulateBinReport(TextArea log) {
        BinGrpc.BinStub binStub = BinGrpc.newStub(channel);

        StreamObserver<BinStatus> requestObserver = binStub.reportBins(
                new StreamObserver<Summary>() {
                    @Override
                    public void onNext(Summary summary) {
                        Platform.runLater(() ->
                                log.appendText("Average fill: " + summary.getAverage() + "%\n")
                        );
                    }

                    @Override
                    public void onError(Throwable t) {
                        Platform.runLater(() ->
                                log.appendText("Error: " + t.getMessage() + "\n")
                        );
                    }

                    @Override
                    public void onCompleted() {
                        Platform.runLater(() ->
                                log.appendText("Report completed\n")
                        );
                    }
                }
        );

        // Simulate 5 bins
        new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    requestObserver.onNext(BinStatus.newBuilder()
                            .setBinId("bin_" + i)
                            .setFillPercent((int)(Math.random() * 100))
                            .build());
                    Thread.sleep(1000);
                }
                requestObserver.onCompleted();
            } catch (Exception e) {
                requestObserver.onError(e);
            }
        }).start();
    }

    // 3. Noise Service Tab
    private Tab createNoiseTab() {
        Button startMonitorBtn = new Button("Start Noise Monitor");
        TextArea noiseLog = new TextArea();

        startMonitorBtn.setOnAction(e -> startNoiseMonitoring(noiseLog));

        return new Tab("Noise", new VBox(10, startMonitorBtn, noiseLog));
    }

    private void startNoiseMonitoring(TextArea log) {
        NoiseGrpc.NoiseStub noiseStub = NoiseGrpc.newStub(channel);

        StreamObserver<NoiseData> requestObserver = noiseStub.monitorNoise(
                new StreamObserver<Alert>() {
                    @Override
                    public void onNext(Alert alert) {
                        Platform.runLater(() ->
                                log.appendText(alert.getMessage() +
                                        (alert.getIsCritical() ? " (CRITICAL)" : "") + "\n")
                        );
                    }

                    @Override
                    public void onError(Throwable t) {
                        Platform.runLater(() ->
                                log.appendText("Monitoring error: " + t.getMessage() + "\n")
                        );
                    }

                    @Override
                    public void onCompleted() {
                        Platform.runLater(() ->
                                log.appendText("Monitoring stopped\n")
                        );
                    }
                }
        );

        // Simulate noise data
        new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    requestObserver.onNext(NoiseData.newBuilder()
                            .setSensorId("sensor_1")
                            .setDecibels((float)(Math.random() * 40 + 60)) // 60-100 dB
                            .build());
                    Thread.sleep(1500);
                }
                requestObserver.onCompleted();
            } catch (Exception e) {
                requestObserver.onError(e);
            }
        }).start();
    }

    // Service Discovery Tab
    private Tab createServicesTab() {
        ListView<String> serviceListView = new ListView<>(serviceList);
        Button refreshBtn = new Button("Refresh Services");
        refreshBtn.setOnAction(e -> refreshServices());

        return new Tab("Services", new VBox(10, refreshBtn, serviceListView));
    }

    //
    private void refreshServices() {
        new Thread(() -> {
            try {
//                serviceList.clear(); // clear old data
                Platform.runLater(() -> serviceList.clear());

                registryStub.discoverServices(ServiceFilter.newBuilder().build())
                        .forEachRemaining(service ->

                                Platform.runLater(() ->
                                        serviceList.add(
                                                service.getServiceType() + " @ " + service.getAddress()
                                        )
                                )
                        );
            } catch (StatusRuntimeException e) {
                Platform.runLater(() ->
                        serviceList.add("gRPC Error: " + e.getStatus().getDescription())
                );
            } catch (Exception e) { // catch all exceptions
                Platform.runLater(() ->
                        serviceList.add("General Error: " + e.getClass().getSimpleName())
                );
                e.printStackTrace(); // print error
            }
        }).start();
    }

    @Override
    public void stop() throws Exception {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
