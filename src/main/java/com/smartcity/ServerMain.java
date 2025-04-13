/**
 * gRPC Server Main Class
 * Hosts services:
 * - Traffic light service
 * - Bin monitoring service
 * - Noise tracking service
 * - Service registry
 */

package com.smartcity;
import com.smartcity.services.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;

public class ServerMain {
    private static final Logger logger = LogManager.getLogger(ServerMain.class);
    private static final int REGISTRY_PORT = 50050;
    private static final int SERVICE_PORT = 50051;
    
    private Server registryServer;
    private Server serviceServer;

    public void start() throws Exception {
        try {
            // Initialize Registry Server
            logger.info("Initializing service registry...");
            RegistryService registryService = new RegistryService();
            registryServer = ServerBuilder.forPort(REGISTRY_PORT)
                .intercept(new ApiKeyInterceptor())
                .addService(registryService)
                .build();
            registryServer.start();
            logger.info("Registry Server started on port {}", REGISTRY_PORT);

            // Initialize Services
            logger.info("Initializing services...");
            TrafficService trafficService;
            BinService binService;
            NoiseService noiseService;

            try {
                logger.debug("Initializing Traffic Service...");
                trafficService = new TrafficService();
                
                logger.debug("Initializing Bin Service...");
                binService = new BinService();
                
                logger.debug("Initializing Noise Service...");
                noiseService = new NoiseService();
                
                logger.info("All services initialized successfully");
            } catch (Exception e) {
                logger.error("Error initializing services", e);
                throw new RuntimeException("Failed to initialize services", e);
            }

            // Initialize Service Server
            serviceServer = ServerBuilder.forPort(SERVICE_PORT)
                .intercept(new ApiKeyInterceptor())
                .addService(trafficService)
                .addService(binService)
                .addService(noiseService)
                .build();
            serviceServer.start();
            
            // Log startup information
            logger.info("========================================");
            logger.info("Registry Server running on port {}", REGISTRY_PORT);
            logger.info("Service Server running on port {}", SERVICE_PORT);
            logger.info("Active Services: Traffic, Bin, Noise");
            logger.info("Registered Services Count: {}", registryService.getRegisteredServicesCount());
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("Failed to start servers", e);
            stop(); // Ensure cleanup on failure
            throw e;
        }
    }

    public void stop() {
        try {
            if (serviceServer != null) {
                logger.info("Shutting down service server...");
                serviceServer.shutdown();
                if (!serviceServer.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Service server did not terminate gracefully, forcing shutdown");
                    serviceServer.shutdownNow();
                }
            }
        } catch (Exception e) {
            logger.error("Error shutting down service server", e);
            if (serviceServer != null) {
                serviceServer.shutdownNow();
            }
        }

        try {
            if (registryServer != null) {
                logger.info("Shutting down registry server...");
                registryServer.shutdown();
                if (!registryServer.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Registry server did not terminate gracefully, forcing shutdown");
                    registryServer.shutdownNow();
                }
            }
        } catch (Exception e) {
            logger.error("Error shutting down registry server", e);
            if (registryServer != null) {
                registryServer.shutdownNow();
            }
        }
        
        logger.info("All servers stopped");
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (serviceServer != null) {
            serviceServer.awaitTermination();
        }
        if (registryServer != null) {
            registryServer.awaitTermination();
        }
    }

    public static void main(String[] args) {
        ServerMain server = new ServerMain();
        try {
            server.start();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Received shutdown signal");
                server.stop();
            }));

            // Wait for termination
            server.blockUntilShutdown();

        } catch (Exception e) {
            logger.error("Fatal error starting server", e);
            System.exit(1);
        }
    }
}
