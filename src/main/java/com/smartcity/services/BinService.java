package com.smartcity.services;

import com.smartcity.*;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class BinService extends BinGrpc.BinImplBase {
    private static final Logger logger = LogManager.getLogger(BinService.class);
    private final Map<String, BinStatus> binStatuses = new ConcurrentHashMap<>();
    private static final int URGENT_THRESHOLD = 90;
    private static final int HIGH_THRESHOLD = 80;

    public BinService() {
        RegistryService.selfRegister("bin", "localhost:50051");
    }

    @Override
    public StreamObserver<BinStatus> reportBins(StreamObserver<Summary> responseObserver) {
        return new StreamObserver<>() {
            private int total = 0;
            private int count = 0;

            @Override
            public void onNext(BinStatus status) {
                binStatuses.put(status.getBinId(), status);
                total += status.getFillPercent();
                count++;
                
                if (status.getFillPercent() > URGENT_THRESHOLD) {
                    logger.warn("Urgent collection needed for bin: {} ({}%)", 
                            status.getBinId(), 
                            status.getFillPercent());
                }
            }

            @Override
            public void onCompleted() {
                float average = count > 0 ? (float) total / count : 0;
                responseObserver.onNext(Summary.newBuilder()
                        .setAverage(average)
                        .build());
                responseObserver.onCompleted();
                logger.info("Bin report completed: {} bins, average fill {}%", count, average);
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Error in bin reporting", t);
                responseObserver.onError(t);
            }
        };
    }

    @Override
    public void getRoute(Zone request, StreamObserver<Route> responseObserver) {
        ArrayList<String> priorityBins = new ArrayList<>();
        binStatuses.forEach((binId, status) -> {
            if (status.getFillPercent() > HIGH_THRESHOLD) {
                priorityBins.add(binId);
            }
        });
        
        Route route = Route.newBuilder()
                .addAllBins(priorityBins)
                .build();
        
        responseObserver.onNext(route);
        responseObserver.onCompleted();
        logger.info("Route generated for zone {} with {} priority bins", 
                request.getAreaId(), 
                priorityBins.size());
    }

    @Override
    public void getUrgentCollections(Empty request, StreamObserver<BinAlert> responseObserver) {
        binStatuses.forEach((binId, status) -> {
            if (status.getFillPercent() > URGENT_THRESHOLD) {
                BinAlert alert = BinAlert.newBuilder()
                        .setBinId(binId)
                        .setFillPercent(status.getFillPercent())
                        .setUrgentCollection(true)
                        .build();
                responseObserver.onNext(alert);
                logger.warn("Urgent collection alert: Bin {} at {}%", 
                        binId, 
                        status.getFillPercent());
            }
        });
        responseObserver.onCompleted();
    }
}
