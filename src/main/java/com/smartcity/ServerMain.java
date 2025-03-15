package com.smartcity;

import com.smartcity.services.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;

public class ServerMain {
    public static void main(String[] args) throws IOException, InterruptedException {
        RegistryService registryService = new RegistryService();

        Server server = ServerBuilder.forPort(50051)
                .addService(new TrafficService())
                .addService(new BinService())
                .addService(new NoiseService())
                .addService(registryService)
                .build()
                .start();

        String serverAddress = "localhost:50051";
        RegistryService.selfRegister("traffic", serverAddress);
        RegistryService.selfRegister("bins", serverAddress);
        RegistryService.selfRegister("noise", serverAddress);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            System.out.println("Server shutdown");
        }));

        System.out.println("Server listening on port 50051");
        System.out.println("Registered services: " + registryService.getRegisteredServicesCount());
        server.awaitTermination();
    }
}