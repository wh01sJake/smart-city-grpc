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
import java.io.IOException;

public class ServerMain {
    public static void main(String[] args) throws IOException, InterruptedException {
        // Initialize service registry
        RegistryService registryService = new RegistryService();
        System.out.println("[INFO] Initializing service registry...");

        Server server = ServerBuilder.forPort(50051)
                .addService(new TrafficService())
                .addService(new BinService())
                .addService(new NoiseService())
                .addService(registryService)
                .build()
                .start();

        // Self-register services
        String serverAddress = "localhost:50051";
        System.out.println("[INFO] Registering core services...");
        RegistryService.selfRegister("traffic", serverAddress);
        RegistryService.selfRegister("bins", serverAddress);
        RegistryService.selfRegister("noise", serverAddress);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[INFO] Shutting down server...");
            server.shutdown();
            System.out.println("[INFO] Server stopped");
        }));

        // Startup banner
        System.out.println("========================================");
        System.out.println("Server listening on port 50051");
        System.out.println("Registered services: " + registryService.getRegisteredServicesCount());
        System.out.println("========================================");
        server.awaitTermination();
    }
}