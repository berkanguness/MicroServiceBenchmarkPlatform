package com.benchmark.patient_service.config;

import com.benchmark.proto.LabServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    LabServiceGrpc.LabServiceBlockingStub labStub(GrpcChannelFactory channels){
        return LabServiceGrpc.newBlockingStub(channels.createChannel("lab"));
    }
}
