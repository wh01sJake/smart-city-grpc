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

import java.io.IOException;

public class ServerMain {
    private static final Logger logger = LogManager.getLogger(ServerMain.class);

    public static void main(String[] args) throws IOException, InterruptedException {

        // Initialize service registry
        RegistryService registryService = new RegistryService();
//        System.out.println("[INFO] Initializing service registry...");
        logger.info("Initializing service registry..."); // Replaced System.out

        Server server = ServerBuilder.forPort(50051)
                .intercept(new ApiKeyInterceptor())
                .addService(new TrafficService())
                .addService(new BinService())
                .addService(new NoiseService())
                .addService(registryService)
                .build()
                .start();

//        // Self-register services
//        String serverAddress = "localhost:50051";
//        System.out.println("[INFO] Registering core services...");
//        RegistryService.selfRegister("traffic", serverAddress);
//        RegistryService.selfRegister("bins", serverAddress);
//        RegistryService.selfRegister("noise", serverAddress);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down server...");
            server.shutdown();
            logger.info("Server stopped");
        }));

        // Updated startup banner with logger
        logger.info("========================================");
        logger.info("Server listening on port 50051");
        logger.info("Registered services: {}", registryService.getRegisteredServicesCount());
        logger.info("========================================");
        server.awaitTermination();
    }
}