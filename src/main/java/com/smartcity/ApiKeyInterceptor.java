package com.smartcity;

import io.grpc.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-side interceptor that validates API keys and optional bearer tokens for incoming gRPC requests.
 * This interceptor ensures that all incoming requests have valid authentication before
 * allowing them to proceed to the service implementations.
 *
 * Datetime: 2025/4/4 18:36
 * Author: @Likun.Fang
 * Version: 1.1
 */
public class ApiKeyInterceptor implements ServerInterceptor {
    private static final Logger logger = LogManager.getLogger(ApiKeyInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        
        try {
            String methodName = call.getMethodDescriptor().getFullMethodName();
            logger.debug("Validating authentication for method: {}", methodName);

            // Validate API key
            Status status = validateApiKey(headers);
            if (!status.isOk()) {
                logger.warn("API key validation failed for method {}: {}", methodName, status.getDescription());
                call.close(status, headers);
                return new ServerCall.Listener<>() {};
            }

            // Validate bearer token if present
            String authHeader = headers.get(SecurityConfig.Auth.AUTH_HEADER);
            if (authHeader != null && !authHeader.trim().isEmpty()) {
                status = validateBearerToken(authHeader);
                if (!status.isOk()) {
                    logger.warn("Bearer token validation failed for method {}: {}", methodName, status.getDescription());
                    call.close(status, headers);
                    return new ServerCall.Listener<>() {};
                }
            }

            logger.debug("Authentication successful for method: {}", methodName);
            return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override
                public void close(Status status, Metadata trailers) {
                    if (!status.isOk()) {
                        logger.warn("Call failed with status: {} - {} for method: {}", 
                            status.getCode(),
                            status.getDescription(),
                            getMethodDescriptor().getFullMethodName());
                    }
                    super.close(status, trailers);
                }
            }, headers);

        } catch (Exception e) {
            logger.error("Error validating authentication", e);
            call.close(Status.INTERNAL.withDescription(SecurityConfig.StatusCodes.INTERNAL_AUTH_ERROR), 
                new Metadata());
            return new ServerCall.Listener<>() {};
        }
    }

    /**
     * Validates the API key from request headers.
     *
     * @param headers The request metadata headers
     * @return Status.OK if valid, or appropriate error status
     */
    private Status validateApiKey(Metadata headers) {
        String key = headers.get(SecurityConfig.API_KEY_HEADER);
        if (key == null || key.trim().isEmpty()) {
            return Status.UNAUTHENTICATED.withDescription(SecurityConfig.StatusCodes.MISSING_API_KEY);
        }
        if (!SecurityConfig.DEFAULT_API_KEY.equals(key)) {
            return Status.UNAUTHENTICATED.withDescription(SecurityConfig.StatusCodes.INVALID_API_KEY);
        }
        return Status.OK;
    }

    /**
     * Validates the bearer token if present in request headers.
     *
     * @param authHeader The authorization header value
     * @return Status.OK if valid, or appropriate error status
     */
    private Status validateBearerToken(String authHeader) {
        if (!authHeader.startsWith(SecurityConfig.Auth.TOKEN_PREFIX)) {
            return Status.UNAUTHENTICATED.withDescription(SecurityConfig.Auth.Errors.INVALID_FORMAT);
        }
        
        String token = authHeader.substring(SecurityConfig.Auth.TOKEN_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Status.UNAUTHENTICATED.withDescription(SecurityConfig.Auth.Errors.EMPTY_TOKEN);
        }

        // TODO: Implement actual token validation logic
        // For now, accept any non-empty token
        return Status.OK;
    }
}
