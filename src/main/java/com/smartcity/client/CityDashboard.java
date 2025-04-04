/**
 * Smart City Monitoring Dashboard - JavaFX Client Application
 * Provides four main functional modules:
 * 1. Traffic light control
 * 2. Bin status monitoring
 * 3. Noise level tracking
 * 4. Service discovery management
 */


package com.smartcity.client;
import com.smartcity.*;
import com.smartcity.Alert;
import io.grpc.*;
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
    // gRPC communication channel
    private ManagedChannel channel;
    // Service registry stub
    private RegistryGrpc.RegistryBlockingStub registryStub;
    // Observable list for service discovery UI
    private final ObservableList<String> serviceList = FXCollections.observableArrayList();

    /** Initialize gRPC communication channel */
    public CityDashboard() {
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .intercept(new ClientInterceptor() {
                    @Override
                    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                            MethodDescriptor<ReqT, RespT> method,
                            CallOptions callOptions,
                            Channel next) {
                        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                            @Override
                            public void start(Listener<RespT> responseListener, Metadata headers) {
                                headers.put(Metadata.Key.of("apikey", Metadata.ASCII_STRING_MARSHALLER), "smartcityjake");
                                super.start(responseListener, headers);
                            }
                        };
                    }
                })
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
        Button streamBtn = new Button("Start Traffic Stream");
        TextArea trafficLog = new TextArea();
        TextArea trafficStream = new TextArea();

        redBtn.setOnAction(e -> setTrafficLight("RED", trafficLog));
        greenBtn.setOnAction(e -> setTrafficLight("GREEN", trafficLog));
        streamBtn.setOnAction(e -> startTrafficStream(trafficStream));

        return new Tab("Traffic", new VBox(10, redBtn, greenBtn, streamBtn, trafficLog, trafficStream));
    }

    private void startTrafficStream(TextArea log) {
        TrafficGrpc.TrafficStub stub = TrafficGrpc.newStub(channel);
        stub.streamTraffic(Empty.newBuilder().build(), new StreamObserver<TrafficData>() {
            @Override
            public void onNext(TrafficData data) {
                Platform.runLater(() ->
                        log.appendText("Vehicles: " + data.getVehicleCount() + " at " + data.getTimestamp() + "\n")
                );
            }

            @Override
            public void onError(Throwable t) {
                Platform.runLater(() ->
                        log.appendText("Stream error: " + t.getMessage() + "\n")
                );
            }

            @Override
            public void onCompleted() {
                Platform.runLater(() ->
                        log.appendText("Traffic stream ended\n")
                );
            }
        });
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

    /**
     * Refresh service list (thread-safe implementation)
     * Workflow:
     * 1. Clear current list (FX thread)
     * 2. Fetch services asynchronously
     * 3. Update UI (FX thread)
     */

    private void refreshServices() {
        new Thread(() -> {
            try {
                Platform.runLater(() -> serviceList.clear());

                // Explicitly build an empty filter to get all services
                ServiceFilter filter = ServiceFilter.newBuilder().build();

                registryStub.discoverServices(filter)
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
            } catch (Exception e) {
                Platform.runLater(() ->
                        serviceList.add("General Error: " + e.getClass().getSimpleName())
                );
                e.printStackTrace();
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
