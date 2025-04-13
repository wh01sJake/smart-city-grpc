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
    private static final int REGISTRY_PORT = 50051;
    private static final int TRAFFIC_PORT = 50052;
    private static final int BIN_PORT = 50053;
    private static final int NOISE_PORT = 50054;

    public static void main(String[] args) throws IOException, InterruptedException {

        // Initialize service registry
        RegistryService registryService = new RegistryService();
        logger.info("Initializing service registry...");

        // Create service instances with distinct addresses
        String registryAddress = "localhost:" + REGISTRY_PORT;
        TrafficService trafficService = new TrafficService("localhost:" + TRAFFIC_PORT);
        BinService binService = new BinService("localhost:" + BIN_PORT);
        NoiseService noiseService = new NoiseService("localhost:" + NOISE_PORT);

        Server server = ServerBuilder.forPort(REGISTRY_PORT)
                .intercept(new ApiKeyInterceptor())
                .addService(trafficService)
                .addService(binService)
                .addService(noiseService)
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
        logger.info("Server listening on port {}", REGISTRY_PORT);
        logger.info("Traffic service registered at port {}", TRAFFIC_PORT);
        logger.info("Bin service registered at port {}", BIN_PORT);
        logger.info("Noise service registered at port {}", NOISE_PORT);
        logger.info("Total registered services: {}", registryService.getRegisteredServicesCount());
        logger.info("========================================");
        server.awaitTermination();
    }
}
