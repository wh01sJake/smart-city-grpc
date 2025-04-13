package com.smartcity;

import com.smartcity.services.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestClient {
    private static final Logger logger = LogManager.getLogger(TestClient.class);
    private final ManagedChannel channel;
    private final TrafficGrpc.TrafficBlockingStub trafficStub;
    private final BinGrpc.BinStub binStub;
    private final NoiseGrpc.NoiseStub noiseStub;
    private final RegistryGrpc.RegistryBlockingStub registryStub;

    public TestClient() {
        channel = ManagedChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .intercept(new ClientAuthInterceptor("smartcityjake"))
                .build();
        
        trafficStub = TrafficGrpc.newBlockingStub(channel);
        binStub = BinGrpc.newStub(channel);
        noiseStub = NoiseGrpc.newStub(channel);
        registryStub = RegistryGrpc.newBlockingStub(channel);
    }

    public void testServices() throws InterruptedException {
        testTrafficService();
        testBinService();
        testNoiseService();
        testServiceRegistry();
    }

    private void testTrafficService() {
        logger.info("Testing Traffic Service...");
        try {
            // Test setLight
            Response response = trafficStub.setLight(LightCommand.newBuilder()
                    .setIntersection("main_st")
                    .setColor("RED")
                    .build());
            logger.info("SetLight Response: {}", response.getStatus());

            // Test traffic stream
            trafficStub.streamTraffic(Empty.newBuilder().build())
                    .forEachRemaining(data -> 
                            logger.info("Traffic Data: {} vehicles at {}", 
                                    data.getVehicleCount(), 
                                    data.getTimestamp()));
        } catch (Exception e) {
            logger.error("Traffic Service Test Failed: {}", e.getMessage());
        }
    }

    private void testBinService() throws InterruptedException {
        logger.info("Testing Bin Service...");
        CountDownLatch binLatch = new CountDownLatch(1);

        // Test bin reporting
        StreamObserver<BinStatus> binObserver = binStub.reportBins(
                new StreamObserver<Summary>() {
                    @Override
                    public void onNext(Summary summary) {
                        logger.info("Bin Summary: Average fill {}%", summary.getAverage());
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("Bin Service Error: {}", t.getMessage());
                        binLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        logger.info("Bin reporting completed");
                        binLatch.countDown();
                    }
                });

        // Send test bin data
        for (int i = 1; i <= 3; i++) {
            binObserver.onNext(BinStatus.newBuilder()
                    .setBinId("bin_" + i)
                    .setFillPercent(85 + i * 5)
                    .build());
        }
        binObserver.onCompleted();
        binLatch.await(5, TimeUnit.SECONDS);
    }

    private void testNoiseService() throws InterruptedException {
        logger.info("Testing Noise Service...");
        CountDownLatch noiseLatch = new CountDownLatch(1);

        StreamObserver<NoiseData> noiseObserver = noiseStub.monitorNoise(
                new StreamObserver<Alert>() {
                    @Override
                    public void onNext(Alert alert) {
                        logger.info("Noise Alert: {}", alert.getMessage());
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("Noise Service Error: {}", t.getMessage());
                        noiseLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        logger.info("Noise monitoring completed");
                        noiseLatch.countDown();
                    }
                });

        // Send test noise data
        noiseObserver.onNext(NoiseData.newBuilder()
                .setSensorId("sensor_1")
                .setDecibels(75.0f)
                .build());
        noiseObserver.onCompleted();
        noiseLatch.await(5, TimeUnit.SECONDS);
    }

    private void testServiceRegistry() {
        logger.info("Testing Service Registry...");
        try {
            ServiceFilter filter = ServiceFilter.newBuilder().build();
            registryStub.discoverServices(filter)
                    .forEachRemaining(service -> 
                            logger.info("Discovered Service: {} at {}", 
                                    service.getServiceType(), 
                                    service.getAddress()));
        } catch (Exception e) {
            logger.error("Service Registry Test Failed: {}", e.getMessage());
        }
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws InterruptedException {
        TestClient client = new TestClient();
        try {
            client.testServices();
        } finally {
            client.shutdown();
        }
    }
}

