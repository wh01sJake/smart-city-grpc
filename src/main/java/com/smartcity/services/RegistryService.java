package com.smartcity.services;

import com.smartcity.ServiceInfo;
import com.smartcity.ServiceFilter;
import com.smartcity.Confirmation;
import com.smartcity.RegistryGrpc;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

public class RegistryService extends RegistryGrpc.RegistryImplBase {
    private final ConcurrentHashMap<String, ServiceInfo> services = new ConcurrentHashMap<>();
    private static RegistryService instance;
    private static final Logger logger = LogManager.getLogger(RegistryService.class);


    public RegistryService() {
        instance = this;
    }

    public int getRegisteredServicesCount() {
        return services.size();
    }

    public static boolean selfRegister(String serviceType, String address) {
        if (instance == null) {
            logger.error("Cannot self-register {} at {} - Registry instance is null", serviceType, address);
            return false;
        }
        
        try {
            ServiceInfo info = ServiceInfo.newBuilder()
                    .setServiceType(serviceType)
                    .setServiceId(serviceType + "-" + System.currentTimeMillis())
                    .setAddress(address)
                    .build();
            instance.services.put(info.getServiceType() + "@" + address, info);
            logger.info("[Self-Register] {} at {}", serviceType, address);
            return true;
        } catch (Exception e) {
            logger.error("Error during self-registration of {} at {}: {}", serviceType, address, e.getMessage());
            return false;
        }
    }

    @Override
    public void register(ServiceInfo request, StreamObserver<Confirmation> responseObserver) {
        String serviceKey = request.getServiceType() + "@" + request.getAddress();
        services.put(serviceKey, request);
        responseObserver.onNext(Confirmation.newBuilder().setStatus("Registered: " + serviceKey).build());
        responseObserver.onCompleted();
        logger.info("New service registered: {}", serviceKey);
    }

    @Override
    public void discoverServices(ServiceFilter filter, StreamObserver<ServiceInfo> responseObserver) {
        String filterType = filter.getServiceType();
        String logMessage = filterType.isEmpty() ?
                "Service discovery completed for all services" :
                "Service discovery completed for type: " + filterType;

        services.values().stream()
                .filter(service -> filterType.isEmpty() || service.getServiceType().equals(filterType))
                .forEach(responseObserver::onNext);

        responseObserver.onCompleted();
        logger.info(logMessage); // Updated log message
    }
}