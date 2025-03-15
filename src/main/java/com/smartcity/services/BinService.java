package com.smartcity.services;

import com.smartcity.BinStatus;
import com.smartcity.Summary;
import com.smartcity.Zone;
import com.smartcity.Route;
import com.smartcity.BinGrpc;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class BinService extends BinGrpc.BinImplBase {
    private final ConcurrentHashMap<String, Integer> binStatusMap = new ConcurrentHashMap<>();

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
                System.out.println("Received status for bin " + status.getBinId()
                        + ": " + status.getFillPercent() + "% full");
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Error in bin reporting: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                float average = count > 0 ? (float) total / count : 0;
                responseObserver.onNext(Summary.newBuilder().setAverage(average).build());
                responseObserver.onCompleted();
                System.out.println("Bin reporting completed. Average fill: " + average + "%");
            }
        };
    }

    @Override
    public void getRoute(Zone request, StreamObserver<Route> responseObserver) {
        List<String> priorityBins = new ArrayList<>();

        // Simple logic: prioritize bins > 80% full
        binStatusMap.forEach((binId, fillPercent) -> {
            if(fillPercent > 80) {
                priorityBins.add(binId);
            }
        });

        responseObserver.onNext(Route.newBuilder()
                .addAllBins(priorityBins)
                .build());
        responseObserver.onCompleted();
        System.out.println("Generated route for zone " + request.getAreaId()
                + " with " + priorityBins.size() + " priority bins");
    }
}