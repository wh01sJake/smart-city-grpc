package com.smartcity.services;

import com.smartcity.Empty;
import com.smartcity.LightCommand;
import com.smartcity.Response;
import com.smartcity.TrafficData;
import com.smartcity.TrafficGrpc;

import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class TrafficService extends TrafficGrpc.TrafficImplBase {
    private static final Logger logger = LogManager.getLogger(TrafficService.class);


    //self-register upon initialization
    public TrafficService() {
        RegistryService.selfRegister("traffic", "localhost:50051");
    }

    // Simple RPC implementation
    @Override
    public void setLight(LightCommand request, StreamObserver<Response> responseObserver) {
        logger.info("Changing light at {} to {}", request.getIntersection(), request.getColor());
        responseObserver.onNext(Response.newBuilder().setStatus("OK").build());
        responseObserver.onCompleted();
    }

    // Server Streaming implementation
    @Override
    public void streamTraffic(Empty request, StreamObserver<TrafficData> responseObserver) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            TrafficData data = TrafficData.newBuilder()
                    .setVehicleCount((int)(Math.random() * 100))
                    .setTimestamp(java.time.Instant.now().toString())
                    .build();
            responseObserver.onNext(data);
        }, 0, 5, TimeUnit.SECONDS);
        logger.info("Started traffic data streaming");
    }
}