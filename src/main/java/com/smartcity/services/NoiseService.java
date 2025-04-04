package com.smartcity.services;

import com.smartcity.NoiseData;
import com.smartcity.Alert;
import com.smartcity.NoiseGrpc;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;

public class NoiseService extends NoiseGrpc.NoiseImplBase {
    private static final Logger logger = LogManager.getLogger(NoiseService.class);
    private static final float NOISE_THRESHOLD = 70.0f, CRITICAL_THRESHOLD = 90.0f;

    public NoiseService() {
        RegistryService.selfRegister("noise", "localhost:50051");
    }

    @Override
    public StreamObserver<NoiseData> monitorNoise(StreamObserver<Alert> responseObserver) {

        return new StreamObserver<>() {
            @Override
            public void onNext(NoiseData data) {
                float decibels = data.getDecibels();
                if(decibels > NOISE_THRESHOLD) {
                    Alert alert = Alert.newBuilder()
                            .setMessage("Noise level exceeded: " + decibels + " dB")
                            .setIsCritical(decibels > CRITICAL_THRESHOLD)
                            .build();
                    responseObserver.onNext(alert);
                    logger.info("Alert sent for sensor {}", data.getSensorId());
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Noise monitoring error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                logger.info("Noise monitoring session ended");
            }
        };
    }
}