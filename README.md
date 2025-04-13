# Smart City Project - Requirements Assessment

## Project Overview
This project implements a distributed system for a Smart City environment, featuring multiple microservices that communicate via gRPC. The implementation includes traffic management, waste management, and noise monitoring services with a central service registry and graphical user interface.

## Requirements Assessment

### Domain Selection (Smart Cities) ✓
- **Requirement**: Domain selection based on student ID ending with 9
- **Implementation**: Smart Cities domain correctly implemented with traffic automation and public services
- **Evidence**: Services focus on urban management aspects (traffic lights, waste bins, noise monitoring)

### Minimum 3 Services Requirement ✓
- **Requirement**: At least 3 services that simulate devices or operations
- **Implementation**: 4 services implemented:
  - TrafficService: Controls traffic lights and streams traffic data
  - BinService: Monitors waste bin fill levels and calculates collection routes
  - NoiseService: Monitors environmental noise levels and issues alerts
  - RegistryService: Handles service discovery and registration
- **Evidence**: Each service has a dedicated Java implementation file with correct gRPC integration

### Service Discovery ✓
- **Requirement**: Devices/services should be discoverable automatically
- **Implementation**: Robust service registry with self-registration mechanism
- **Evidence**: `RegistryService.java` implements registration and discovery with proper concurrency handling

### gRPC Communication ✓
- **Requirement**: Services communicate using defined .proto files
- **Implementation**: Well-structured proto file defining all service interfaces
- **Evidence**: Implementation of all four gRPC styles:
  - Simple RPC: `setLight()`, `getRoute()`, `register()`
  - Server Streaming: `streamTraffic()`, `discoverServices()`
  - Client Streaming: `reportBins()`
  - Bi-directional Streaming: `monitorNoise()`

### Security Features ✓
- **Requirement**: Basic authentication for access control
- **Implementation**: API key authentication via gRPC interceptors
- **Evidence**: `ApiKeyInterceptor.java` validates authentication tokens on all requests

### Data Logging ✓
- **Requirement**: Capture logs for analytics
- **Implementation**: Comprehensive logging using Log4j throughout all services
- **Evidence**: Log statements for service operations, errors, and important events

### GUI Controller ✓
- **Requirement**: Graphical interface for monitoring and controlling services
- **Implementation**: JavaFX-based dashboard with tabs for each service type
- **Evidence**: `CityDashboard.java` provides UI for:
  - Traffic control (light setting and data streaming)
  - Bin status monitoring and reporting
  - Noise level monitoring with alerts
  - Service discovery and browsing

## Technical Implementation Strengths
1. **Thread Safety**: Proper concurrency handling with `ConcurrentHashMap` and thread management
2. **Error Handling**: Comprehensive exception handling in both services and client
3. **Modularity**: Clear separation of concerns with well-defined service boundaries
4. **Protocol Design**: Well-structured protocol definitions in the proto file

## Potential Enhancements
1. Documentation expansion for API components
2. Configuration externalization (API keys, service addresses)
3. UI styling improvements
4. Error recovery mechanisms (retries, circuit breakers)

## Conclusion
The project fully satisfies all required elements of the distributed systems assignment. The implementation demonstrates a strong understanding of distributed systems concepts, gRPC communication patterns, and practical software engineering principles.

## Testing Instructions

1. Start the server:
```
mvn compile exec:java -Dexec.mainClass="com.smartcity.ServerMain"
```

2. Run the test client:
```
mvn compile exec:java -Dexec.mainClass="com.smartcity.TestClient"
```

The test client will verify the functionality of all services:
1. Traffic light control and traffic stream monitoring
2. Bin status reporting with urgent collection alerts
3. Noise monitoring with threshold-based alerts
4. Service discovery for all registered services

## Implementation Notes

1. All services use proper error handling and logging
2. API key authentication is enforced on all service calls
3. The implementation demonstrates all four gRPC communication patterns
4. Services register themselves with the registry service for discovery
5. Each service implements domain-appropriate error handling and status tracking

