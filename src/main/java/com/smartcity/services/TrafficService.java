package com.smartcity.services;

import com.smartcity.*;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TrafficService extends TrafficGrpc.TrafficImplBase {
    private static final Logger logger = LogManager.getLogger(TrafficService.class);
    private final Map<String, IntersectionStatus> intersections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger totalVehicles = new AtomicInteger(0);

    public TrafficService() {
        RegistryService.selfRegister("traffic", "localhost:50051");
    }
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
