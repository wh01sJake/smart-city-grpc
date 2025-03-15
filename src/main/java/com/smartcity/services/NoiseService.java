package com.smartcity.services;

import com.smartcity.NoiseData;
import com.smartcity.Alert;
import com.smartcity.NoiseGrpc;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;

public class NoiseService extends NoiseGrpc.NoiseImplBase {
    private static final float NOISE_THRESHOLD = 70.0f;
    private static final float CRITICAL_THRESHOLD = 90.0f;

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
                    System.out.println("Alert sent for sensor " + data.getSensorId());
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Noise monitoring error: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                System.out.println("Noise monitoring session ended");
            }
        };
    }
}