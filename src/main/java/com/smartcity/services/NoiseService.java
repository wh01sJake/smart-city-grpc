package com.smartcity.services;

import com.smartcity.*;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Noise Monitoring Service implementation.
 * This service monitors noise levels across different zones in the city
 * and generates alerts when thresholds are exceeded.
 *
 * Features:
 * - Real-time noise level monitoring
 * - Configurable thresholds for day and night periods
 * - Critical and non-critical alert classification
 * - Zone-specific noise management
 */
public class NoiseService extends NoiseGrpc.NoiseImplBase {
    private static final Logger logger = LogManager.getLogger(NoiseService.class);
    private final Map<String, NoiseThreshold> zoneThresholds = new ConcurrentHashMap<>();

    // Default thresholds if not set for a zone
    private static final float DEFAULT_DAY_LIMIT = 70.0f;   // 70 dB during day
    private static final float DEFAULT_NIGHT_LIMIT = 55.0f; // 55 dB during night
    private static final int DAY_START_HOUR = 7;
    private static final int NIGHT_START_HOUR = 22;

    public NoiseService() {
        // Register with the registry service
        RegistryService.selfRegister("noise", "localhost:50053");
        logger.info("NoiseService initialized and registered");
    }

    /**
     * Monitors noise levels and generates alerts when thresholds are exceeded.
     * This method implements bidirectional streaming, receiving noise measurements
     * from clients and sending back alerts when thresholds are exceeded.
     *
     * @param responseObserver Observer for sending alerts back to the client
     * @return StreamObserver for receiving noise data from the client
     */
    @Override
    public StreamObserver<NoiseData> monitorNoise(final StreamObserver<Alert> responseObserver) {
        logger.info("Starting new noise monitoring stream");

        return new StreamObserver<NoiseData>() {
            @Override
            public void onNext(NoiseData data) {
                try {
                    // Get the zone ID from the sensor ID (assuming format: zone_sensorNumber)
                    String zoneId = data.getSensorId().split("_")[0];

                    // Get threshold for the zone, or use default
                    NoiseThreshold threshold = zoneThresholds.getOrDefault(zoneId,
                            NoiseThreshold.newBuilder()
                                    .setDayLimit(DEFAULT_DAY_LIMIT)
                                    .setNightLimit(DEFAULT_NIGHT_LIMIT)
                                    .setZoneId(zoneId)
                                    .build());

                    // Determine if it's day or night
                    int currentHour = LocalTime.now().getHour();
                    boolean isDay = currentHour >= DAY_START_HOUR && currentHour < NIGHT_START_HOUR;
                    float currentLimit = isDay ? threshold.getDayLimit() : threshold.getNightLimit();

                    // Check if noise level exceeds threshold
                    if (data.getDecibels() > currentLimit) {
                        String timeContext = isDay ? "daytime" : "nighttime";
                        Alert alert = Alert.newBuilder()
                                .setMessage(String.format("High noise level detected in zone %s: %.1f dB (limit: %.1f dB, %s)",
                                        zoneId, data.getDecibels(), currentLimit, timeContext))
                                .setIsCritical(data.getDecibels() > currentLimit + 10) // Critical if exceeds by 10dB
                                .build();

                        responseObserver.onNext(alert);

                        if (alert.getIsCritical()) {
                            logger.warn("Critical noise level in zone {}: {} dB", zoneId, data.getDecibels());
                        } else {
                            logger.info("Noise threshold exceeded in zone {}: {} dB", zoneId, data.getDecibels());
                        }
                    }

                    // Log regular readings at debug level
                    logger.debug("Noise reading from sensor {}: {} dB", data.getSensorId(), data.getDecibels());

                } catch (Exception e) {
                    logger.error("Error processing noise data", e);
                    responseObserver.onError(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Error in noise monitoring stream", t);
                // Don't close the stream on error, let the client decide
            }

            @Override
            public void onCompleted() {
                try {
                    responseObserver.onCompleted();
                    logger.info("Noise monitoring stream completed");
                } catch (Exception e) {
                    logger.error("Error completing noise monitoring stream", e);
                    responseObserver.onError(e);
                }
            }
        };
    }

    /**
     * Sets noise thresholds for a specific zone.
     * This method configures the maximum allowed noise levels for day and night periods.
     * It performs validation to ensure thresholds are reasonable and consistent.
     *
     * @param request Threshold configuration with zone ID, day limit, and night limit
     * @param responseObserver Observer for sending the operation result
     */
    @Override
    public void setZoneThreshold(NoiseThreshold request, StreamObserver<Response> responseObserver) {
        try {
            // Validate thresholds
            if (request.getDayLimit() < 0 || request.getNightLimit() < 0) {
                throw new IllegalArgumentException("Noise thresholds cannot be negative");
            }
            if (request.getNightLimit() > request.getDayLimit()) {
                throw new IllegalArgumentException("Night threshold cannot be higher than day threshold");
            }
            if (request.getDayLimit() > 100 || request.getNightLimit() > 100) {
                throw new IllegalArgumentException("Noise thresholds cannot exceed 100 dB");
            }

            // Store the threshold
            zoneThresholds.put(request.getZoneId(), request);

            logger.info("Updated noise thresholds for zone {}: day={} dB, night={} dB",
                    request.getZoneId(), request.getDayLimit(), request.getNightLimit());

            Response response = Response.newBuilder()
                    .setStatus(String.format("Thresholds updated for zone %s", request.getZoneId()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid threshold settings: {}", e.getMessage());
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            logger.error("Error setting zone threshold for zone: " + request.getZoneId(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Internal server error: " + e.getMessage())
                .asRuntimeException());
        }
    }
}
