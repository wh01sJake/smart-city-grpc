package com.smartcity.services;

import com.smartcity.Empty;
import com.smartcity.LightCommand;
import com.smartcity.Response;
import com.smartcity.TrafficData;
import com.smartcity.TrafficGrpc;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TrafficService extends TrafficGrpc.TrafficImplBase {

    // Simple RPC implementation
    @Override
    public void setLight(LightCommand request,
                         StreamObserver<Response> responseObserver) {
        System.out.println("Changing light at " + request.getIntersection()
                + " to " + request.getColor());
        responseObserver.onNext(Response.newBuilder()
                .setStatus("OK")
                .build());
        responseObserver.onCompleted();
    }

    // Server Streaming implementation
    @Override
    public void streamTraffic(Empty request,
                              StreamObserver<TrafficData> responseObserver) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            TrafficData data = TrafficData.newBuilder()
                    .setVehicleCount((int)(Math.random() * 100))
                    .setTimestamp(java.time.Instant.now().toString())
                    .build();
            responseObserver.onNext(data);
        }, 0, 5, TimeUnit.SECONDS);
    }
}