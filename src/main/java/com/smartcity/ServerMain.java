/**
 * Main server application for the Smart City system.
 * This class initializes and manages all gRPC services in the system.
 *
 * The server architecture consists of two separate gRPC servers:
 * 1. Registry Server - Hosts the service registry for service discovery
 * 2. Service Server - Hosts all the smart city services (Traffic, Bin, Noise)
 *
 * Features:
 * - Graceful startup and shutdown of all services
 * - API key authentication for all service endpoints
 * - Comprehensive error handling and logging
 * - Runtime shutdown hook for proper cleanup
 */

package com.smartcity;
import com.smartcity.services.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Main server class that initializes and manages all Smart City services.
 * This class is responsible for starting and stopping the gRPC servers,
 * initializing all services, and handling graceful shutdown.
 */
public class ServerMain {
    private static final Logger logger = LogManager.getLogger(ServerMain.class);
    private static final int REGISTRY_PORT = 50050;
    private static final int SERVICE_PORT = 50051;

    private Server registryServer;
    private Server serviceServer;

    /**
     * Starts all servers and initializes all services.
     * This method performs the following steps:
     * 1. Starts the Registry Server on the configured port
     * 2. Initializes all service implementations (Traffic, Bin, Noise)
     * 3. Starts the Service Server hosting all smart city services
     * 4. Logs startup information and service status
     *
     * @throws Exception If any error occurs during server startup
     */
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

    /**
     * Stops all servers gracefully.
     * This method performs the following steps:
     * 1. Attempts to shut down the Service Server gracefully
     * 2. Forces shutdown if graceful shutdown fails
     * 3. Attempts to shut down the Registry Server gracefully
     * 4. Forces shutdown if graceful shutdown fails
     *
     * The method ensures that all servers are properly stopped even if errors occur,
     * preventing resource leaks and allowing for clean application termination.
     */
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

    /**
     * Main entry point for the Smart City server application.
     * This method initializes the server, sets up a shutdown hook for graceful termination,
     * and keeps the application running until terminated by the user.
     *
     * @param args Command line arguments (not used)
     */
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
