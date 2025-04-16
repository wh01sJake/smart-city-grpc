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

/**
 * Registry Service implementation that provides service discovery and registration.
 * This service acts as the central hub for all other services in the system,
 * allowing them to register themselves and be discovered by clients.
 *
 * Features:
 * - Service registration with automatic heartbeat tracking
 * - Service discovery with optional filtering by service type
 * - Automatic cleanup of stale services
 * - Thread-safe implementation for concurrent access
 */
public class RegistryService extends RegistryGrpc.RegistryImplBase {
    private static final Logger logger = LogManager.getLogger(RegistryService.class);
    private static final String REGISTRY_ADDRESS = "localhost:50050";
    private static final long SERVICE_TIMEOUT_SECONDS = 30;
    private static final long CLEANUP_INTERVAL_SECONDS = 15;

    /**
     * Inner class representing a registered service with heartbeat tracking.
     * Each service entry contains the service information and tracks when it was last seen.
     */
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

    /**
     * Starts a background task that periodically removes stale services.
     * This ensures that services that have crashed or stopped responding are
     * automatically removed from the registry.
     */
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

    /**
     * Registers a service with the registry.
     * This method is called by services when they start up to make themselves discoverable.
     * If a service with the same key already exists, its registration is updated.
     *
     * @param request The service information to register
     * @param responseObserver Observer for sending the registration confirmation
     */
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

    /**
     * Discovers services registered with the registry.
     * This method is called by clients to find available services.
     * It streams back information about all non-stale services that match the filter.
     *
     * @param request Filter specifying which service types to discover (empty returns all)
     * @param responseObserver Observer for streaming back discovered services
     */
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

    /**
     * Registers a service with the registry.
     * This static method is used by services to register themselves.
     * It first tries to register locally if the registry is in the same JVM,
     * then falls back to remote registration if needed.
     *
     * @param serviceType The type of service being registered
     * @param address The network address where the service is running
     */
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

    /**
     * Shuts down the registry service.
     * This method stops the cleanup task, clears all registered services,
     * and resets the singleton instance.
     */
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
