package com.smartcity;

import io.grpc.Metadata;

/**
 * Central configuration for security-related constants and settings.
 * This class provides shared security configuration for both client and server interceptors.
 */
public final class SecurityConfig {
    // Prevent instantiation
    private SecurityConfig() {}

    /**
     * API key header name used in both client and server interceptors.
     */
    public static final String API_KEY_HEADER_NAME = "x-api-key";
    
    /**
     * Metadata key for API key header, used in both client and server interceptors.
     */
    public static final Metadata.Key<String> API_KEY_HEADER = 
        Metadata.Key.of(API_KEY_HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Default API key for development environment.
     * In production, this should be loaded from environment variables or secure configuration.
     */
    public static final String DEFAULT_API_KEY = "smartcityjake";

    /**
     * Status codes for authentication errors
     */
    public static final class StatusCodes {
        public static final String MISSING_API_KEY = "Missing API key";
        public static final String INVALID_API_KEY = "Invalid API key";
        public static final String INTERNAL_AUTH_ERROR = "Internal authentication error";
    }
    
    /**
     * Authentication-related constants
     */
    public static final class Auth {
        /**
         * Default authentication token prefix (Bearer)
         */
        public static final String TOKEN_PREFIX = "Bearer ";
        
        /**
         * Authentication header name
         */
        public static final String AUTH_HEADER_NAME = "authorization";
        
        /**
         * Metadata key for authorization header
         */
        public static final Metadata.Key<String> AUTH_HEADER = 
            Metadata.Key.of(AUTH_HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);

        /**
         * Error messages for bearer token validation
         */
        public static final class Errors {
            public static final String INVALID_FORMAT = "Invalid authorization header format";
            public static final String EMPTY_TOKEN = "Empty bearer token";
            public static final String INVALID_TOKEN = "Invalid bearer token";
            public static final String EXPIRED_TOKEN = "Bearer token has expired";
        }
    }
}

