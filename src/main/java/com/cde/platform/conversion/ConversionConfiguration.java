package com.cde.platform.conversion;

import com.cde.platform.fetch.DestinationCheck;
import com.cde.platform.fetch.RemoteContentFetcher;
import com.cde.platform.repository.ConversionJobRepository;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.service.ConverterService;
import com.cde.platform.storage.StorageProperties;
import com.cde.platform.storage.StorageProvider;
import com.cde.platform.upload.UploadAdmissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the conversion pipeline, and only where fetching is switched on.
 *
 * <p>Every part of this depends on {@link RemoteContentFetcher}, which exists
 * only when {@code cde.fetch.enabled} is true. Sharing that condition is why
 * these classes carry no {@code @Service} or {@code @Component}: component
 * scanning would find them in a deployment that has deliberately switched
 * outbound fetching off, and the context would fail to start looking for a
 * fetcher that is correctly absent.
 *
 * <p>An air-gapped or PROTECTED deployment therefore has no conversion queue,
 * no workers and no endpoint — not a disabled one. There is no code path to
 * reach.
 */
@Configuration
@EnableConfigurationProperties(ConversionJobProperties.class)
@ConditionalOnProperty(prefix = "cde.fetch", name = "enabled", havingValue = "true")
public class ConversionConfiguration {

    @Bean
    public ConversionWorkQueue conversionWorkQueue(ConversionJobProperties properties) {
        return new ConversionWorkQueue(properties);
    }

    @Bean
    public ConversionJobStateWriter conversionJobStateWriter(ConversionJobRepository jobs) {
        return new ConversionJobStateWriter(jobs);
    }

    @Bean
    public ConversionJobService conversionJobService(ConversionJobRepository jobs,
                                                     ConversionWorkQueue queue,
                                                     DestinationCheck destinationCheck) {
        return new ConversionJobService(jobs, queue, destinationCheck);
    }

    @Bean
    public ConversionPipeline conversionPipeline(RemoteContentFetcher fetcher,
                                                 UploadAdmissionService admission,
                                                 ConverterService converter,
                                                 StorageProvider storage,
                                                 ConversionJobStateWriter state,
                                                 ConversionJobProperties properties,
                                                 StorageProperties storageProperties,
                                                 @Value("${cde.storage.upload-dir}")
                                                 String uploadDir) {
        return new ConversionPipeline(fetcher, admission, converter, storage, state,
                                      properties, storageProperties, uploadDir);
    }

    @Bean
    public ConversionJobExecutor conversionJobExecutor(ConversionWorkQueue queue,
                                                       ConversionPipeline pipeline,
                                                       ConversionJobProperties properties) {
        return new ConversionJobExecutor(queue, pipeline, properties);
    }

    @Bean
    public ConversionStartupRecovery conversionStartupRecovery(ConversionJobService jobService,
                                                               TenantRepository tenants,
                                                               ConversionJobExecutor executor) {
        return new ConversionStartupRecovery(jobService, tenants, executor);
    }
}
