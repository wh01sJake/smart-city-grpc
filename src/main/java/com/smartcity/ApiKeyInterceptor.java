package com.smartcity;

import io.grpc.*;

/**
 * ClassName: ApiKeyInterceptor
 * Description:
 *
 * ApiKeyInterceptor class to validate API keys on the server side
 *
 * Datetime: 2025/4/4 18:36
 * Author: @Likun.Fang
 * Version: 1.0
 */
public class ApiKeyInterceptor implements ServerInterceptor {
    private static final String API_KEY = "smartcityjake";
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String key = headers.get(Metadata.Key.of("apikey", Metadata.ASCII_STRING_MARSHALLER));
        if (key == null || !key.equals(API_KEY)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid API key"), headers);
            return new ServerCall.Listener<>() {};
        }
        return next.startCall(call, headers);
    }
}