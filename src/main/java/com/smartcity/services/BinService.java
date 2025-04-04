package com.smartcity.services;

import com.smartcity.BinStatus;
import com.smartcity.Summary;
import com.smartcity.Zone;
import com.smartcity.Route;
import com.smartcity.BinGrpc;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class BinService extends BinGrpc.BinImplBase {
    private final ConcurrentHashMap<String, Integer> binStatusMap = new ConcurrentHashMap<>();
    private static final Logger logger = LogManager.getLogger(BinService.class);

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
                // Store bin status
                binStatusMap.put(status.getBinId(), status.getFillPercent());
                total += status.getFillPercent();
                count++;
                logger.info("Received status for bin {}: {}% full", status.getBinId(), status.getFillPercent());
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Bin reporting error: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                float avg = count > 0 ? (float) total / count : 0;
                responseObserver.onNext(Summary.newBuilder().setAverage(avg).build());
                responseObserver.onCompleted();
                logger.info("Bin reporting completed. Average: {}%", avg);
            }
        };
    }

    @Override
    public void getRoute(Zone request, StreamObserver<Route> responseObserver) {
        List<String> priorityBins = new ArrayList<>();
        binStatusMap.forEach((binId, fill) -> {
            if (fill > 80) priorityBins.add(binId);
        });
        responseObserver.onNext(Route.newBuilder().addAllBins(priorityBins).build());
        responseObserver.onCompleted();
        logger.info("Route generated for zone {}", request.getAreaId());
    }
}