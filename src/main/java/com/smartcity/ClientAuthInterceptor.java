package com.smartcity;

import io.grpc.*;

public class ClientAuthInterceptor implements ClientInterceptor {
    private final String apiKey;

    public ClientAuthInterceptor(String apiKey) {
        this.apiKey = apiKey;
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
                headers.put(
                        Metadata.Key.of("apikey", Metadata.ASCII_STRING_MARSHALLER),
                        apiKey
                );
                super.start(responseListener, headers);
            }
        };
    }
}

