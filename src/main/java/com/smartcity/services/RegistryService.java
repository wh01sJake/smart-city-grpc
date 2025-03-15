package com.smartcity.services;

import com.smartcity.ServiceInfo;
import com.smartcity.ServiceFilter;
import com.smartcity.Confirmation;
import com.smartcity.RegistryGrpc;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ConcurrentHashMap;

public class RegistryService extends RegistryGrpc.RegistryImplBase {
    private final ConcurrentHashMap<String, ServiceInfo> services = new ConcurrentHashMap<>();
    private static RegistryService instance;

    public RegistryService() {
        instance = this;
    }

    public int getRegisteredServicesCount() {
        return services.size();
    }

    public static void selfRegister(String serviceType, String address) {
        if (instance != null) {
            ServiceInfo info = ServiceInfo.newBuilder()
                    .setServiceType(serviceType)
                    .setAddress(address)
                    .build();
            instance.services.put(info.getServiceType() + "@" + address, info);
            System.out.println("[Self-Register] " + serviceType + " at " + address);
        }
    }

    @Override
    public void register(ServiceInfo request, StreamObserver<Confirmation> responseObserver) {
        String serviceKey = request.getServiceType() + "@" + request.getAddress();
        services.put(serviceKey, request);

        responseObserver.onNext(Confirmation.newBuilder()
                .setStatus("Service registered: " + serviceKey)
                .build());
        responseObserver.onCompleted();
        System.out.println("New service registered: " + serviceKey);
    }

    @Override
    public void discoverServices(ServiceFilter filter, StreamObserver<ServiceInfo> responseObserver) {
        services.values().stream()
                .filter(service ->
                        filter.getServiceType().isEmpty() ||
                                service.getServiceType().equals(filter.getServiceType()))
                .forEach(responseObserver::onNext);

        responseObserver.onCompleted();
        System.out.println("Service discovery request completed for type: "
                + filter.getServiceType());
    }
}