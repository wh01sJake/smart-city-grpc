# Smart City Monitoring and Control System

## Project Overview
The Smart City Monitoring and Control System is a distributed application that simulates an interconnected network of smart services for urban management. Built using gRPC for communication between services, the system provides real-time monitoring and control capabilities for various city services through a centralized dashboard.

## Key Features

### Multiple Integrated Services
- **Traffic Management Service**: Controls traffic lights and monitors vehicle flow at intersections
- **Waste Management Service**: Tracks bin fill levels and optimizes collection routes
- **Noise Monitoring Service**: Measures noise levels across different zones with configurable thresholds
- **Registry Service**: Enables automatic service discovery and registration

### Advanced Communication Patterns
The system demonstrates all four gRPC communication styles:
- **Unary RPC**: For simple request-response operations (e.g., setting traffic lights)
- **Server Streaming**: For continuous data monitoring (e.g., traffic flow updates)
- **Client Streaming**: For batch reporting (e.g., bin status updates)
- **Bidirectional Streaming**: For real-time interactive monitoring (e.g., noise alerts)

### Security and Reliability
- API key authentication for secure service access
- Comprehensive error handling and logging
- Automatic service discovery and reconnection
- Simulated data generation for demonstration purposes

### User Interface
A JavaFX-based dashboard provides:
- Real-time visualization of city service data
- Interactive controls for managing traffic, waste collection, and noise thresholds
- Service status monitoring and system logs
- Responsive design with intuitive tab-based navigation

## Technical Implementation

### Architecture
The system follows a microservices architecture where each service operates independently but communicates through well-defined gRPC interfaces. The Registry Service acts as the central hub for service discovery, allowing for dynamic scaling and resilience.

### Technologies Used
- **Java**: Core programming language
- **gRPC**: For efficient service communication
- **Protocol Buffers**: For service interface definition
- **JavaFX**: For the graphical user interface
- **Log4j**: For comprehensive logging
- **Maven**: For project management and dependency handling

## Getting Started

### Prerequisites
- Java 11 or higher
- Maven 3.6 or higher

### Running the Application

1. Start the server with all services:
```
mvn compile exec:java -Dexec.mainClass="com.smartcity.ServerMain"
```

2. Launch the dashboard client:
```
mvn compile exec:java -Dexec.mainClass="com.smartcity.client.ClientMain"
```

### Usage Guide
1. The dashboard will automatically connect to available services
2. Use the tabs to navigate between different service controls:
   - **Dashboard**: Overview of all services
   - **Traffic**: Control traffic lights and view vehicle counts
   - **Waste**: Monitor bin fill levels and get collection routes
   - **Noise**: Set noise thresholds and view alerts
   - **System**: View service status and logs

## Project Structure
- `src/main/proto`: Protocol buffer definitions
- `src/main/java/com/smartcity/services`: Service implementations
- `src/main/java/com/smartcity/client`: Client and GUI implementation
- `src/main/java/com/smartcity/security`: Authentication components

## Future Enhancements
- Additional smart city services (e.g., energy management, public transport)
- Enhanced data visualization and analytics
- Mobile client application
- Integration with real IoT devices

## Acknowledgments
This project was developed as part of the Distributed Systems course at the National College of Ireland.
