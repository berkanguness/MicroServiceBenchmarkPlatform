package com.benchmark.patient_service.config;

import com.benchmark.proto.LabServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel labChannel(@Value("${lab.grpc-address}") String address) {
        return ManagedChannelBuilder.forTarget(address).usePlaintext().build();
    }

    @Bean
    LabServiceGrpc.LabServiceBlockingStub labStub(ManagedChannel labChannel) {
        return LabServiceGrpc.newBlockingStub(labChannel);
    }
}