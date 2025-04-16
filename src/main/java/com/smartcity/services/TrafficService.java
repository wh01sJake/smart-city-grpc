package com.smartcity.services;

import com.smartcity.*;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Traffic Management Service implementation.
 * This service provides functionality for controlling traffic lights
 * and monitoring traffic flow throughout the city.
 *
 * Features:
 * - Traffic light control for intersections
 * - Real-time traffic data streaming
 * - Traffic summary statistics
 */
public class TrafficService extends TrafficGrpc.TrafficImplBase {
    private static final Logger logger = LogManager.getLogger(TrafficService.class);
    private final Map<String, IntersectionStatus> intersections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger totalVehicles = new AtomicInteger(0);

    public TrafficService() {
        RegistryService.selfRegister("traffic", "localhost:50051");
    }
    /**
     * Sets the traffic light color at a specific intersection.
     * This method allows clients to control traffic lights throughout the city.
     *
     * @param request Command specifying the intersection and desired light color
     * @param responseObserver Observer for sending the operation result
     */
    @Override
    public void setLight(LightCommand request, StreamObserver<Response> responseObserver) {
        String intersection = request.getIntersection();
        String color = request.getColor();

        IntersectionStatus status = IntersectionStatus.newBuilder()
                .setIntersectionId(intersection)
                .setCurrentColor(color)
                .setWaitingVehicles((int)(Math.random() * 20))
                .build();

        intersections.put(intersection, status);
        logger.info("Light at {} changed to {}", intersection, color);

        responseObserver.onNext(Response.newBuilder()
                .setStatus("Light changed successfully")
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Streams real-time traffic data to clients.
     * This method provides a continuous stream of traffic measurements,
     * sending updates every 5 seconds until the client disconnects.
     *
     * @param request Empty request (no parameters needed)
     * @param responseObserver Observer for streaming traffic data back to the client
     */
    @Override
    public void streamTraffic(Empty request, StreamObserver<TrafficData> responseObserver) {
        executor.scheduleAtFixedRate(() -> {
            try {
                int vehicleCount = (int)(Math.random() * 100);
                totalVehicles.addAndGet(vehicleCount);

                TrafficData data = TrafficData.newBuilder()
                        .setVehicleCount(vehicleCount)
                        .setTimestamp(java.time.Instant.now().toString())
                        .build();
                responseObserver.onNext(data);
                logger.info("Traffic data sent: {} vehicles", vehicleCount);
            } catch (Exception e) {
                logger.error("Error streaming traffic data", e);
                responseObserver.onError(e);
            }
        }, 0, 5, TimeUnit.SECONDS);
        logger.info("Started traffic data streaming");
    }

    /**
     * Provides a summary of current traffic conditions.
     * This method returns the total vehicle count and status of all intersections.
     *
     * @param request Empty request (no parameters needed)
     * @param responseObserver Observer for sending the traffic summary
     */
    @Override
    public void getTrafficSummary(Empty request, StreamObserver<TrafficSummary> responseObserver) {
        TrafficSummary.Builder summaryBuilder = TrafficSummary.newBuilder()
                .setTotalVehicles(totalVehicles.get());

        for (IntersectionStatus status : intersections.values()) {
            summaryBuilder.addIntersections(status);
        }

        responseObserver.onNext(summaryBuilder.build());
        responseObserver.onCompleted();
        logger.info("Traffic summary sent: {} total vehicles, {} intersections",
                totalVehicles.get(), intersections.size());
    }
}
