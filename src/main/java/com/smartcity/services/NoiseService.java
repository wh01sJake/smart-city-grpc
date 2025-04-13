package com.smartcity.services;

import com.smartcity.*;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NoiseService extends NoiseGrpc.NoiseImplBase {
    private static final Logger logger = LogManager.getLogger(NoiseService.class);
    private static final float DEFAULT_DAY_THRESHOLD = 70.0f;
    private static final float DEFAULT_NIGHT_THRESHOLD = 55.0f;
    private final Map<String, NoiseThreshold> zoneThresholds = new ConcurrentHashMap<>();
    private final String serviceAddress;

    public NoiseService(String serviceAddress) {
        this.serviceAddress = serviceAddress;
        if (!RegistryService.selfRegister("noise", serviceAddress)) {
            logger.warn("Failed to register noise service at {}", serviceAddress);
        }
    }
    
    // Default constructor for backward compatibility
    public NoiseService() {
        this("localhost:50054");
    }

    @Override
    public StreamObserver<NoiseData> monitorNoise(StreamObserver<Alert> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(NoiseData data) {
                float threshold = getCurrentThreshold(data.getSensorId());
                if (data.getDecibels() > threshold) {
                    boolean isCritical = data.getDecibels() > threshold + 10;
                    String message = String.format("Noise level %.1f dB at sensor %s %s", 
                            data.getDecibels(), 
                            data.getSensorId(),
                            isCritical ? "(CRITICAL)" : "");

                    Alert alert = Alert.newBuilder()
                            .setMessage(message)
                            .setIsCritical(isCritical)
                            .build();
                    
                    responseObserver.onNext(alert);
                    logger.warn(message);
                }
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                logger.info("Noise monitoring completed");
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Error in noise monitoring", t);
                responseObserver.onError(t);
            }
        };
    }

    @Override
    public void setZoneThreshold(NoiseThreshold request, StreamObserver<Response> responseObserver) {
        zoneThresholds.put(request.getZoneId(), request);
        logger.info("Set noise thresholds for zone {}: day={}, night={}", 
                request.getZoneId(), 
                request.getDayLimit(), 
                request.getNightLimit());
        
        responseObserver.onNext(Response.newBuilder()
                .setStatus("Threshold updated successfully")
                .build());
        responseObserver.onCompleted();
    }

    private float getCurrentThreshold(String sensorId) {
        NoiseThreshold threshold = zoneThresholds.get(sensorId);
        if (threshold == null) {
            return isNightTime() ? DEFAULT_NIGHT_THRESHOLD : DEFAULT_DAY_THRESHOLD;
        }
        return isNightTime() ? threshold.getNightLimit() : threshold.getDayLimit();
    }

    private boolean isNightTime() {
        int hour = LocalDateTime.now().getHour();
        return hour >= 22 || hour < 6;
    }
}
