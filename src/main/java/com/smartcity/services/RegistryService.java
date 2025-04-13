package com.smartcity.services;

import com.smartcity.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RegistryService extends RegistryGrpc.RegistryImplBase {
    private static final Logger logger = LogManager.getLogger(RegistryService.class);
    private static final String REGISTRY_ADDRESS = "localhost:50050";
    private static final long SERVICE_TIMEOUT_SECONDS = 30;
    private static final long CLEANUP_INTERVAL_SECONDS = 15;

    private static class ServiceEntry {
        final ServiceInfo info;
        volatile Instant lastHeartbeat;

        ServiceEntry(ServiceInfo info) {
            this.info = info;
            this.lastHeartbeat = Instant.now();
        }

        void updateHeartbeat() {
            this.lastHeartbeat = Instant.now();
        }

        boolean isStale() {
            return Instant.now().minusSeconds(SERVICE_TIMEOUT_SECONDS).isAfter(lastHeartbeat);
        }
    }

    private final Map<String, ServiceEntry> services = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    
    // Static registry instance for self-registration
    private static volatile RegistryService instance;

    public RegistryService() {
        instance = this;
        startCleanupTask();
        logger.info("Registry Service initialized at {}", REGISTRY_ADDRESS);
    }

    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            if (!isRunning.get()) {
                return;
            }
            try {
                cleanupStaleServices();
            } catch (Exception e) {
                logger.error("Error during service cleanup", e);
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void cleanupStaleServices() {
        services.entrySet().removeIf(entry -> {
            if (entry.getValue().isStale()) {
                logger.warn("Removing stale service: {} at {}", 
                    entry.getValue().info.getServiceType(),
                    entry.getValue().info.getAddress());
                return true;
            }
            return false;
        });
    }

    @Override
    public void register(ServiceInfo request, StreamObserver<Confirmation> responseObserver) {
        try {
            // Validate request
            if (request.getServiceType().isEmpty() || request.getServiceId().isEmpty() || request.getAddress().isEmpty()) {
                throw new IllegalArgumentException("Service type, ID, and address are required");
            }

            // Create or update service entry
            ServiceEntry entry = new ServiceEntry(request);
            String serviceKey = generateServiceKey(request);
            ServiceEntry existing = services.put(serviceKey, entry);

            String status = existing == null ? 
                "Service registered successfully" : 
                "Service registration updated";

            logger.info("{}: {} at {}", status, request.getServiceType(), request.getAddress());

            responseObserver.onNext(Confirmation.newBuilder()
                .setStatus(status)
                .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            logger.error("Error registering service: {}", request.getServiceId(), e);
            responseObserver.onError(e);
        }
    }

    private String generateServiceKey(ServiceInfo info) {
        return String.format("%s@%s", info.getServiceType(), info.getAddress());
    }

    @Override
    public void discoverServices(ServiceFilter request, StreamObserver<ServiceInfo> responseObserver) {
        try {
            String serviceType = request.getServiceType();
            logger.info("Processing service discovery request for type: {}", 
                serviceType.isEmpty() ? "ALL" : serviceType);

            services.values().stream()
                .filter(entry -> !entry.isStale())
                .filter(entry -> serviceType.isEmpty() || entry.info.getServiceType().equals(serviceType))
                .forEach(entry -> {
                    responseObserver.onNext(entry.info);
                    logger.debug("Discovered service: {} at {}", 
                        entry.info.getServiceType(), 
                        entry.info.getAddress());
                });

            responseObserver.onCompleted();
            logger.info("Service discovery completed for type: {}", 
                serviceType.isEmpty() ? "ALL" : serviceType);

        } catch (Exception e) {
            logger.error("Error discovering services", e);
            responseObserver.onError(e);
        }
    }

    public static void selfRegister(String serviceType, String address) {
        String serviceId = serviceType + "_" + System.currentTimeMillis();
        ServiceInfo info = ServiceInfo.newBuilder()
            .setServiceType(serviceType)
            .setServiceId(serviceId)
            .setAddress(address)
            .build();

        // Try local registration first
        if (instance != null) {
            try {
                StreamObserver<Confirmation> observer = new StreamObserver<>() {
                    @Override
                    public void onNext(Confirmation confirmation) {
                        logger.info("Local registration successful for {} at {}", serviceType, address);
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("Error in local registration", t);
                    }

                    @Override
                    public void onCompleted() {
                        logger.debug("Local registration completed");
                    }
                };

                instance.register(info, observer);
                return;
            } catch (Exception e) {
                logger.error("Local registration failed, attempting remote registration", e);
            }
        }

        // Fallback to remote registration
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forTarget(REGISTRY_ADDRESS)
                .usePlaintext()
                .build();

            RegistryGrpc.RegistryBlockingStub stub = RegistryGrpc.newBlockingStub(channel);
            
            Confirmation confirmation = stub.register(info);
            logger.info("Remote registration successful for {} at {}: {}", 
                serviceType, address, confirmation.getStatus());

        } catch (Exception e) {
            logger.error("Remote registration failed for {} at {}", serviceType, address, e);
            throw new RuntimeException("Service registration failed", e);
        } finally {
            if (channel != null) {
                try {
                    channel.shutdown();
                    if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                        channel.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void shutdown() {
        isRunning.set(false);
        try {
            cleanupExecutor.shutdown();
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        services.clear();
        instance = null;
        logger.info("Registry Service shut down");
    }

    public int getRegisteredServicesCount() {
        return services.size();
    }
}
