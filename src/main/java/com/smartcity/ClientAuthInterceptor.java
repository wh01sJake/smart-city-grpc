package com.smartcity;

import io.grpc.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client-side interceptor that adds API key authentication to outgoing gRPC requests.
 * This interceptor automatically adds the API key and optionally a bearer token to
 * all outgoing requests to authenticate with server-side services.
 */
public class ClientAuthInterceptor implements ClientInterceptor {
    private static final Logger logger = LogManager.getLogger(ClientAuthInterceptor.class);
    
    private final String apiKey;
    private final String authToken;  // Optional bearer token

    /**
     * Creates a new client auth interceptor with an API key.
     *
     * @param apiKey The API key to include in all requests
     * @throws IllegalArgumentException if apiKey is null or empty
     */
    public ClientAuthInterceptor(String apiKey) {
        this(apiKey, null);
    }

    /**
     * Creates a new client auth interceptor with both API key and bearer token.
     *
     * @param apiKey The API key to include in all requests
     * @param authToken Optional bearer token for additional authentication
     * @throws IllegalArgumentException if apiKey is null or empty
     */
    public ClientAuthInterceptor(String apiKey, String authToken) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException(SecurityConfig.StatusCodes.MISSING_API_KEY);
        }
        this.apiKey = apiKey;
        this.authToken = authToken;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        
        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {
            
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                try {
                    logger.debug("Adding authentication headers for method: {}", method.getFullMethodName());
                    
                    // Add API key header
                    headers.put(SecurityConfig.API_KEY_HEADER, apiKey);
                    
                    // Add bearer token if provided
                    if (authToken != null && !authToken.trim().isEmpty()) {
                        headers.put(SecurityConfig.Auth.AUTH_HEADER, 
                            SecurityConfig.Auth.TOKEN_PREFIX + authToken.trim());
                    }
                    // Create a forwarding listener to handle errors
                    Listener<RespT> monitoringListener = new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            if (!status.isOk()) {
                                if (status.getCode() == Status.Code.UNAUTHENTICATED) {
                                    logger.error("Authentication failed: {}", status.getDescription());
                                } else {
                                    logger.warn("Call failed with status: {} - {}", status.getCode(), status.getDescription());
                                }
                            }
                            super.onClose(status, trailers);
                        }
                    };
                    
                    super.start(monitoringListener, headers);
                } catch (Exception e) {
                    logger.error("Error adding authentication headers to request", e);
                    responseListener.onClose(
                        Status.INTERNAL.withDescription(SecurityConfig.StatusCodes.INTERNAL_AUTH_ERROR), 
                        new Metadata());
                }
            }
        };
    }
}
